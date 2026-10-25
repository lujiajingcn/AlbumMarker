package com.albummarker

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.constraintlayout.widget.ConstraintSet
import androidx.recyclerview.widget.GridLayoutManager
import com.albummarker.data.MediaGridItem
import com.albummarker.data.MediaItem
import com.albummarker.data.MediaQuery
import com.albummarker.data.TagEmbedder
import com.albummarker.data.TagEntry
import com.albummarker.data.TaggedMedia
import com.albummarker.databinding.ActivityMainBinding
import com.albummarker.ui.MediaGridAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * 主界面：权限申请 → 媒体网格（图片 + 视频混合，按修改时间倒序）。
 * 也承担「从其他应用 VIEW 打开单个图片/视频」的入口。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** 标签显示开关的实例状态键（旋转屏幕等重建后恢复）。 */
        private const val KEY_SHOW_TAGS = "show_tags"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaGridAdapter

    /** 相册全量媒体；搜索时从这里过滤。 */
    private var allItems: List<MediaItem> = emptyList()
    private var searchQuery: String = ""
    private var searchJob: Job? = null

    /** 首页网格是否在缩略图上显示标签（「更多」菜单开关）。 */
    private var showTags: Boolean = false

    /** Uri 字符串 -> 标签列表；仅在 showTags 为 true 时有意义。 */
    private var tagsByUri: Map<String, List<TagEntry>> = emptyMap()

    /** 搜索框展开时，系统返回键先收起搜索，而不是退出应用。 */
    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            closeSearch()
        }
    }

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

        // 旋转屏幕等重建后，标签显示开关要保持原状
        showTags = savedInstanceState?.getBoolean(KEY_SHOW_TAGS) ?: false

        binding.btnMore.setOnClickListener { showOverflowMenu(it) }
        binding.btnSearch.setOnClickListener { openSearch() }
        binding.btnCloseSearch.setOnClickListener { closeSearch() }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                scheduleFilter()
            }
        })
        onBackPressedDispatcher.addCallback(this, searchBackCallback)

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOW_TAGS, showTags)
    }

    override fun onResume() {
        super.onResume()
        // 从查看器/模型管理返回时标签可能已变化，重新应用当前搜索条件
        if (::adapter.isInitialized && allItems.isNotEmpty()) {
            // 标签在查看器里被改过，映射要重新拉，否则网格上显示的是旧标签
            refreshFilter(reloadTags = true)
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
            allItems = items
            binding.pbLoading.isVisible = false
            // 首次进入也要把标签映射拉起来，否则冷启动时网格上不会有标签
            refreshFilter(reloadTags = true)
        }
    }

    // ---- 首页标签搜索 ----

    private fun openSearch() {
        binding.searchBar.isVisible = true
        binding.btnSearch.isVisible = false
        binding.btnMore.isVisible = false
        searchBackCallback.isEnabled = true
        setGridBelowSearch(true)
        binding.etSearch.requestFocus()
        binding.etSearch.post {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeSearch() {
        binding.etSearch.setText("")
        searchJob?.cancel()
        searchQuery = ""
        binding.etSearch.clearFocus()
        binding.searchBar.isVisible = false
        binding.btnSearch.isVisible = true
        binding.btnMore.isVisible = true
        searchBackCallback.isEnabled = false
        setGridBelowSearch(false)
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        searchJob = lifecycleScope.launch { applyFilterNow("") }
    }

    /** 搜索展开时把网格顶边约束到搜索框下方，避免第一排照片被搜索框盖住。 */
    private fun setGridBelowSearch(active: Boolean) {
        val set = ConstraintSet()
        set.clone(binding.root)
        if (active) {
            set.connect(R.id.rvMedia, ConstraintSet.TOP, R.id.searchBar, ConstraintSet.BOTTOM)
            set.setMargin(R.id.rvMedia, ConstraintSet.TOP, dp(8))
        } else {
            set.connect(R.id.rvMedia, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            set.setMargin(R.id.rvMedia, ConstraintSet.TOP, 0)
        }
        set.applyTo(binding.root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 输入防抖 250ms，避免每个字符都查一次库。 */
    private fun scheduleFilter() {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(250)
            applyFilterNow(searchQuery)
        }
    }

    /**
     * 重新按当前搜索条件刷新网格。
     * @param reloadTags 是否顺带重拉「Uri -> 标签」映射（标签在查看器里被改过时需要）
     */
    private fun refreshFilter(reloadTags: Boolean = false) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (reloadTags && showTags) {
                // tagsByUri() 走内存态（标签本来就是按哈希存在内存里的），不需要查库
                tagsByUri = Graph.tagStore.tagsByUri()
            }
            applyFilterNow(searchQuery)
        }
    }

    private suspend fun applyFilterNow(query: String) {
        val filtered = if (query.isBlank()) {
            allItems
        } else {
            val uris = withContext(Dispatchers.IO) {
                Graph.tagStore.searchMediaUris(query)
            }
            allItems.filter { it.uri.toString() in uris }
        }
        MediaBus.current = filtered
        adapter.submitList(filtered.map { MediaGridItem(it, tagsFor(it)) }) {
            if (query.isNotBlank()) binding.rvMedia.scrollToPosition(0)
        }
        if (filtered.isEmpty()) {
            binding.tvEmpty.isVisible = true
            binding.tvEmpty.setText(
                if (query.isBlank()) R.string.empty_gallery else R.string.search_no_result
            )
        } else {
            binding.tvEmpty.isVisible = false
        }
    }

    /** 取某个媒体的标签（隐藏状态下恒为空）。 */
    private fun tagsFor(item: MediaItem): List<TagEntry> {
        if (!showTags) return emptyList()
        return tagsByUri[item.uri.toString()].orEmpty()
    }

    // ---- 首页网格的标签显示开关 ----

    /**
     * 切换网格上的标签浮层。
     *
     * 标签映射由 TagStore 的内存态推导（标签本就按哈希存在内存里），
     * 不给整个相册逐个算 SHA-256，切换开销与相册总量无关。
     */
    private fun setShowTags(visible: Boolean) {
        if (showTags == visible) return
        showTags = visible
        refreshFilter(reloadTags = true)
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

                R.id.action_show_tags -> {
                    setShowTags(true)
                    true
                }

                R.id.action_hide_tags -> {
                    setShowTags(false)
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
                // 显式标注 Int：AGP 8.6.1 的 lint 推断不出内联算术表达式（i + 1）的类型，
                // 会误当成 String 报 StringFormatMatches 错。
                val done: Int = index + 1
                dialog.setMessage(
                    getString(R.string.embed_batch_progress, done, candidates.size)
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
