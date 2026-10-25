package com.albummarker.data

/**
 * 首页网格上的标签浮层排布规则（纯逻辑，无 Android 依赖，可单测）。
 *
 * 网格单元只有屏幕宽度的 1/3，chip 排布必须收敛：
 *  - 标签 ≤ [MAX_VISIBLE_TAGS] 个：逐个展示，每个按自身来源着色；
 *  - 标签更多：只留首个 + 一个「+N」溢出标记（灰底）。
 */
object GridTagDisplay {

    /** 网格缩略图上最多直接展示的标签个数。 */
    const val MAX_VISIBLE_TAGS = 2

    /** 一个待渲染的 chip。 */
    data class Chip(
        val text: String,
        /** 标签来源（手动/自动/启发式），决定 chip 底色；溢出标记为 null（中性灰）。 */
        val source: String?,
    ) {
        val isOverflow: Boolean get() = source == null
    }

    /**
     * 计算某个媒体在网格上要渲染的 chip 列表。
     * @param tags 该媒体的全部标签（按创建时间升序）
     * @param moreFormat 「+N」的文案模板，入参为被折叠的标签个数
     */
    fun plan(tags: List<TagEntry>, moreFormat: (Int) -> String): List<Chip> {
        if (tags.isEmpty()) return emptyList()
        if (tags.size <= MAX_VISIBLE_TAGS) {
            return tags.map { Chip(it.tag, it.source) }
        }
        return listOf(
            Chip(tags.first().tag, tags.first().source),
            Chip(moreFormat(tags.size - 1), null),
        )
    }
}
