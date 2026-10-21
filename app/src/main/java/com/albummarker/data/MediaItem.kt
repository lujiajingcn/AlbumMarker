package com.albummarker.data

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** 一条媒体记录（图片或视频），来自 MediaStore 合并查询。 */
@Parcelize
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long,
    val durationMs: Long,
) : Parcelable {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}
