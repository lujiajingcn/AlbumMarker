package com.albummarker.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.albummarker.R
import com.albummarker.data.GridTagDisplay
import com.albummarker.data.MediaBitmaps
import com.albummarker.data.MediaGridItem
import com.albummarker.data.MediaItem
import com.albummarker.data.MediaQuery
import com.albummarker.data.TagEntry
import com.albummarker.databinding.ItemMediaGridBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 主界面媒体网格适配器：图片走 Coil，视频缩略图走 MediaStore。
 *
 * 标签浮层：网格项带 [MediaGridItem.tags]，为空时整行隐藏；
 * 排布规则见 [GridTagDisplay]。
 */
class MediaGridAdapter(
    private val onClick: (MediaItem, Int) -> Unit,
) : ListAdapter<MediaGridItem, MediaGridAdapter.VH>(DIFF) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    inner class VH(private val binding: ItemMediaGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaGridItem) {
            val media = item.item
            binding.videoBadge.isVisible = media.isVideo
            binding.tvDuration.text = MediaQuery.formatDuration(media.durationMs)
            binding.ivThumb.setImageDrawable(null)
            binding.ivThumb.tag = media.id
            if (media.isVideo) {
                scope.launch {
                    val bmp = MediaBitmaps.thumbnail(binding.root.context, media)
                    // View 可能已被复用：仅当 tag 仍对得上时才落图
                    if (binding.ivThumb.tag == media.id && bmp != null) {
                        binding.ivThumb.setImageBitmap(bmp)
                    }
                }
            } else {
                binding.ivThumb.load(media.uri) {
                    crossfade(true)
                    size(360)
                }
            }
            bindTags(item.tags)
            binding.root.setOnClickListener { onClick(media, bindingAdapterPosition) }
        }

        /** 渲染标签浮层；视图复用时先清干净，避免上一个 item 的残留。 */
        private fun bindTags(tags: List<TagEntry>) {
            binding.tagRow.removeAllViews()
            if (tags.isEmpty()) {
                binding.tagRow.isVisible = false
                return
            }
            binding.tagRow.isVisible = true
            val context = binding.root.context
            val inflater = LayoutInflater.from(context)
            GridTagDisplay.plan(tags) { n ->
                context.getString(R.string.grid_tag_more, n)
            }.forEach { chip ->
                val color = if (chip.isOverflow) {
                    ContextCompat.getColor(context, R.color.tag_source_more)
                } else {
                    colorOf(context, chip.source!!)
                }
                binding.tagRow.addView(newChip(inflater, context, chip.text, color))
            }
        }

        private fun newChip(
            inflater: LayoutInflater,
            context: Context,
            text: String,
            color: Int,
        ): TextView {
            val chip = inflater.inflate(
                R.layout.item_grid_tag_chip, binding.tagRow, false
            ) as TextView
            chip.text = text
            chip.setTextColor(Color.WHITE)
            chip.backgroundTintList = ColorStateList.valueOf(color)
            return chip
        }

        private fun colorOf(context: Context, source: String): Int =
            ContextCompat.getColor(
                context,
                when (source) {
                    TagEntry.SOURCE_MANUAL -> R.color.tag_source_manual
                    TagEntry.SOURCE_AUTO -> R.color.tag_source_auto
                    else -> R.color.tag_source_heuristic
                }
            )
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
        private val DIFF = object : DiffUtil.ItemCallback<MediaGridItem>() {
            override fun areItemsTheSame(oldItem: MediaGridItem, newItem: MediaGridItem) =
                oldItem.item.id == newItem.item.id && oldItem.item.uri == newItem.item.uri

            override fun areContentsTheSame(oldItem: MediaGridItem, newItem: MediaGridItem) =
                oldItem == newItem
        }
    }
}
