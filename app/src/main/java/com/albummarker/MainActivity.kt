package com.albummarker

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.albummarker.data.MediaQuery
import com.albummarker.data.TagEmbedder
import com.albummarker.data.TaggedMedia
import com.albummarker.databinding.ActivityMainBinding
import com.albummarker.ui.MediaGridAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * 主界面：权限申请 → 媒体网格（图片 + 视频混合，按修改时间倒序）。
 * 也承担「从其他应用 VIEW 打开单个图片/视频」的入口。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaGridAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // 与 hasMediaPermissions() 保持一致：任意一个读权限 granted 即放行
        // （兼容 Android 14+ 的「 selected photos 」部分照片访问）。
        if (grants.isNotEmpty() && grants.values.any { it }) {
            loadMedia()
        } else {
            binding.tvEmpty.isVisible = true
            binding.tvEmpty.setText(R.string.perm_denied)
            showPermissionRationale()
        }
    }

    /** 批量写标签的文件授权回调（Android 10+）。 */
    private val batchWriteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            toast(getString(R.string.embed_write_denied))
            return@registerForActivityResult
        }
        pendingBatch?.let { batch ->
            pendingBatch = null
            runBatchEmbed(batch)
        }
    }

    /** Android 10 及以下的存储写权限回调。 */
    private val legacyWriteLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast(getString(R.string.embed_write_denied))
            return@registerForActivityResult
        }
        pendingBatch?.let { batch ->
            pendingBatch = null
            runBatchEmbed(batch)
        }
    }

    /** 等待授权的批量写入清单（授权回调里消费）。 */
    private var pendingBatch: List<TaggedMedia>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnMore.setOnClickListener { showOverflowMenu(it) }

        adapter = MediaGridAdapter { item, position ->
            startActivity(
                Intent(this, MediaViewerActivity::class.java)
                    .putExtra(MediaViewerActivity.EXTRA_INDEX, position)
            )
        }
        binding.rvMedia.layoutManager = GridLayoutManager(this, 3)
        binding.rvMedia.adapter = adapter

        if (handleViewIntent(intent)) return

        if (hasMediaPermissions()) {
            loadMedia()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    /** 处理其他应用转发来的 VIEW intent：直接打开单个媒体。 */
    private fun handleViewIntent(intent: Intent?): Boolean {
        val uri: Uri = intent?.data ?: return false
        if (intent.action != Intent.ACTION_VIEW) return false
        val item = MediaQuery.fromSingleUri(this, uri) ?: return false
        MediaBus.current = listOf(item)
        startActivity(
            Intent(this, MediaViewerActivity::class.java)
                .putExtra(MediaViewerActivity.EXTRA_INDEX, 0)
        )
        finish()
        return true
    }

    private fun loadMedia() {
        binding.pbLoading.isVisible = true
        binding.tvEmpty.isVisible = false
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { MediaQuery.load(this@MainActivity) }
            binding.pbLoading.isVisible = false
            MediaBus.current = items
            adapter.submitList(items)
            binding.tvEmpty.isVisible = items.isEmpty()
            if (items.isNotEmpty()) binding.tvEmpty.setText(R.string.empty_gallery)
        }
    }

    /** 首页专属「更多」菜单：锚定浮动按钮弹出，与查看器里的样式一致。 */
    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_model_manage -> {
                    startActivity(Intent(this, ModelManageActivity::class.java))
                    true
                }

                R.id.action_embed_all -> {
                    onEmbedAllClicked()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    // ---- 批量把标签写进照片本体（XMP） ----

    private fun onEmbedAllClicked() {
        lifecycleScope.launch {
            // 直接读库拿带标签的媒体，避免对整相册重新算哈希
            val candidates = withContext(Dispatchers.IO) { Graph.tagStore.allTagged() }
                .filter { it.mime.startsWith("image/jpeg") }
            if (candidates.isEmpty()) {
                toast(getString(R.string.embed_batch_none))
                return@launch
            }
            pendingBatch = candidates
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ 改写非本应用创建的媒体文件，必须走系统授权对话框
                val request = MediaStore.createWriteRequest(
                    contentResolver,
                    candidates.map { Uri.parse(it.uri) }
                )
                batchWriteLauncher.launch(IntentSenderRequest.Builder(request).build())
            } else {
                legacyWriteLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun runBatchEmbed(candidates: List<TaggedMedia>) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.embed_batch_progress, 0, candidates.size))
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            for ((index, item) in candidates.withIndex()) {
                dialog.setMessage(
                    getString(R.string.embed_batch_progress, index + 1, candidates.size)
                )
                try {
                    val result = withContext(Dispatchers.IO) {
                        TagEmbedder.embed(this@MainActivity, Uri.parse(item.uri), item.tags)
                    }
                    Graph.tagStore.migrateHash(item.hash, result.newHash, result.newSize)
                    Graph.tagStore.invalidateHashCache(item.uri)
                    ok++
                } catch (e: Exception) {
                    Log.e("AlbumMarker", "batch embed failed: ${item.displayName}", e)
                    fail++
                }
            }
            dialog.dismiss()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setMessage(getString(R.string.embed_batch_summary, ok, fail))
                .setPositiveButton(R.string.confirm, null)
                .show()
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 决定「能否读到相册」的读权限（不含 ACCESS_MEDIA_LOCATION）。 */
    private fun readPermissions(): Array<String> =
        when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
            )

            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** 首次启动一并申请：读权限 + 位置（规避 Android 12 MediaProvider redaction 崩溃）。 */
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            readPermissions() + android.Manifest.permission.ACCESS_MEDIA_LOCATION
        } else readPermissions()

    /** 任意一个读权限 granted 即视为可用（兼容 Android 14+ 的部分照片访问）。 */
    private fun hasMediaPermissions(): Boolean =
        readPermissions().any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_title)
            .setMessage(R.string.perm_message)
            .setPositiveButton(R.string.perm_grant) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.auto_tag_cancel, null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }
}
