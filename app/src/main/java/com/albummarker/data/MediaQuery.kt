package com.albummarker.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 需求 2：合并查询系统相册中的图片与视频。 */
object MediaQuery {

    private val PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
    )

    private val VIDEO_PROJECTION = PROJECTION + MediaStore.Video.VideoColumns.DURATION

    suspend fun load(context: Context, limit: Int = 5000): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<MediaItem>()
            items += query(
                context = context,
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection = PROJECTION,
                hasDuration = false,
                limit = limit,
            )
            items += query(
                context = context,
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection = VIDEO_PROJECTION,
                hasDuration = true,
                limit = limit,
            )
            // 0 字节的媒体无法解码（系统中可能残留 _size=0 的记录，例如 sc.png），
            // 显示出来只有黑屏；系统相册同样不展示它们。先按大小过滤再截断。
            items.removeAll { it.sizeBytes <= 0 }
            items.sortByDescending { it.dateModifiedSec }
            if (items.size > limit) items.subList(limit, items.size).clear()
            items
        }

    private fun query(
        context: Context,
        collection: Uri,
        projection: Array<String>,
        hasDuration: Boolean,
        limit: Int,
    ): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        // 注意：不能把 "LIMIT n" 拼进 sortOrder —— 新版 MediaProvider 会校验排序表达式，
        // 直接抛 IllegalArgumentException("Invalid token LIMIT") 使应用启动即崩溃
        // （Android 17 实测）。API 30+ 用 QUERY_ARG_LIMIT；旧版只排序列，由 load() 截断。
        val sortColumn = MediaStore.MediaColumns.DATE_MODIFIED
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val queryArgs = Bundle().apply {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(sortColumn)
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            context.contentResolver.query(collection, projection, queryArgs, null)
        } else {
            context.contentResolver.query(collection, projection, null, null, "$sortColumn DESC")
        }
        cursor?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iDuration =
                if (hasDuration) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                result += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = c.getString(iName) ?: "unknown",
                    mimeType = c.getString(iMime) ?: "",
                    sizeBytes = if (c.isNull(iSize)) 0L else c.getLong(iSize),
                    dateModifiedSec = if (c.isNull(iDate)) 0L else c.getLong(iDate),
                    durationMs = if (iDuration >= 0 && !c.isNull(iDuration)) c.getLong(iDuration) else 0L,
                )
            }
        }
        return result
    }

    /** 打开单个媒体（VIEW intent 传入的 Uri）时构造 MediaItem。 */
    fun fromSingleUri(context: Context, uri: Uri): MediaItem? {
        val isVideo = context.contentResolver.getType(uri)?.startsWith("video/") == true
        val projection = if (isVideo) VIDEO_PROJECTION else PROJECTION
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            fun text(col: String): String? =
                c.getColumnIndex(col).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getString(it) }
            fun long(col: String): Long =
                c.getColumnIndex(col).takeIf { it >= 0 }?.let { if (c.isNull(it)) 0L else c.getLong(it) } ?: 0L
            val id = c.getColumnIndex(MediaStore.MediaColumns._ID)
                .takeIf { it >= 0 }?.let { c.getLong(it) }
                ?: uri.lastPathSegment?.toLongOrNull()
                ?: 0L
            return MediaItem(
                id = id,
                uri = uri,
                displayName = text(MediaStore.MediaColumns.DISPLAY_NAME)
                    ?: uri.lastPathSegment ?: "unknown",
                mimeType = text(MediaStore.MediaColumns.MIME_TYPE)
                    ?: (if (isVideo) "video/*" else "image/*"),
                sizeBytes = long(MediaStore.MediaColumns.SIZE),
                dateModifiedSec = long(MediaStore.MediaColumns.DATE_MODIFIED),
                durationMs = if (isVideo) long(MediaStore.Video.VideoColumns.DURATION) else 0L,
            )
        }
        return null
    }

    /** 毫秒时长格式化为 0:05 / 1:23。 */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /** 字节数格式化为可读字符串。 */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
