package com.albummarker.data.model

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 需求 6：基于 LiteRT-LM 的本地 Qwen 引擎，加载 `.litertlm` 模型包。
 *
 * 与旧 MediaPipe 实现的区别：
 * - 模型必须是 **VL（视觉）版** `Qwen3.5-0.8B-VL_int8.litertlm`，纯文本版看不到照片，
 *   无法用于打标；
 * - 图像以 JPEG 字节（`Content.ImageBytes`）随消息送入，模型侧按 512×512 预处理；
 * - 每次打标创建并关闭一个 Conversation，Engine 复用（首次 initialize 可能 10–60 秒）。
 */
class QwenLlmEngine(private val context: Context) : AutoTagEngine {

    override val id: String = AutoTagEngine.ID_QWEN
    override val displayName: String = "Qwen3.5-0.8B-VL（LiteRT-LM）"

    private val lock = Any()

    // volatile：isLoaded() 会在主线程被调用，不能去抢 prepare() 加载模型时持有的锁
    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var loadedPath: String? = null

    /** 幂等加载模型；重复调用同路径不会重建。调用方应放在 IO 线程。 */
    fun prepare(modelPath: String) {
        synchronized(lock) {
            if (loadedPath == modelPath && engine != null) return
            release()
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                maxNumTokens = MAX_CONTEXT_TOKENS,
                maxNumImages = 1,
                cacheDir = context.cacheDir.path,
            )
            val loaded = Engine(config)
            loaded.initialize()
            engine = loaded
            loadedPath = modelPath
        }
    }

    /** 指定模型是否已加载；用于区分「首次加载」与「后续复用」的提示文案。 */
    fun isLoaded(modelPath: String): Boolean = loadedPath == modelPath && engine != null

    override suspend fun suggestTags(input: TagInput): List<String> = withContext(Dispatchers.Default) {
        val raw = synchronized(lock) {
            val active = engine ?: throw IllegalStateException("Qwen 模型未加载")
            generate(active, input)
        }
        parseTags(raw)
    }

    private fun generate(engine: Engine, input: TagInput): String {
        val image = input.bitmaps.firstOrNull()
        if (image == null) {
            throw IllegalStateException("没有可用的画面帧，无法进行图像打标")
        }
        val contents = Contents.of(
            Content.ImageBytes(bitmapToJpeg(image)),
            Content.Text(PROMPT),
        )
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = TOP_K,
                topP = TOP_P,
                temperature = TEMPERATURE,
                seed = 0,
            ),
            maxOutputToken = MAX_OUTPUT_TOKENS,
            // Qwen3.5 的模板默认会带一个空思考块；这里直接关闭思考，减少输出噪声。
            thinkingConfig = com.google.ai.edge.litertlm.ThinkingConfig(false, 0),
        )
        return try {
            engine.createConversation(config).use { conversation ->
                conversation.sendMessage(contents).toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT-LM inference failed", e)
            throw IllegalStateException(
                "Qwen 模型推理失败（请确认安装的是 VL 视觉版 .litertlm）：" +
                    (e.message ?: e.javaClass.simpleName),
                e,
            )
        }
    }

    /** 缩到最长边 512 并压成 JPEG：Qwen3.5-VL 的视觉输入为 512×512，太大只会浪费内存。 */
    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest <= IMAGE_MAX_SIDE) {
            bitmap
        } else {
            val scale = IMAGE_MAX_SIDE.toFloat() / longest
            val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        return try {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    override fun release() {
        synchronized(lock) {
            runCatching { engine?.close() }
            engine = null
            loadedPath = null
        }
    }

    companion object {
        private const val TAG = "QwenLlmEngine"

        /** 视觉输入最长边。 */
        private const val IMAGE_MAX_SIDE = 512
        private const val JPEG_QUALITY = 90

        /** 上下文与输出上限。 */
        private const val MAX_CONTEXT_TOKENS = 2048
        private const val MAX_OUTPUT_TOKENS = 192

        private const val TEMPERATURE = 0.2
        private const val TOP_K = 4
        private const val TOP_P = 0.9

        /** 面向 Qwen 系列的中文打标提示词。 */
        const val PROMPT =
            "你是一个图片标签助手。请根据图片内容，用简体中文列出 5 到 8 个最相关的标签。" +
                "标签应为简短的名词或名词短语，覆盖：场景或地点、主要对象、动作或事件、" +
                "明显属性（颜色、天气、时间段）、内容类别。只输出标签本身，" +
                "多个标签用中文逗号「，」分隔，不要输出编号、解释或任何其他内容。"

        private val SEPARATORS = charArrayOf(
            '，', ',', '、', ';', '；', '\n', '\r', '\t', ' ', '。', '.'
        )

        /** 行首编号前缀，如 "1. " / "2、"。 */
        private val NUMBERING_PREFIX = Regex("^\\d+\\s*[.、。:：\\-]\\s*")

        /** 把模型原始输出切成标签列表：去引号/编号、去重、限量。 */
        fun parseTags(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            val result = LinkedHashSet<String>()
            for (piece in raw.split(delimiters = SEPARATORS)) {
                var tag = piece.trim()
                    .trim('"', '\'', '“', '”', '‘', '’', '*', '#')
                    .removePrefix("-").removePrefix("•").trim()
                tag = tag.replaceFirst(NUMBERING_PREFIX, "")
                if (tag.isEmpty() || tag.all { it.isDigit() }) continue
                if (tag.length > MAX_TAG_LEN) tag = tag.take(MAX_TAG_LEN)
                result.add(tag)
                if (result.size >= MAX_TAG_COUNT) break
            }
            return result.toList()
        }

        const val MAX_TAG_LEN = 20
        const val MAX_TAG_COUNT = 12
    }
}
