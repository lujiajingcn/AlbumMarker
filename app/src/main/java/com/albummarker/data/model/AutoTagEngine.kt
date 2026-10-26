package com.albummarker.data.model

import android.graphics.Bitmap
import com.albummarker.data.ExifFacts
import com.albummarker.data.VideoFacts

/** 自动打标的输入：若干帧位图 + 可选 EXIF / 视频元信息。 */
data class TagInput(
    val bitmaps: List<Bitmap>,
    val exif: ExifFacts? = null,
    val video: VideoFacts? = null,
)

/**
 * 需求 6：自动打标引擎抽象。
 * 实现类：QwenLlmEngine（LiteRT-LM 本地 Qwen 模型）、HeuristicTagEngine（无模型兜底）。
 */
interface AutoTagEngine {
    val id: String
    val displayName: String

    /** 返回建议标签列表（已去重、限量）；失败抛异常由调用方兜底。 */
    suspend fun suggestTags(input: TagInput): List<String>

    /** 释放 native 资源。 */
    fun release() {}

    companion object {
        const val ID_QWEN = "qwen"
        const val ID_HEURISTIC = "heuristic"

        /** 自动打标结果上限：最多 3 个标签。 */
        const val MAX_AUTO_TAGS = 3

        /**
         * 统一约束自动标签：去空白、去重（大小写不敏感）、最多 3 个。
         * 允许 0 个（模型没有把握时不强行编造标签）。
         */
        fun normalize(tags: List<String>): List<String> =
            tags.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .take(MAX_AUTO_TAGS)
                .toList()
    }
}
