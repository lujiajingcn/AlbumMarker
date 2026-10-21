package com.albummarker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.albummarker.data.MediaQuery
import com.albummarker.data.model.ModelManager
import com.albummarker.data.model.buildModelPickerIntent
import com.albummarker.databinding.ActivityModelManageBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * 需求 6：Qwen3.5-0.8B 模型管理页。
 * 支持 SAF 导入与 URL 下载两条安装路径，删除模型，展示实时状态。
 */
class ModelManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelManageBinding
    private var progressDialog: AlertDialog? = null

    private val pickModelLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri: Uri = result.data?.data ?: return@registerForActivityResult
            importModel(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnImportFile.setOnClickListener {
            pickModelLauncher.launch(buildModelPickerIntent())
        }
        binding.btnDownloadModel.setOnClickListener { downloadModel() }
        binding.btnDeleteModel.setOnClickListener { confirmDelete() }

        lifecycleScope.launch {
            Graph.modelManager.state.collect { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        Graph.modelManager.refresh()
    }

    private fun render(state: ModelManager.ModelState) {
        binding.tvModelStatus.text = when (state) {
            is ModelManager.ModelState.NotInstalled -> getString(R.string.model_status_none)
            is ModelManager.ModelState.Ready -> getString(
                R.string.model_status_ready,
                state.file.name,
                MediaQuery.formatSize(state.sizeBytes)
            )

            is ModelManager.ModelState.Broken ->
                getString(R.string.model_status_broken, state.message)
        }
        binding.btnDeleteModel.isEnabled = state is ModelManager.ModelState.Ready
    }

    private fun importModel(uri: Uri) {
        showProgress(getString(R.string.model_importing))
        lifecycleScope.launch {
            val result = Graph.modelManager.importFromUri(uri)
            dismissProgress()
            result.onSuccess { file ->
                snack(getString(R.string.model_imported, file.name))
            }.onFailure { e ->
                toast(getString(R.string.model_import_failed, e.message ?: ""))
            }
        }
    }

    private fun downloadModel() {
        val url = binding.etModelUrl.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            toast(getString(R.string.model_url_hint))
            return
        }
        showProgress(getString(R.string.model_download_progress, 0))
        lifecycleScope.launch {
            val result = Graph.modelManager.downloadFromUrl(url) { percent ->
                runOnUiThread {
                    progressDialog?.setMessage(getString(R.string.model_download_progress, percent))
                }
            }
            dismissProgress()
            result.onSuccess { file ->
                snack(getString(R.string.model_imported, file.name))
            }.onFailure { e ->
                toast(getString(R.string.model_download_failed, e.message ?: ""))
            }
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.model_delete)
            .setMessage(R.string.model_delete_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                Graph.modelManager.deleteModel()
                snack(getString(R.string.model_deleted))
            }
            .setNegativeButton(R.string.auto_tag_cancel, null)
            .show()
    }

    private fun showProgress(message: String) {
        progressDialog?.dismiss()
        progressDialog = MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setCancelable(false)
            .setView(ProgressBar(this))
            .create()
            .also { it.show() }
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun snack(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}
