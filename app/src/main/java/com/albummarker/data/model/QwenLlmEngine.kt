package com.albummarker.data.model

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 需求 6：基于 MediaPipe LLM Inference 的本地 Qwen 引擎。
 *
 * - 模型文件（.task / .litert）由 ModelManager 管理，路径在 prepare() 时传入；
 * - 图像经 LlmInferenceSession.addImage(MPImage) 送入（已验证 0.10.35 支持）；
 * - 模型为纯文本时自动降级为纯文本提示（图像信息丢失但不崩溃）；
 * - 输出按分隔符切分为标签。
 *
 * LlmInference 实例复用（模型加载数秒，不能每次请求都重建）。
 */
class QwenLlmEngine(private val context: Context) : AutoTagEngine {

    override val id: String = AutoTagEngine.ID_QWEN
    override val displayName: String = "Qwen3.5-0.8B（本地模型）"

    private val lock = Any()
    private var llm: LlmInference? = null
    private var loadedPath: String? = null

    /** 幂等加载模型；重复调用同路径不会重建。 */
    fun prepare(modelPath: String) {
        synchronized(lock) {
            if (loadedPath == modelPath && llm != null) return
            release()
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                .setMaxNumImages(MAX_IMAGES)
                .build()
            llm = LlmInference.createFromOptions(context, options)
            loadedPath = modelPath
        }
    }

    override suspend fun suggestTags(input: TagInput): List<String> =
        withContext(Dispatchers.Default) {
            val engine = synchronized(lock) { llm }
                ?: throw IllegalStateException("Qwen 模型未加载")
            val images = input.bitmaps.take(MAX_IMAGES)
            val raw = runCatching {
                generate(engine, images, PROMPT)
            }.getOrElse { firstError ->
                // 纯文本模型不接受图像输入：去掉图像重试一次
                if (images.isNotEmpty()) {
                    runCatching { generate(engine, emptyList(), PROMPT) }
                        .getOrElse { throw firstError }
                } else {
                    throw firstError
                }
            }
            parseTags(raw)
        }

    private fun generate(engine: LlmInference, images: List<Bitmap>, prompt: String): String {
        val session = LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(TEMPERATURE)
                .setTopK(TOP_K)
                .setTopP(TOP_P)
                .build()
        )
        try {
            images.forEach { session.addImage(BitmapImageBuilder(it).build()) }
            session.addQueryChunk(prompt)
            return session.generateResponse()
        } finally {
            runCatching { session.close() }
        }
    }

    override fun release() {
        synchronized(lock) {
            runCatching { llm?.close() }
            llm = null
            loadedPath = null
        }
    }

    companion object {
        /** 单次推理最多送入的图像数（视频抽帧上限）。 */
        const val MAX_IMAGES = 3
        private const val MAX_TOKENS = 384
        private const val TEMPERATURE = 0.2f
        private const val TOP_K = 4
        private const val TOP_P = 0.9f

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
                // 去掉行首编号，如 "1. 海边"
                tag = tag.replaceFirst(NUMBERING_PREFIX, "")
                // 纯数字片段是编号残留，不是标签
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
