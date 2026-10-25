package com.albummarker.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首页多选的批量打标。
 *
 * 关键点：标签在库里以文件 SHA-256 为主键，而网格里的媒体只有 Uri，
 * 因此批量写标签必须先算出每个媒体的哈希。
 * 哈希走 [MediaHasher.hashOfCached]（有 hash_cache 表缓存），代价可接受。
 */
object BatchTagger {

    private const val TAG = "AlbumMarker"

    /** 批量操作的结果汇总。 */
    data class Result(
        val succeeded: Int,
        val failed: Int,
        /** 实际新增的标签条数。 */
        val added: Int,
    )

    /**
     * 给一组媒体批量添加标签。
     *
     * @param media 待打标的媒体（已按选中顺序）
     * @param tags 要添加的标签（已去掉所有选中项都有的）
     * @param onProgress 已处理数量回调，用于更新进度条。
     *   本方法整体在 IO 线程执行，因此回调被切回主线程再触发
     *   （调用方通常在回调里更新对话框，直接调用会抛线程异常）。
     */
    suspend fun addTags(
        context: Context,
        store: TagStore,
        media: List<MediaItem>,
        tags: List<String>,
        source: String = TagEntry.SOURCE_MANUAL,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        var ok = 0
        var fail = 0
        var added = 0
        media.forEachIndexed { index, item ->
            withContext(Dispatchers.Main) { onProgress(index + 1, media.size) }
            try {
                val hash = MediaHasher.hashOfCached(context, store, item)
                val existing = store.tagsFor(hash).map { it.tag }
                val toAdd = TagParser.filterExisting(tags, existing)
                if (toAdd.isNotEmpty()) {
                    // 必须传 media：否则 media_tags 行缺失，
                    // 这张图在网格标签浮层与标签搜索里都查不到
                    added += store.addTags(hash, toAdd, source, item)
                }
                ok++
            } catch (e: Exception) {
                // 单个失败不影响其余：文件可能已被移动/删除
                Log.e(TAG, "batch addTags failed: ${item.displayName}", e)
                fail++
            }
        }
        Result(ok, fail, added)
    }

    /**
     * 批量操作前的准备：算出每个媒体的哈希，并取出它们各自的现有标签。
     *
     * 用于「选择标签」弹窗——只有当某个标签是**所有**选中项都已有时，
     * 才算「已添加」（禁用），否则它仍可添加给缺的那些项。
     */
    suspend fun prepare(
        context: Context,
        store: TagStore,
        media: List<MediaItem>,
    ): Prepared = withContext(Dispatchers.IO) {
        val hashByUri = mutableMapOf<String, String>()
        val tagsByUri = mutableMapOf<String, List<TagEntry>>()
        for (item in media) {
            try {
                val hash = MediaHasher.hashOfCached(context, store, item)
                hashByUri[item.uri.toString()] = hash
                tagsByUri[item.uri.toString()] = store.tagsFor(hash)
            } catch (e: Exception) {
                Log.e(TAG, "batch prepare failed: ${item.displayName}", e)
            }
        }
        Prepared(hashByUri, tagsByUri)
    }

    /** 批量打标前的中间结果。 */
    data class Prepared(
        val hashByUri: Map<String, String>,
        val tagsByUri: Map<String, List<TagEntry>>,
    ) {
        /**
         * 所有选中项都已有的标签（这些在选择器里应标为「已添加」不可勾选）。
         * 没有标签的项不参与交集计算——它没有的标签仍可添加。
         */
        fun commonTags(): Set<String> {
            val lists = tagsByUri.values.filter { it.isNotEmpty() }
            if (lists.isEmpty()) return emptySet()
            var common: Set<String> = lists.first().mapTo(mutableSetOf()) { it.tag }
            for (list in lists.drop(1)) {
                val names = list.mapTo(mutableSetOf()) { it.tag }
                common = common intersect names
                if (common.isEmpty()) return emptySet()
            }
            return common
        }
    }
}
