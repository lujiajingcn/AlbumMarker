package com.albummarker

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.albummarker.data.MediaItem
import com.albummarker.data.MediaHasher
import com.albummarker.data.MediaQuery
import com.albummarker.data.TagCatalog
import com.albummarker.data.TagEmbedder
import com.albummarker.data.TagEntry
import com.albummarker.data.TagParser
import com.albummarker.data.model.AutoTagEngine
import com.albummarker.databinding.ActivityMediaViewerBinding
import com.albummarker.ui.MediaPagerAdapter
import com.albummarker.ui.TagTreeAdapter
import com.albummarker.ui.ViewerViewModel
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 需求 2/3/4/5/6 的汇聚页：
 *  - ViewPager2 全屏浏览图片/视频；
 *  - 底部常驻菜单栏：「手动添加标签」「自动添加标签」；
 *  - 手动：菜单栏上方滑出输入面板（语音输入图标 + 确认图标）；
 *  - 标签以文件 SHA-256 哈希为键存入本地数据库。
 */
class MediaViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AlbumMarker"
        const val EXTRA_INDEX = "extra_index"
    }

    private lateinit var binding: ActivityMediaViewerBinding
    private val viewModel: ViewerViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[ViewerViewModel::class.java]
    }

    private lateinit var pagerAdapter: MediaPagerAdapter
    private var mediaList: List<MediaItem> = emptyList()
    private var current: MediaItem? = null
    private var currentHash: String? = null
    private var hashJob: Job? = null
    private var panelVisible = false

    /** 语音输入结果回填到输入框。 */
    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val texts = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spoken = texts?.firstOrNull()
        if (spoken.isNullOrBlank()) {
            toast(getString(R.string.voice_empty))
            return@registerForActivityResult
        }
        val et = binding.etTagInput
        val existing = et.text?.toString().orEmpty()
        et.setText(if (existing.isBlank()) spoken else "$existing，$spoken")
        et.setSelection(et.text?.length ?: 0)
    }

    /** Android 10+ 改写 MediaStore 文件的系统授权（createWriteRequest）。 */
    private val writeRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            toast(getString(R.string.embed_write_denied))
            return@registerForActivityResult
        }
        val media = current ?: return@registerForActivityResult
        val hash = currentHash ?: return@registerForActivityResult
        embedTagsToFile(media, hash)
    }

    /** Android 10 及以下：WRITE_EXTERNAL_STORAGE 运行时申请（清单已 maxSdkVersion=29）。 */
    private val legacyWriteLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toast(getString(R.string.embed_write_denied))
            return@registerForActivityResult
        }
        val media = current ?: return@registerForActivityResult
        val hash = currentHash ?: return@registerForActivityResult
        embedTagsToFile(media, hash)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaList = MediaBus.current
        if (mediaList.isEmpty()) {
            finish()
            return
        }

        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, mediaList.lastIndex)
        pagerAdapter = MediaPagerAdapter(this, mediaList)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.setCurrentItem(startIndex, false)
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onCurrentChanged(mediaList[position])
            }
        })

        binding.btnOverflow.setOnClickListener { showOverflowMenu(it) }
        binding.btnSelectTags.setOnClickListener { showTagPickerDialog() }
        binding.btnManualTag.setOnClickListener { toggleManualPanel() }
        binding.btnAutoTag.setOnClickListener { onAutoTagClicked() }
        binding.btnVoice.setOnClickListener { startVoiceInput() }
        binding.btnConfirmTag.setOnClickListener { confirmManualTags() }
        binding.etTagInput.imeOptions = EditorInfo.IME_ACTION_DONE
        binding.etTagInput.setRawInputType(InputType.TYPE_CLASS_TEXT)

        observeTagStore()
        observeAutoTagState()
        onCurrentChanged(mediaList[startIndex])
    }

    // ---- 当前媒体与哈希 ----

    private fun onCurrentChanged(media: MediaItem) {
        current = media
        currentHash = null
        clearChips()
        collapseManualPanel()
        hashJob?.cancel()
        hashJob = lifecycleScope.launch {
            // openInputStream 取流失败有两类表现，都必须降级处理、绝不能崩：
            //  1) 文件被移走/删除/权限变更 → IOException / FileNotFoundException；
            //  2) Android 12 MediaProvider 位置脱敏(redaction)失败 → 平台把它包成
            //     IllegalStateException("java.io.IOException: Failed to redact ...")。
            // 2026-10-14 真机 K30 Pro 实测：原先只 catch(IOException) 拦不住第 2 类，
            // 直接 FATAL 闪退。故统一 catch(Exception)，但取消信号要原样放行（翻页取消）。
            val hash = try {
                withContext(Dispatchers.IO) { hashForMedia(media) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "hash failed for ${media.uri}", e)
                if (current?.uri != media.uri) return@launch
                toast(getString(R.string.hash_failed))
                return@launch
            }
            // 页面可能已切换
            if (current?.uri != media.uri) return@launch
            currentHash = hash
            updateChips()
        }
    }

    private suspend fun hashForMedia(media: MediaItem): String =
        MediaHasher.hashOfCached(this@MediaViewerActivity, Graph.tagStore, media)

    // ---- 标签展示 ----

    private fun observeTagStore() {
        lifecycleScope.launch {
            Graph.tagStore.tagsFlow.collect { updateChips() }
        }
    }

    private fun clearChips() {
        binding.chipGroupTags.removeAllViews()
    }

    private fun updateChips() {
        val hash = currentHash ?: return
        val entries = Graph.tagStore.tagsFor(hash)
        binding.chipGroupTags.removeAllViews()
        entries.forEach { entry ->
            val chip = Chip(this).apply {
                text = entry.tag
                isCloseIconVisible = true
                isClickable = false
                val color = when (entry.source) {
                    TagEntry.SOURCE_MANUAL -> R.color.tag_source_manual
                    TagEntry.SOURCE_AUTO -> R.color.tag_source_auto
                    else -> R.color.tag_source_heuristic
                }
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@MediaViewerActivity, color)
                )
                setTextColor(ContextCompat.getColor(this@MediaViewerActivity, android.R.color.white))
                closeIcon?.setTint(ContextCompat.getColor(this@MediaViewerActivity, android.R.color.white))
                setOnCloseIconClickListener { removeTag(entry.tag) }
            }
            binding.chipGroupTags.addView(chip)
        }
    }

    private fun removeTag(tag: String) {
        val hash = currentHash ?: return
        Graph.tagStore.removeTag(hash, tag)
        updateChips()
        Snackbar.make(binding.root, getString(R.string.tag_removed, tag), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        Graph.tagStore.addTags(hash, listOf(tag), TagEntry.SOURCE_MANUAL)
                    }
                    updateChips()
                }
            }
            .show()
    }

    // ---- 手动添加标签 ----

    private fun toggleManualPanel() {
        if (panelVisible) collapseManualPanel() else expandManualPanel()
    }

    private fun expandManualPanel() {
        val panel = binding.panelManualTag
        panel.isVisible = true
        panel.post {
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(200L).start()
        }
        panelVisible = true
        binding.etTagInput.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(binding.etTagInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun collapseManualPanel() {
        val panel = binding.panelManualTag
        if (!panelVisible) return
        panel.animate()
            .translationY(panel.height.toFloat())
            .setDuration(180L)
            .withEndAction { panel.isVisible = false }
            .start()
        panelVisible = false
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(panel.windowToken, 0)
    }

    /** 语音输入图标：调起系统语音识别，结果回填输入框。 */
    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_input))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            toast(getString(R.string.voice_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    /** 确认图标：把输入框文字作为标签写入当前媒体。 */
    private fun confirmManualTags() {
        val hash = currentHash
        val media = current
        if (hash == null || media == null) {
            toast(getString(R.string.hashing))
            return
        }
        val parsed = TagParser.parse(binding.etTagInput.text?.toString().orEmpty())
        if (parsed.isEmpty()) {
            toast(getString(R.string.tag_input_hint))
            return
        }
        val existing = Graph.tagStore.tagsFor(hash).map { it.tag }
        val toAdd = TagParser.filterExisting(parsed, existing)
        binding.etTagInput.setText("")
        collapseManualPanel()
        if (toAdd.isEmpty()) {
            snack("标签已存在")
            return
        }
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                Graph.tagStore.addTags(hash, toAdd, TagEntry.SOURCE_MANUAL, media)
            }
            updateChips()
            snack(getString(R.string.tag_added, added))
        }
    }

    // ---- 从标签目录选择 ----

    /**
     * 树形标签目录弹窗：按主题展开，可多选；确认后把选中的标签作为手动标签写入当前媒体。
     * 已存在的标签在树里显示为「已添加」并禁用，避免重复勾选。
     */
    private fun showTagPickerDialog() {
        val hash = currentHash
        val media = current
        if (hash == null || media == null) {
            toast(getString(R.string.hashing))
            return
        }
        val existing = Graph.tagStore.tagsFor(hash).map { it.tag }.toSet()

        val content = layoutInflater.inflate(R.layout.dialog_tag_picker, null)
        val list = content.findViewById<RecyclerView>(R.id.rvTagTree)
        list.layoutManager = LinearLayoutManager(this)
        val adapter = TagTreeAdapter(TagCatalog.tree, existing)
        list.adapter = adapter
        // 弹窗内滚动：约占屏幕一半高度
        list.layoutParams = list.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_tags_title)
            .setView(content)
            .setNegativeButton(R.string.auto_tag_cancel, null)
            .setPositiveButton(R.string.select_tags_add, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun refreshPositive() {
                val count = adapter.selectedTags.size
                positive.isEnabled = count > 0
                positive.text = getString(R.string.select_tags_add_count, count)
            }
            adapter.onSelectionChanged = { refreshPositive() }
            refreshPositive()

            positive.setOnClickListener {
                val chosen = adapter.selectedTags
                val toAdd = TagParser.filterExisting(chosen, existing.toList())
                if (toAdd.isEmpty()) {
                    toast(getString(R.string.tags_all_exist))
                    return@setOnClickListener
                }
                dialog.dismiss()
                lifecycleScope.launch {
                    val added = withContext(Dispatchers.IO) {
                        Graph.tagStore.addTags(hash, toAdd, TagEntry.SOURCE_MANUAL, media)
                    }
                    updateChips()
                    snack(getString(R.string.tag_added, added))
                }
            }
        }
        dialog.show()
    }

    // ---- 自动添加标签 ----

    private fun onAutoTagClicked() {
        val media = current ?: return
        val modelPath = Graph.modelManager.activeModelPath
        if (modelPath == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auto_tag_no_model_title)
                .setMessage(R.string.auto_tag_no_model_msg)
                .setPositiveButton(R.string.auto_tag_install) { _, _ ->
                    startActivity(Intent(this, ModelManageActivity::class.java))
                }
                .setNegativeButton(R.string.auto_tag_heuristic) { _, _ ->
                    Log.d(TAG, "no-model dialog: heuristic clicked, media=" + media.displayName)
                    viewModel.runAutoTag(media, useQwen = false, modelPath = null)
                }
                .setNeutralButton(R.string.auto_tag_cancel, null)
                .show()
        } else {
            viewModel.runAutoTag(media, useQwen = true, modelPath = modelPath)
        }
    }

    private fun observeAutoTagState() {
        lifecycleScope.launch {
            viewModel.autoTagState.collect { state ->
                Log.d(TAG, "autoTagState collected: " + state.javaClass.simpleName)
                when (state) {
                    is ViewerViewModel.AutoTagState.Idle ->
                        binding.progressOverlay.isVisible = false

                    is ViewerViewModel.AutoTagState.Running -> {
                        binding.progressOverlay.isVisible = true
                        binding.tvProgress.text = state.message
                    }

                    is ViewerViewModel.AutoTagState.Suggestions -> {
                        binding.progressOverlay.isVisible = false
                        showSuggestionsDialog(state.tags, state.engineId)
                    }

                    is ViewerViewModel.AutoTagState.Error -> {
                        binding.progressOverlay.isVisible = false
                        toast(getString(R.string.auto_tag_failed, state.message))
                    }
                }
            }
        }
    }

    private fun showSuggestionsDialog(tags: List<String>, engineId: String) {
        Log.d(TAG, "showSuggestionsDialog: ${tags.size} tags, engine=$engineId")
        if (tags.isEmpty()) {
            toast(getString(R.string.suggest_empty))
            viewModel.resetAutoTag()
            return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_auto_tag_suggestions, null)
        val chipGroup =
            dialogView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupSuggestions)
        val sourceText = dialogView.findViewById<android.widget.TextView>(R.id.tvSuggestSource)
        sourceText.text = getString(
            if (engineId == AutoTagEngine.ID_QWEN) R.string.suggest_from_qwen
            else R.string.suggest_from_heuristic
        )
        tags.forEach { tag ->
            chipGroup.addView(
                Chip(this).apply {
                    text = tag
                    isCheckable = true
                    isChecked = true
                }
            )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setNegativeButton(R.string.auto_tag_cancel, null)
            .setPositiveButton(R.string.suggest_add_selected, null)
            .create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun refreshButtonText() {
                val selected = (0 until chipGroup.childCount)
                    .mapNotNull { chipGroup.getChildAt(it) as? Chip }
                    .count { it.isChecked }
                positive.text = getString(R.string.suggest_add_selected, selected)
            }
            positive.setOnClickListener {
                val selected = (0 until chipGroup.childCount)
                    .mapNotNull { chipGroup.getChildAt(it) as? Chip }
                    .filter { it.isChecked }
                    .map { it.text.toString() }
                if (selected.isNotEmpty()) addAutoTags(selected, engineId)
                dialog.dismiss()
            }
            for (i in 0 until chipGroup.childCount) {
                (chipGroup.getChildAt(i) as? Chip)?.setOnCheckedChangeListener { _, _ ->
                    refreshButtonText()
                }
            }
            refreshButtonText()
        }
        dialog.setOnDismissListener { viewModel.resetAutoTag() }
        dialog.show()
    }

    private fun addAutoTags(tags: List<String>, engineId: String) {
        val hash = currentHash ?: return
        val media = current ?: return
        val source = if (engineId == AutoTagEngine.ID_QWEN) TagEntry.SOURCE_AUTO
        else TagEntry.SOURCE_HEURISTIC
        lifecycleScope.launch {
            val existing = Graph.tagStore.tagsFor(hash).map { it.tag }
            val toAdd = TagParser.filterExisting(tags, existing)
            if (toAdd.isEmpty()) {
                snack("标签已存在")
                return@launch
            }
            val added = withContext(Dispatchers.IO) {
                Graph.tagStore.addTags(hash, toAdd, source, media)
            }
            updateChips()
            snack(getString(R.string.tag_added, added))
        }
    }

    // ---- 溢出菜单 ----

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_viewer, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_model_manage -> {
                    startActivity(Intent(this, ModelManageActivity::class.java))
                    true
                }

                R.id.action_file_info -> {
                    showFileInfoDialog()
                    true
                }

                R.id.action_embed_tags -> {
                    onEmbedTagsClicked()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    // ---- 把标签写进照片本体（XMP） ----

    private fun onEmbedTagsClicked() {
        val media = current ?: return
        val hash = currentHash
        if (hash == null) {
            toast(getString(R.string.hashing))
            return
        }
        if (media.isVideo || !media.mimeType.startsWith("image/jpeg")) {
            toast(getString(R.string.embed_only_jpeg))
            return
        }
        if (Graph.tagStore.tagsFor(hash).isEmpty()) {
            toast(getString(R.string.embed_no_tags))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 改写非本应用创建的媒体文件，必须走系统授权对话框
            val request = MediaStore.createWriteRequest(contentResolver, listOf(media.uri))
            writeRequestLauncher.launch(IntentSenderRequest.Builder(request).build())
        } else {
            legacyWriteLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun embedTagsToFile(media: MediaItem, oldHash: String) {
        val tags = Graph.tagStore.tagsFor(oldHash).map { it.tag }
        if (tags.isEmpty()) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.embed_running)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    TagEmbedder.embed(this@MediaViewerActivity, media.uri, tags)
                }
                // 文件字节已变 -> 哈希变 -> 标签行必须搬家，否则 chips 消失
                Graph.tagStore.migrateHash(oldHash, result.newHash, result.newSize)
                Graph.tagStore.invalidateHashCache(media.uri.toString())
                // 同步内存态：当前媒体、MediaBus（网格/再次进入查看器都读它）
                val fresh = media.copy(sizeBytes = result.newSize)
                current = fresh
                currentHash = result.newHash
                MediaBus.current = MediaBus.current.map { if (it.uri == media.uri) fresh else it }
                updateChips()
                snack(getString(R.string.embed_done, result.written.size))
            } catch (e: Exception) {
                Log.e(TAG, "embed tags to file failed", e)
                toast(getString(R.string.embed_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                dialog.dismiss()
            }
        }
    }

    private fun showFileInfoDialog() {
        val media = current ?: return
        val hash = currentHash ?: "…"
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(media.dateModifiedSec * 1000))
        val message = buildString {
            append(getString(R.string.info_name)).append("：").append(media.displayName).append('\n')
            append(getString(R.string.info_type)).append("：").append(media.mimeType).append('\n')
            append(getString(R.string.info_size)).append("：")
                .append(MediaQuery.formatSize(media.sizeBytes)).append('\n')
            append(getString(R.string.info_modified)).append("：").append(date).append('\n')
            append(getString(R.string.info_uri)).append("：").append(media.uri).append('\n')
            append(getString(R.string.info_hash)).append("：\n").append(hash)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.file_info)
            .setMessage(message)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    // ---- 小工具 ----

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun snack(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}
