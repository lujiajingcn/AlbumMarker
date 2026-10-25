package com.albummarker.data

/**
 * 首页网格里的一项：媒体本身 + 该媒体当前要展示的标签。
 *
 * 标签与媒体拆开，是因为标签是「按需显示」的（首页「更多」菜单里开关），
 * 而 [MediaItem] 同时还要经 Parcelable 传给查看器，不适合挂上易变的展示态。
 */
data class MediaGridItem(
    val item: MediaItem,
    val tags: List<TagEntry> = emptyList(),
)
