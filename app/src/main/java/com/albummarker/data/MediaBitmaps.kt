package com.albummarker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 媒体位图加载：网格缩略图 + 自动打标用的降采样帧。 */
object MediaBitmaps {

    /**
     * 网格缩略图。API 29+ 用 contentResolver.loadThumbnail；
     * 更早版本回退到已弃用的 MediaStore.Thumbnails（仍可用）。
     */
    suspend fun thumbnail(context: Context, item: MediaItem, targetPx: Int = 512): Bitmap? =
        withContext(Dispatchers.IO) {
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

    /** 视频按位置抽取关键帧（默认 15% / 50% / 85% 三帧）。 */
    suspend fun framesForVideo(
        context: Context,
        item: MediaItem,
        maxDim: Int = 512,
        count: Int = 3,
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
        var w = width
        var h = height
        while (w / 2 >= maxDim && h / 2 >= maxDim) {
            w /= 2
            h /= 2
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
        return Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
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
