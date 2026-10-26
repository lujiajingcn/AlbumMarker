package com.albummarker.data.model

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.albummarker.data.MediaBitmaps
import com.albummarker.data.MediaHasher
import com.albummarker.data.MediaItem
import com.albummarker.data.TagEntry
import com.albummarker.data.TagParser
import com.albummarker.data.TagStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 批量自动打标。
 *
 * 与查看器的单张流程不同：查看器是「弹出建议 -> 用户勾选 -> 写入」，
 * 批量场景下不可能弹 N 次对话框，因此改为：
 *  - 逐张跑引擎，标签**直接写入**（不再逐张确认）；
 *  - 引擎实例在整个批次内复用（Qwen 加载模型很贵，绝不能每张都重新加载）。
 */
object BatchAutoTagger {

    private const val TAG = "AlbumMarker"

    /** 批次结果。 */
    data class Result(
        val succeeded: Int,
        val failed: Int,
        /** 实际写入的标签条数。 */
        val added: Int,
    )

    /**
     * 对一组媒体逐张跑自动打标并直接写入。
     *
     * @param engine 已准备好的引擎（调用方负责复用与释放）
     * @param onProgress 已处理数量回调。引擎内部会切到后台线程，
     *   因此这里统一切回主线程再触发（调用方要更新对话框）。
     */
    suspend fun tagAll(
        context: Context,
        store: TagStore,
        engine: AutoTagEngine,
        media: List<MediaItem>,
        source: String = TagEntry.SOURCE_AUTO,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result {
        var ok = 0
        var fail = 0
        var added = 0
        media.forEachIndexed { index, item ->
            withContext(Dispatchers.Main) { onProgress(index + 1, media.size) }
            var bitmaps: List<Bitmap> = emptyList()
            try {
                val input = buildInput(context, item)
                bitmaps = input.bitmaps
                if (input.bitmaps.isEmpty()) {
                    throw IllegalStateException("无法从该媒体提取画面帧")
                }
                val tags = engine.suggestTags(input)
                if (tags.isNotEmpty()) {
                    // 标签以文件哈希为键，写入前要先算出哈希
                    val hash = MediaHasher.hashOfCached(context, store, item)
                    val existing = store.tagsFor(hash).map { it.tag }
                    val toAdd = TagParser.filterExisting(tags, existing)
                    if (toAdd.isNotEmpty()) {
                        // 必须传 media：否则 media_tags 行缺失，
                        // 这张图在网格标签浮层与标签搜索里都查不到
                        added += store.addTags(hash, toAdd, source, item)
                    }
                }
                ok++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                // 单张失败不影响其余：文件可能已被移动/删除；OOM 也只计失败，不拖垮整批。
                Log.e(TAG, "batch autoTag failed: ${item.displayName}", t)
                fail++
            } finally {
                bitmaps.forEach { runCatching { it.recycle() } }
            }
        }
        return Result(ok, fail, added)
    }

    /** 构造单张媒体的打标输入（图片取一帧，视频取关键帧 + 时长信息）。 */
    private suspend fun buildInput(context: Context, media: MediaItem): TagInput =
        if (media.isVideo) {
            TagInput(
                bitmaps = MediaBitmaps.framesForVideo(context, media),
                exif = null,
                video = MediaBitmaps.videoFacts(context, media),
            )
        } else {
            TagInput(
                bitmaps = listOfNotNull(MediaBitmaps.bitmapForImage(context, media.uri)),
                exif = MediaBitmaps.exifFacts(context, media.uri),
                video = null,
            )
        }
}
