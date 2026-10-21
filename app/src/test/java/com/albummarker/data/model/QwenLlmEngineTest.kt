package com.albummarker.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenLlmEngineTest {

    @Test
    fun ` parses comma separated tags `() {
        val tags = QwenLlmEngine.parseTags("海边，日落，旅行，人物")
        assertEquals(listOf("海边", "日落", "旅行", "人物"), tags)
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
}
