package com.albummarker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import coil.load
import com.albummarker.data.MediaBitmaps
import com.albummarker.data.MediaItem
import com.albummarker.data.MediaQuery
import com.albummarker.databinding.ItemMediaGridBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 主界面媒体网格适配器：图片走 Coil，视频缩略图走 MediaStore。 */
class MediaGridAdapter(
    private val onClick: (MediaItem, Int) -> Unit,
) : ListAdapter<MediaItem, MediaGridAdapter.VH>(DIFF) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    inner class VH(private val binding: ItemMediaGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem) {
            binding.videoBadge.isVisible = item.isVideo
            binding.tvDuration.text = MediaQuery.formatDuration(item.durationMs)
            binding.ivThumb.setImageDrawable(null)
            binding.ivThumb.tag = item.id
            if (item.isVideo) {
                scope.launch {
                    val bmp = MediaBitmaps.thumbnail(binding.root.context, item)
                    // View 可能已被复用：仅当 tag 仍对得上时才落图
                    if (binding.ivThumb.tag == item.id && bmp != null) {
                        binding.ivThumb.setImageBitmap(bmp)
                    }
                }
            } else {
                binding.ivThumb.load(item.uri) {
                    crossfade(true)
                    size(360)
                }
            }
            binding.root.setOnClickListener { onClick(item, bindingAdapterPosition) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMediaGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope.cancel()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem) =
                oldItem.id == newItem.id && oldItem.uri == newItem.uri

            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem) =
                oldItem == newItem
        }
    }
}
