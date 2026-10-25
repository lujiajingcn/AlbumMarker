package com.albummarker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页网格标签浮层的排布规则。 */
class GridTagDisplayTest {

    private fun entry(tag: String, source: String = TagEntry.SOURCE_MANUAL) =
        TagEntry(tag, source, 0L)

    private fun plan(vararg tags: TagEntry): List<GridTagDisplay.Chip> =
        GridTagDisplay.plan(tags.toList()) { "+$it" }

    @Test
    fun ` no tags yields no chips `() {
        assertTrue(plan().isEmpty())
    }

    @Test
    fun ` single tag yields one chip `() {
        val chips = plan(entry("海边"))
        assertEquals(1, chips.size)
        assertEquals("海边", chips[0].text)
        assertTrue(!chips[0].isOverflow)
    }

    @Test
    fun ` two tags are shown one by one `() {
        val chips = plan(entry("海边"), entry("日落"))
        assertEquals(2, chips.size)
        assertEquals(listOf("海边", "日落"), chips.map { it.text })
    }

    @Test
    fun ` three tags collapse into first plus overflow `() {
        val chips = plan(entry("海边"), entry("日落"), entry("旅行"))
        assertEquals(2, chips.size)
        assertEquals("海边", chips[0].text)
        assertEquals("+2", chips[1].text)
        assertTrue(chips[1].isOverflow)
    }

    @Test
    fun ` overflow count is folded tag number `() {
        val chips = plan(entry("a"), entry("b"), entry("c"), entry("d"), entry("e"))
        assertEquals("+4", chips[1].text)
    }

    @Test
    fun ` each real chip keeps its own source for coloring `() {
        val chips = plan(
            entry("手动", TagEntry.SOURCE_MANUAL),
            entry("自动", TagEntry.SOURCE_AUTO),
        )
        assertEquals(TagEntry.SOURCE_MANUAL, chips[0].source)
        assertEquals(TagEntry.SOURCE_AUTO, chips[1].source)
    }

    @Test
    fun ` overflow chip has no source `() {
        val chips = plan(entry("a"), entry("b"), entry("c"))
        assertEquals(null, chips[1].source)
        assertTrue(chips[1].isOverflow)
    }
}
