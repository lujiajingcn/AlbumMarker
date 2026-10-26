package com.albummarker.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.albummarker.R
import com.albummarker.data.MediaBitmaps
import com.albummarker.data.MediaItem
import com.albummarker.data.model.AutoTagEngine
import com.albummarker.data.model.HeuristicTagEngine
import com.albummarker.data.model.QwenLlmEngine
import com.albummarker.data.model.TagInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 查看器的自动打标状态机。 */
class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AlbumMarker"
    }

    sealed class AutoTagState {
        object Idle : AutoTagState()
        data class Running(val message: String) : AutoTagState()
        data class Suggestions(val tags: List<String>, val engineId: String) : AutoTagState()
        data class Error(val message: String) : AutoTagState()
    }

    private val _autoTagState = MutableStateFlow<AutoTagState>(AutoTagState.Idle)
    val autoTagState: StateFlow<AutoTagState> = _autoTagState.asStateFlow()

    private var autoJob: Job? = null
    private var qwenEngine: QwenLlmEngine? = null

    /** onCleared 后 ViewModel 的 scope 已取消，释放模型必须用独立的后台 scope。 */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 对当前媒体执行自动打标。
     * @param useQwen true 时加载 [modelPath] 指向的 Qwen 模型；false 走启发式引擎。
     */
    fun runAutoTag(media: MediaItem, useQwen: Boolean, modelPath: String?) {
        Log.d(TAG, "runAutoTag: media=" + media.displayName + " useQwen=" + useQwen)
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val qwenPath = modelPath?.takeIf { useQwen }
            val modelAlreadyLoaded = qwenPath != null && qwenEngine?.isLoaded(qwenPath) == true
            _autoTagState.value = AutoTagState.Running(
                app.getString(
                    if (qwenPath != null && !modelAlreadyLoaded) R.string.auto_tag_analyzing_qwen
                    else R.string.auto_tag_analyzing
                )
            )
            Log.d(TAG, "state -> Running (modelLoaded=$modelAlreadyLoaded)")
            var input: TagInput? = null
            try {
                val payload = withContext(Dispatchers.IO) {
                    Log.d(TAG, "buildInput start")
                    buildInput(app, media).also { Log.d(TAG, "buildInput done: ${it.bitmaps.size} bitmaps") }
                }
                input = payload
                if (payload.bitmaps.isEmpty()) {
                    throw IllegalStateException("无法从该媒体提取画面帧")
                }
                val engine: AutoTagEngine = if (qwenPath != null) {
                    val qwen = qwenEngine ?: QwenLlmEngine(app).also { qwenEngine = it }
                    withContext(Dispatchers.IO) { qwen.prepare(qwenPath) }
                    qwen
                } else {
                    HeuristicTagEngine()
                }
                val tags = AutoTagEngine.normalize(engine.suggestTags(payload))
                Log.d(TAG, "engine done: $tags")
                _autoTagState.value = AutoTagState.Suggestions(tags, engine.id)
                Log.d(TAG, "state -> Suggestions(${tags.size})")
            } catch (cancel: CancellationException) {
                Log.d(TAG, "cancelled")
                throw cancel
            } catch (t: Throwable) {
                Log.e(TAG, "autoTag failed", t)
                _autoTagState.value = AutoTagState.Error(
                    if (t is OutOfMemoryError) {
                        app.getString(R.string.auto_tag_oom)
                    } else {
                        t.message ?: t.javaClass.simpleName
                    }
                )
            } finally {
                // 输入位图只服务这一次推理；无论成功、失败还是取消都及时回收。
                input?.bitmaps?.forEach { runCatching { it.recycle() } }
            }
        }
    }

    fun resetAutoTag() {
        autoJob?.cancel()
        _autoTagState.value = AutoTagState.Idle
    }

    private suspend fun buildInput(app: Application, media: MediaItem): TagInput =
        if (media.isVideo) {
            TagInput(
                bitmaps = MediaBitmaps.framesForVideo(app, media),
                exif = null,
                video = MediaBitmaps.videoFacts(app, media),
            )
        } else {
            TagInput(
                bitmaps = listOfNotNull(MediaBitmaps.bitmapForImage(app, media.uri)),
                exif = MediaBitmaps.exifFacts(app, media.uri),
                video = null,
            )
        }

    override fun onCleared() {
        autoJob?.cancel()
        val engine = qwenEngine
        qwenEngine = null
        // release() 内部要等推理/加载释放锁；不能放在主线程，否则 2B 推理时退出会 ANR。
        releaseScope.launch { engine?.release() }
        super.onCleared()
    }
}
