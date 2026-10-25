package com.albummarker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批量打标前的准备结果：交集标签计算。 */
class BatchTaggerTest {

    private fun entry(tag: String) = TagEntry(tag, TagEntry.SOURCE_MANUAL, 0L)

    private fun prepared(vararg items: Pair<String, List<TagEntry>>) =
        BatchTagger.Prepared(
            hashByUri = items.associate { it.first to "hash-${it.first}" },
            tagsByUri = items.toMap(),
        )

    @Test
    fun ` empty selection yields no common tags `() {
        assertTrue(BatchTagger.Prepared(emptyMap(), emptyMap()).commonTags().isEmpty())
    }

    @Test
    fun ` single item's tags are all common `() {
        val p = prepared("a" to listOf(entry("海边"), entry("日落")))
        assertEquals(setOf("海边", "日落"), p.commonTags())
    }

    @Test
    fun ` intersection keeps only shared tags `() {
        val p = prepared(
            "a" to listOf(entry("海边"), entry("日落")),
            "b" to listOf(entry("日落"), entry("旅行")),
        )
        assertEquals(setOf("日落"), p.commonTags())
    }

    @Test
    fun ` no shared tags yields empty `() {
        val p = prepared(
            "a" to listOf(entry("海边")),
            "b" to listOf(entry("旅行")),
        )
        assertTrue(p.commonTags().isEmpty())
    }

    @Test
    fun ` untagged item does not shrink the intersection `() {
        // 没标签的项不参与交集：它缺的标签仍可添加给其它项
        val p = prepared(
            "a" to listOf(entry("海边"), entry("日落")),
            "b" to emptyList(),
        )
        assertEquals(setOf("海边", "日落"), p.commonTags())
    }

    @Test
    fun ` three items intersect pairwise `() {
        val p = prepared(
            "a" to listOf(entry("x"), entry("y"), entry("z")),
            "b" to listOf(entry("x"), entry("y")),
            "c" to listOf(entry("x")),
        )
        assertEquals(setOf("x"), p.commonTags())
    }
}
