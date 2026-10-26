package com.albummarker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagParserTest {

    @Test
    fun ` splits by chinese and english commas `() {
        assertEquals(
            listOf("海边", "日落", "旅行"),
            TagParser.parse("海边，日落, 旅行")
        )
    }

    @Test
    fun ` splits by dunhao semicolon and whitespace `() {
        assertEquals(
            listOf("a", "b", "c", "d"),
            TagParser.parse("a、b；c d")
        )
    }

    @Test
    fun ` trims quotes and blank pieces `() {
        assertEquals(
            listOf("猫", "狗"),
            TagParser.parse("  \"猫\" ,  , '狗' ")
        )
    }

    @Test
    fun ` dedupes preserving order `() {
        assertEquals(listOf("x", "y"), TagParser.parse("x, y, x, X, y"))
    }

    @Test
    fun ` truncates overlong tags `() {
        val long = "标".repeat(TagParser.MAX_TAG_LENGTH + 10)
        val result = TagParser.parse(long)
        assertEquals(1, result.size)
        assertEquals(TagParser.MAX_TAG_LENGTH, result[0].length)
    }

    @Test
    fun ` caps tag count `() {
        val raw = (1..50).joinToString(",") { "tag$it" }
        assertEquals(TagParser.MAX_TAGS, TagParser.parse(raw).size)
    }

    @Test
    fun ` blank input yields empty `() {
        assertTrue(TagParser.parse("   \n\t ").isEmpty())
        assertTrue(TagParser.parse("").isEmpty())
    }

    @Test
    fun ` filterExisting is case insensitive `() {
        assertEquals(
            listOf("b", "c"),
            TagParser.filterExisting(listOf("A", "b", "c"), listOf("a"))
        )
    }

    @Test
    fun ` detects prompt and thinking noise `() {
        assertTrue(TagParser.isAutoTagNoise("场景"))
        assertTrue(TagParser.isAutoTagNoise("主要对象"))
        assertTrue(TagParser.isAutoTagNoise("图片标签助手"))
        assertTrue(TagParser.isAutoTagNoise("<think>"))
        assertTrue(TagParser.isAutoTagNoise("图片展示了一个户外场景"))
        assertTrue(TagParser.isAutoTagNoise("好的"))
        assertFalse(TagParser.isAutoTagNoise("公园"))
        assertFalse(TagParser.isAutoTagNoise("禁止攀爬标志牌"))
    }

    @Test
    fun ` strips category prefix `() {
        assertEquals("公园", TagParser.stripCategoryPrefix("场景：公园"))
        assertEquals("树木", TagParser.stripCategoryPrefix("主要对象: 树木"))
        assertEquals("绿色", TagParser.stripCategoryPrefix("明显属性（颜色）：绿色"))
        assertEquals("公园", TagParser.stripCategoryPrefix("公园"))
    }
}
