package com.albummarker.ui

import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.albummarker.data.MediaItem

/** 全屏查看器分页适配器：每个媒体一个 MediaPageFragment。 */
class MediaPagerAdapter(
    fragmentActivity: FragmentActivity,
    private val items: List<MediaItem>,
) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = items.size

    override fun createFragment(position: Int): androidx.fragment.app.Fragment =
        MediaPageFragment.newInstance(items[position])
}
