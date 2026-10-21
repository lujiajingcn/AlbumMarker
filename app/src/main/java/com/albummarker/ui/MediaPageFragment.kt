package com.albummarker.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.SeekBar
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import coil.load
import com.albummarker.R
import com.albummarker.data.MediaItem
import com.albummarker.data.MediaQuery
import com.albummarker.data.MediaStreams
import com.albummarker.databinding.FragmentMediaPageBinding
import kotlin.math.abs

/**
 * 单个媒体的全屏页：图片用 ZoomableImageView（双击/捏合缩放），
 * 视频用 VideoView + 自定义播放控件（播放/暂停 + 进度条）。
 */
class MediaPageFragment : Fragment(R.layout.fragment_media_page) {

    companion object {
        private const val ARG_MEDIA = "arg_media"

        fun newInstance(item: MediaItem): MediaPageFragment =
            MediaPageFragment().apply {
                arguments = Bundle().apply { putParcelable(ARG_MEDIA, item) }
            }
    }

    private val media: MediaItem by lazy {
        requireArguments().getParcelable<MediaItem>(ARG_MEDIA)!!
    }

    private var binding: FragmentMediaPageBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var wasPlaying = false
    private var swipeVelocity: VelocityTracker? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentMediaPageBinding.bind(view)
        binding = b
        b.pbPageLoading.isVisible = media.isVideo
        if (media.isVideo) setupVideo(b) else setupImage(b)
    }

    private fun setupImage(b: FragmentMediaPageBinding) {
        b.ivPhoto.isVisible = true
        b.ivPhoto.load(media.uri) {
            crossfade(true)
            listener(
                onError = { _, _ ->
                    // Android 12 MediaProvider redaction 失败时按 Uri 取不到字节 → 黑屏。
                    // 回退：解析真实文件路径用 Coil.load(File) 直读（仅 SDK≤32 可行）。
                    val file = MediaStreams.file(requireContext(), media.uri)
                    if (file != null) {
                        b.ivPhoto.load(file) {
                            crossfade(true)
                            listener(onError = { _, _ -> b.pbPageLoading.isVisible = false })
                        }
                    } else {
                        b.pbPageLoading.isVisible = false
                    }
                }
            )
        }
        b.pbPageLoading.isVisible = false
        setupSwipeDownToDismiss(b)
    }

    /**
     * 图片页下拉返回：未放大时向下拖动跟手，超过约 12% 屏高（或快速下甩）松手关闭查看器；
     * 放大状态下仍由 ZoomableImageView 负责拖动画布，不触发返回。
     */
    private fun setupSwipeDownToDismiss(b: FragmentMediaPageBinding) {
        val photo = b.ivPhoto
        var downX = 0f
        var downY = 0f
        var tracking = false
        var active = false
        val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop

        photo.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    tracking = !photo.isZoomed
                    active = false
                    swipeVelocity?.recycle()
                    swipeVelocity = VelocityTracker.obtain().apply { addMovement(event) }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 双指缩放优先，取消下拉判定
                    tracking = false
                    active = false
                    view.animate().cancel()
                    view.translationY = 0f
                    view.alpha = 1f
                }

                MotionEvent.ACTION_MOVE -> {
                    swipeVelocity?.addMovement(event)
                    if (!tracking) return@setOnTouchListener false
                    val dy = event.rawY - downY
                    val dx = event.rawX - downX
                    if (!active && dy > slop && abs(dy) > abs(dx)) active = true
                    if (active) {
                        val offset = dy.coerceAtLeast(0f)
                        view.translationY = offset
                        view.alpha = (1f - offset / (view.height * 1.4f)).coerceIn(0.25f, 1f)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    swipeVelocity?.addMovement(event)
                    swipeVelocity?.computeCurrentVelocity(1000)
                    val velocityY = swipeVelocity?.yVelocity ?: 0f
                    swipeVelocity?.recycle()
                    swipeVelocity = null
                    if (active) {
                        val dragged = view.translationY
                        if (dragged > view.height * 0.12f || velocityY > 1200f) {
                            view.animate()
                                .translationY(view.height.toFloat())
                                .alpha(0f)
                                .setDuration(180L)
                                .withEndAction { activity?.finish() }
                                .start()
                        } else {
                            view.animate().translationY(0f).alpha(1f).setDuration(150L).start()
                        }
                    }
                    tracking = false
                    active = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    swipeVelocity?.recycle()
                    swipeVelocity = null
                    if (active) {
                        view.animate().translationY(0f).alpha(1f).setDuration(150L).start()
                    }
                    tracking = false
                    active = false
                }
            }
            false
        }
    }

    private fun setupVideo(b: FragmentMediaPageBinding) {
        b.videoView.isVisible = true
        b.videoControls.isVisible = true
        val started = runCatching {
            b.videoView.setVideoURI(media.uri)
            true
        }.getOrDefault(false)
        if (!started) {
            b.pbPageLoading.isVisible = false
            return
        }

        b.videoView.setOnPreparedListener { mp ->
            b.pbPageLoading.isVisible = false
            b.tvTotalTime.text = MediaQuery.formatDuration(mp.duration.toLong())
            b.seekBar.max = mp.duration.coerceAtLeast(1)
            // 「打开并播放」：进入页面即自动播放
            runCatching { b.videoView.start() }
            // 图标必须在 start() 之后同步：isPlaying 已为 true，
            // 否则播放中会一直显示「播放」图标（实测截图可见）。
            updatePlayIcon(b)
            wasPlaying = true
            startProgressUpdates(b)
        }
        b.videoView.setOnCompletionListener {
            wasPlaying = false
            stopProgressUpdates()
            updatePlayIcon(b)
        }
        b.videoView.setOnErrorListener { _, _, _ ->
            b.pbPageLoading.isVisible = false
            true
        }

        b.btnPlayPause.setOnClickListener {
            if (b.videoView.isPlaying) {
                b.videoView.pause()
                wasPlaying = false
                stopProgressUpdates()
            } else {
                // 播完后点播放：先回起点再播。VideoView 在 PlaybackCompleted 态
                // 直接 start() 不保证重头播（实测停在片尾、进度不动），显式 seekTo(0)。
                val dur = b.videoView.duration
                if (dur > 0 && b.videoView.currentPosition >= dur - 100) {
                    b.videoView.seekTo(0)
                    b.seekBar.progress = 0
                    b.tvCurrentTime.text = MediaQuery.formatDuration(0)
                }
                b.videoView.start()
                wasPlaying = true
                startProgressUpdates(b)
            }
            updatePlayIcon(b)
        }
        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) b.tvCurrentTime.text = MediaQuery.formatDuration(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                stopProgressUpdates()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { b.videoView.seekTo(it.progress) }
                if (b.videoView.isPlaying) startProgressUpdates(b)
            }
        })
        // 点画面切换控件显隐
        b.videoControls.setOnClickListener {
            b.videoControls.isVisible = !b.videoControls.isVisible
        }
    }

    private fun updatePlayIcon(b: FragmentMediaPageBinding) {
        val playing = b.videoView.isPlaying
        b.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        b.btnPlayPause.contentDescription =
            getString(if (playing) R.string.pause else R.string.play)
    }

    private fun startProgressUpdates(b: FragmentMediaPageBinding) {
        stopProgressUpdates()
        val runnable = object : Runnable {
            override fun run() {
                val binding = binding ?: return
                if (binding.videoView.isPlaying) {
                    val pos = binding.videoView.currentPosition
                    binding.seekBar.progress = pos
                    binding.tvCurrentTime.text = MediaQuery.formatDuration(pos.toLong())
                    handler.postDelayed(this, 250)
                }
            }
        }
        progressRunnable = runnable
        handler.post(runnable)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    override fun onPause() {
        super.onPause()
        binding?.videoView?.let { vv ->
            if (vv.isPlaying) {
                vv.pause()
                wasPlaying = true
            }
        }
        stopProgressUpdates()
    }

    override fun onResume() {
        super.onResume()
        binding?.let { b ->
            if (media.isVideo && wasPlaying && !b.videoView.isPlaying) {
                runCatching { b.videoView.start() }
                startProgressUpdates(b)
            }
        }
    }

    override fun onDestroyView() {
        stopProgressUpdates()
        handler.removeCallbacksAndMessages(null)
        swipeVelocity?.recycle()
        swipeVelocity = null
        binding?.videoView?.stopPlayback()
        binding = null
        super.onDestroyView()
    }
}
