package com.albummarker.data.model

import com.albummarker.data.TagParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenLlmEngineTest {

    @Test
    fun ` parses comma separated tags `() {
        val tags = QwenLlmEngine.parseTags("海边，日落，旅行，人物")
        assertEquals(listOf("海边", "日落", "旅行"), tags)
    }

    @Test
    fun ` strips numbering and bullets `() {
        val tags = QwenLlmEngine.parseTags("1. 海边\n2、日落\n3. 旅行")
        assertEquals(listOf("海边", "日落", "旅行"), tags)
    }

    @Test
    fun ` strips quotes and decorations `() {
        val tags = QwenLlmEngine.parseTags("\"猫\"、'狗'、*鸟*")
        assertEquals(listOf("猫", "狗", "鸟"), tags)
    }

    @Test
    fun ` dedupes and caps count `() {
        val raw = (1..30).joinToString("，") { "tag$it" } + "，tag1"
        val tags = QwenLlmEngine.parseTags(raw)
        assertEquals(QwenLlmEngine.MAX_TAG_COUNT, tags.size)
        assertEquals("tag1", tags.first())
    }

    @Test
    fun ` blank output yields empty `() {
        assertTrue(QwenLlmEngine.parseTags("   \n ").isEmpty())
        assertTrue(QwenLlmEngine.parseTags("").isEmpty())
    }

    @Test
    fun ` normalize caps at three and allows empty `() {
        assertEquals(
            listOf("a", "b", "c"),
            AutoTagEngine.normalize(listOf("a", "b", "c", "d")),
        )
        assertTrue(AutoTagEngine.normalize(listOf(" ", "  ", "")).isEmpty())
    }

    @Test
    fun ` normalize strips category prefixes `() {
        assertEquals(
            listOf("公园", "树木", "绿色"),
            AutoTagEngine.normalize(listOf("场景：公园", "主要对象：树木", "明显属性：绿色")),
        )
        assertEquals(
            listOf("警示标识"),
            AutoTagEngine.normalize(listOf("内容类别：警示标识")),
        )
        assertEquals(
            listOf("公园"),
            AutoTagEngine.normalize(listOf("场景：公园", "公园")),
        )
        assertEquals("公园", TagParser.stripCategoryPrefix("场景：公园"))
    }
}
