package com.albummarker.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
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
 *
 * 多选：选中态由 [MediaGridItem.selected] 携带，DiffUtil 自动刷新对应项；
 * [onLongClick] 用于进入多选模式，点击是否切换选中由宿主判断。
 */
class MediaGridAdapter(
    private val onClick: (MediaItem, Int) -> Unit,
    private val onLongClick: (MediaItem, Int) -> Unit = { _, _ -> },
) : ListAdapter<MediaGridItem, MediaGridAdapter.VH>(DIFF) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    inner class VH(private val binding: ItemMediaGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaGridItem) {
            val media = item.item
            binding.videoBadge.isVisible = media.isVideo
            binding.tvDuration.text = MediaQuery.formatDuration(media.durationMs)
            binding.ivThumb.setImageDrawable(null)
            // 复用校验用 uri 而不是 id：图片表与视频表的 _ID 各自独立编号，会撞号
            // （如 图片 /images/media/5 与 视频 /video/media/5），撞号会让视频
            // 缩略图贴到图片的位置上。
            binding.ivThumb.tag = media.uri.toString()
            if (media.isVideo) {
                scope.launch {
                    val bmp = MediaBitmaps.thumbnail(binding.root.context, media)
                    // View 可能已被复用：仅当 tag 仍对得上时才落图
                    if (binding.ivThumb.tag == media.uri.toString() && bmp != null) {
                        binding.ivThumb.setImageBitmap(bmp)
                    } else {
                        // 已经不属于这个 item 的缩略图要立即释放，避免快速滚动时堆积
                        bmp?.recycle()
                    }
                }
            } else {
                binding.ivThumb.load(media.uri) {
                    crossfade(true)
                    size(360)
                }
            }
            bindTags(item.tags)
            binding.ivSelection.isVisible = item.selected
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(media, position)
            }
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onLongClick(media, position)
                }
                true
            }
        }

        /** 渲染标签区；所有标签都展示，ChipGroup 负责自动换行。 */
        private fun bindTags(tags: List<TagEntry>) {
            binding.tagRow.removeAllViews()
            if (tags.isEmpty()) {
                binding.tagRow.isVisible = false
                return
            }
            binding.tagRow.isVisible = true
            val context = binding.root.context
            GridTagDisplay.plan(tags).forEach { chip ->
                val color = colorOf(context, chip.source)
                binding.tagRow.addView(newChip(context, chip.text, color))
            }
        }

        private fun newChip(context: Context, text: String, color: Int): TextView =
            TextView(context).apply {
                this.text = text
                setTextColor(Color.WHITE)
                // 20sp 的 75% = 15sp
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(6f).toInt(), dp(1f).toInt(), dp(6f).toInt(), dp(1f).toInt())
                // 不省略、不限制单行：长标签自动换行，完整展示内容
                isSingleLine = false
                background = ContextCompat.getDrawable(context, R.drawable.bg_grid_tag_chip)
                    ?.mutate()
                backgroundTintList = ColorStateList.valueOf(color)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(2f).toInt() }
            }

        private fun dp(value: Float): Float =
            value * binding.root.resources.displayMetrics.density

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
