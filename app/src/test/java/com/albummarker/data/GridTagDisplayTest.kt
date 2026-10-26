package com.albummarker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页网格标签展示规则：所有标签都要展示，不再折叠。 */
class GridTagDisplayTest {

    private fun entry(tag: String, source: String = TagEntry.SOURCE_MANUAL) =
        TagEntry(tag, source, 0L)

    private fun plan(vararg tags: TagEntry): List<GridTagDisplay.Chip> =
        GridTagDisplay.plan(tags.toList())

    @Test
    fun ` no tags yields no chips `() {
        assertTrue(plan().isEmpty())
    }

    @Test
    fun ` single tag yields one chip `() {
        val chips = plan(entry("海边"))
        assertEquals(1, chips.size)
        assertEquals("海边", chips[0].text)
    }

    @Test
    fun ` all tags are shown one by one `() {
        val chips = plan(entry("海边"), entry("日落"), entry("旅行"))
        assertEquals(listOf("海边", "日落", "旅行"), chips.map { it.text })
    }

    @Test
    fun ` many tags are not collapsed `() {
        val chips = plan(entry("a"), entry("b"), entry("c"), entry("d"), entry("e"))
        assertEquals(listOf("a", "b", "c", "d", "e"), chips.map { it.text })
    }

    @Test
    fun ` each chip keeps its own source for coloring `() {
        val chips = plan(
            entry("手动", TagEntry.SOURCE_MANUAL),
            entry("自动", TagEntry.SOURCE_AUTO),
            entry("启发式", TagEntry.SOURCE_HEURISTIC),
        )
        assertEquals(TagEntry.SOURCE_MANUAL, chips[0].source)
        assertEquals(TagEntry.SOURCE_AUTO, chips[1].source)
        assertEquals(TagEntry.SOURCE_HEURISTIC, chips[2].source)
    }
}
