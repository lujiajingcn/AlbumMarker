package com.albummarker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.albummarker.data.MediaQuery
import com.albummarker.data.model.ModelManager
import com.albummarker.data.model.buildModelPickerIntent
import com.albummarker.databinding.ActivityModelManageBinding
import com.albummarker.databinding.ItemModelRowBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 需求 6：Qwen3.5-0.8B 模型管理页。
 * 支持 SAF 导入与 URL 下载两条安装路径，删除模型，展示实时状态。
 */
class ModelManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelManageBinding
    private var progressDialog: AlertDialog? = null
    private var modelJob: Job? = null

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

        lifecycleScope.launch {
            Graph.modelManager.models.collect { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        Graph.modelManager.refresh()
        // models 列表在只切换 active 时内容不变，StateFlow 不会重发；这里主动重绘一次
        render(Graph.modelManager.models.value)
    }

    private fun render(models: List<ModelManager.InstalledModel>) {
        val activePath = Graph.modelManager.activeModelPath
        val active = models.firstOrNull { it.file.absolutePath == activePath }
        binding.tvModelStatus.text = if (models.isEmpty()) {
            getString(R.string.model_status_none)
        } else {
            getString(
                R.string.model_status_count,
                models.size,
                active?.displayName ?: "-"
            )
        }

        binding.llModels.removeAllViews()
        models.forEach { model ->
            val row = ItemModelRowBinding.inflate(layoutInflater, binding.llModels, false)
            row.tvModelName.text = model.displayName
            row.tvModelSize.text = MediaQuery.formatSize(model.sizeBytes)
            row.rbModel.isChecked = model.file.absolutePath == activePath
            val select = android.view.View.OnClickListener {
                Graph.modelManager.setActive(model)
                // 只切换 active 不会改变 models 列表，StateFlow 可能不发射，主动刷新 UI
                render(Graph.modelManager.models.value)
                snack(getString(R.string.model_switched, model.displayName))
            }
            row.root.setOnClickListener(select)
            row.tvModelName.setOnClickListener(select)
            row.tvModelSize.setOnClickListener(select)
            row.rbModel.setOnClickListener(select)
            row.btnDeleteModelRow.setOnClickListener { confirmDelete(model) }
            binding.llModels.addView(row.root)
        }
    }

    private fun importModel(uri: Uri) {
        showProgress(getString(R.string.model_importing), cancelable = true)
        modelJob = lifecycleScope.launch {
            try {
                val result = Graph.modelManager.importFromUri(uri)
                result.onSuccess { file ->
                    snack(getString(R.string.model_imported, file.name))
                }.onFailure { e ->
                    toast(getString(R.string.model_import_failed, e.message ?: ""))
                }
            } finally {
                dismissProgress()
            }
        }
    }

    private fun downloadModel() {
        val url = binding.etModelUrl.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) {
            toast(getString(R.string.model_url_hint))
            return
        }
        showProgress(getString(R.string.model_download_progress, 0), cancelable = true)
        modelJob = lifecycleScope.launch {
            try {
                val result = Graph.modelManager.downloadFromUrl(url) { percent ->
                    runOnUiThread {
                        progressDialog?.setMessage(getString(R.string.model_download_progress, percent))
                    }
                }
                result.onSuccess { file ->
                    snack(getString(R.string.model_imported, file.name))
                }.onFailure { e ->
                    toast(getString(R.string.model_download_failed, e.message ?: ""))
                }
            } finally {
                dismissProgress()
            }
        }
    }

    private fun confirmDelete(model: ModelManager.InstalledModel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.model_delete)
            .setMessage(getString(R.string.model_delete_named_msg, model.displayName))
            .setPositiveButton(R.string.confirm) { _, _ ->
                Graph.modelManager.deleteModel(model)
                snack(getString(R.string.model_deleted))
            }
            .setNegativeButton(R.string.auto_tag_cancel, null)
            .show()
    }

    private fun showProgress(message: String, cancelable: Boolean = false) {
        progressDialog?.dismiss()
        val builder = MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setCancelable(cancelable)
            .setView(ProgressBar(this))
        if (cancelable) {
            builder.setNegativeButton(R.string.auto_tag_cancel) { _, _ ->
                modelJob?.cancel()
            }
        }
        progressDialog = builder.create().also { it.show() }
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroy()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun snack(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}
