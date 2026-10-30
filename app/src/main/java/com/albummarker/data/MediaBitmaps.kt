package com.albummarker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 媒体位图加载：网格缩略图 + 自动打标用的降采样帧。 */
object MediaBitmaps {

    /** 视频封面内存缓存上限（字节）。360px 的帧约 0.5MB，16MB 够放三十多张。 */
    private const val VIDEO_THUMB_CACHE_BYTES = 16 * 1024 * 1024

    /**
     * 视频封面缓存。
     *
     * 抽帧比读 MediaStore 缩略图慢，网格滚动时同一条视频会被反复绑定，
     * 不缓存的话每次都要重新解码，滚动会卡。
     *
     * 注意：这里**不回收**被淘汰的 Bitmap（entryRemoved 保持默认空实现）——
     * 它可能还挂在某个 ImageView 上显示，主动 recycle 会导致
     * "Canvas: trying to use a recycled bitmap" 崩溃。同理，**拿到缓存实例的
     * 调用方也不能 recycle 它**，交给 GC 处理。
     */
    private val videoThumbCache = object : LruCache<String, Bitmap>(VIDEO_THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 网格缩略图。
     *
     * 视频**不优先**走 [android.content.ContentResolver.loadThumbnail]：
     * 实测（2026-10-30）手机自带剪辑软件导出的新视频，loadThumbnail 会返回
     * 与文件内容完全无关的陈旧封面（时长等元数据却是对的），或者直接抛异常
     * 被吞掉导致格子留空。
     *
     * 根因是它读 MediaStore 的 `thumbnails` 缓存表，该表以 `_ID` 为键；
     * 抖音下载 / 系统剪辑会不断「建了又删」文件，`_ID` 被复用后旧缩略图行
     * 可能还在，于是取到另一条媒体的封面。改为直接从视频文件抽帧，
     * 内容必然与文件一致；系统缩略图仅在抽帧失败时兜底。
     */
    suspend fun thumbnail(context: Context, item: MediaItem, targetPx: Int = 512): Bitmap? =
        withContext(Dispatchers.IO) {
            if (item.isVideo) {
                videoThumbnail(context, item, targetPx)
                    ?: systemThumbnail(context, item, targetPx)
            } else {
                systemThumbnail(context, item, targetPx)
            }
        }

    /**
     * 直接调系统缩略图。
     * API 29+ 用 contentResolver.loadThumbnail；更早版本回退到已弃用的
     * MediaStore.Thumbnails（仍可用）。
     */
    private fun systemThumbnail(context: Context, item: MediaItem, targetPx: Int): Bitmap? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(item.uri, Size(targetPx, targetPx), null)
            } else {
                @Suppress("DEPRECATION")
                if (item.isVideo) {
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver, item.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                } else {
                    MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver, item.id,
                        MediaStore.Images.Thumbnails.MINI_KIND, null
                    )
                }
            }
        }.getOrNull()

    /**
     * 直接从视频文件抽一帧当封面。
     *
     * 取第 1 秒的帧：既避开不少视频开头的纯黑画面，又与系统相册的封面习惯一致；
     * 不足 2 秒的短视频取首帧。
     */
    private fun videoThumbnail(context: Context, item: MediaItem, targetPx: Int): Bitmap? {
        // key 带上修改时间：同一 Uri 的文件被重新导入/覆盖后不会命中旧封面
        val key = "${item.uri}@${item.dateModifiedSec}@$targetPx"
        videoThumbCache.get(key)?.let { return it }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, item.uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: item.durationMs
            val positionUs = if (durationMs > 2_000) 1_000_000L else 0L
            val frame = retriever.getFrameAtTime(
                positionUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { scaleDown(it, targetPx) }?.also { videoThumbCache.put(key, it) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 完整解码图片并缩放到 maxDim 以内，同时按 EXIF 方向旋转。 */
    suspend fun bitmapForImage(
        context: Context,
        uri: Uri,
        maxDim: Int = 512,
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            // 注意：不能写成 `openInputStream(uri)?.use { decodeStream(...) } ?: return null`
            // —— use 返回的是 lambda 的结果，而 inJustDecodeBounds 模式下 decodeStream
            // 恒返回 null，会被 Elvis 误判成「流打开失败」（实测启发式链路全挂在这）。
            // 必须先把「流是否为 null」和「解码结果」分开判断。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = MediaStreams.open(context, uri)
                ?: return@runCatching null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            }
            val decodeStream2 = MediaStreams.open(context, uri)
                ?: return@runCatching null
            val decoded = decodeStream2.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@runCatching null

            val rotation = MediaStreams.open(context, uri)?.use {
                runCatching { ExifInterface(it).rotationDegrees }.getOrDefault(0)
            } ?: 0
            if (rotation == 0) decoded else rotateBitmap(decoded, rotation.toFloat())
        }.getOrNull()
    }

    /** 视频按位置抽取关键帧（默认 1 帧；Qwen/启发式目前都只消费首帧）。 */
    suspend fun framesForVideo(
        context: Context,
        item: MediaItem,
        maxDim: Int = 512,
        count: Int = 1,
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, item.uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: item.durationMs.takeIf { it > 0 } ?: 0L
            val positionsUs: List<Long> = if (durationMs > 0) {
                val fractions = when (count) {
                    1 -> listOf(0.5)
                    2 -> listOf(0.25, 0.75)
                    else -> listOf(0.15, 0.5, 0.85)
                }
                fractions.map { (durationMs * it).toLong() * 1000 }
            } else {
                listOf(0L)
            }
            positionsUs.mapNotNull { us ->
                runCatching {
                    retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { scaleDown(it, maxDim) }
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 读取 EXIF 事实信息（启发式引擎用）。 */
    suspend fun exifFacts(context: Context, uri: Uri): ExifFacts? =
        withContext(Dispatchers.IO) {
            runCatching {
                MediaStreams.open(context, uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val latLong = exif.latLong
                    ExifFacts(
                        dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                            ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
                        make = exif.getAttribute(ExifInterface.TAG_MAKE),
                        model = exif.getAttribute(ExifInterface.TAG_MODEL),
                        hasGps = latLong != null,
                        orientation = exif.rotationDegrees,
                    )
                }
            }.getOrNull()
        }

    /** 读取视频元信息（时长 / 分辨率）。 */
    suspend fun videoFacts(context: Context, item: MediaItem): VideoFacts? =
        withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, item.uri)
                    VideoFacts(
                        durationMs = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: item.durationMs,
                        width = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toIntOrNull() ?: 0,
                        height = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toIntOrNull() ?: 0,
                    )
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()
        }

    fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        // 按最长边降采样；旧逻辑要求宽高同时减半，超宽全景图会按原宽解码。
        while (maxOf(width / sample, height / sample) > maxDim) {
            sample *= 2
        }
        return sample
    }

    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated != src) src.recycle()
        return rotated
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val max = maxOf(w, h)
        if (max <= maxDim) return src
        val scale = maxDim.toFloat() / max
        val scaled = Bitmap.createScaledBitmap(
            src,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== src) src.recycle()
        return scaled
    }
}

data class ExifFacts(
    val dateTime: String?,
    val make: String?,
    val model: String?,
    val hasGps: Boolean,
    val orientation: Int,
)

data class VideoFacts(
    val durationMs: Long,
    val width: Int,
    val height: Int,
)
