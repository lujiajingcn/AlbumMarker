package com.albummarker.data

/**
 * 首页网格上的标签展示规则（纯逻辑，无 Android 依赖，可单测）。
 *
 * 需求：显示标签时把所有标签内容都展示出来，不再折叠为「+N」。
 * 具体换行/尺寸由 ChipGroup 负责。
 */
object GridTagDisplay {

    /** 一个待渲染的 chip。 */
    data class Chip(
        val text: String,
        /** 标签来源（手动/自动/启发式），决定 chip 底色。 */
        val source: String,
    )

    /** 每个标签都生成一个 chip，顺序与传入标签一致。 */
    fun plan(tags: List<TagEntry>): List<Chip> =
        tags.map { Chip(it.tag, it.source) }
}
