package com.albummarker

import com.albummarker.data.MediaItem

/** 主界面与查看器之间传递媒体列表的简易载体（进程内单例）。 */
object MediaBus {
    @Volatile
    var current: List<MediaItem> = emptyList()
}
