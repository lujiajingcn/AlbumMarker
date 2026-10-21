package com.albummarker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TagEmbedder 纯函数单测：合并语义、XMP 拼接、JPEG 段手术。
 * 全部 JVM 可跑（不依赖 Android）。
 */
class TagEmbedderTest {

    // ---- merge ----

    @Test
    fun merge_union_and_dedupe_case_insensitive() {
        val merged = TagEmbedder.merge(listOf("Beach"), listOf("beach", "Sunset", "  ", "旅行"))
        assertEquals(listOf("Beach", "Sunset", "旅行"), merged)
    }

    @Test
    fun merge_empty_existing() {
        assertEquals(listOf("a", "b"), TagEmbedder.merge(emptyList(), listOf("a", "b")))
    }

    @Test
    fun merge_trims_and_skips_blank() {
        val merged = TagEmbedder.merge(listOf(" old "), listOf("", "  ", "new\t"))
        assertEquals(listOf("old", "new"), merged)
    }

    // ---- escapeXml ----

    @Test
    fun escapeXml_escapes_specials() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", TagEmbedder.escapeXml("a&b<c>d\"e'f"))
    }

    // ---- parseSubjectItems / parseDescription ----

    @Test
    fun parseSubjectItems_extracts_all_li() {
        val xmp = "<?xml?><rdf:RDF><rdf:Description><dc:subject><rdf:Bag>" +
            "<rdf:li>甲</rdf:li><rdf:li>乙</rdf:li></rdf:Bag></dc:subject></rdf:Description></rdf:RDF>"
        assertEquals(listOf("甲", "乙"), TagEmbedder.parseSubjectItems(xmp))
    }

    @Test
    fun parseSubjectItems_absent_returns_empty() {
        assertTrue(TagEmbedder.parseSubjectItems("<rdf:RDF></rdf:RDF>").isEmpty())
    }

    @Test
    fun parseDescription_absent_returns_null() {
        assertNull(TagEmbedder.parseDescription("<dc:subject><rdf:Bag/></dc:subject>"))
    }

    @Test
    fun parseDescription_finds_first_li() {
        val xmp = "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">说明文字</rdf:li></rdf:Alt></dc:description>"
        assertEquals("说明文字", TagEmbedder.parseDescription(xmp))
    }

    // ---- spliceXmp ----

    private val ns = "http://ns.adobe.com/xap/1.0/\u0000"

    @Test
    fun spliceXmp_fresh_packet_roundtrip() {
        val payload = TagEmbedder.spliceXmp(null, listOf("甲", "乙"), "甲、乙")
        assertTrue(payload.startsWith(ns))
        assertTrue(payload.endsWith("<?xpacket end=\"w\"?>"))
        assertEquals(listOf("甲", "乙"), TagEmbedder.parseSubjectItems(payload))
        assertEquals("甲、乙", TagEmbedder.parseDescription(payload))
    }

    @Test
    fun spliceXmp_merges_into_existing_subject_and_keeps_other_props() {
        val existing = ns +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:subject><rdf:Bag><rdf:li>old</rdf:li></rdf:Bag></dc:subject>" +
            "</rdf:Description>" +
            "<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">" +
            "<xmp:CreatorTool>AlbumMarker</xmp:CreatorTool>" +
            "</rdf:Description>" +
            "</rdf:RDF></x:xmpmeta>"
        val out = TagEmbedder.spliceXmp(existing, listOf("old", "new"), null)
        assertEquals(listOf("old", "new"), TagEmbedder.parseSubjectItems(out))
        assertTrue("其它 XMP 属性必须保留", out.contains("<xmp:CreatorTool>AlbumMarker</xmp:CreatorTool>"))
    }

    @Test
    fun spliceXmp_appends_description_when_absent() {
        val existing = ns + "<rdf:RDF><rdf:Description><dc:subject><rdf:Bag><rdf:li>a</rdf:li></rdf:Bag></dc:subject></rdf:Description></rdf:RDF>"
        val out = TagEmbedder.spliceXmp(existing, listOf("a", "b"), "a、b")
        assertEquals(listOf("a", "b"), TagEmbedder.parseSubjectItems(out))
        assertEquals("a、b", TagEmbedder.parseDescription(out))
    }

    @Test
    fun spliceXmp_keeps_existing_description() {
        val existing = ns + "<rdf:RDF><rdf:Description>" +
            "<dc:subject><rdf:Bag><rdf:li>a</rdf:li></rdf:Bag></dc:subject>" +
            "<dc:description><rdf:Alt><rdf:li>用户原有说明</rdf:li></rdf:Alt></dc:description>" +
            "</rdf:Description></rdf:RDF>"
        val out = TagEmbedder.spliceXmp(existing, listOf("a", "b"), "a、b")
        assertEquals("用户原有说明", TagEmbedder.parseDescription(out))
    }

    @Test
    fun spliceXmp_idempotent_on_repeated_embed() {
        val first = TagEmbedder.spliceXmp(null, listOf("甲"), "甲")
        val second = TagEmbedder.spliceXmp(first, listOf("甲"), "甲")
        assertEquals(listOf("甲"), TagEmbedder.parseSubjectItems(second))
    }

    // ---- 命名空间合法性（回归防线） ----

    /**
     * 用命名空间严格的解析器校验 XMP 报文——模拟 Android MediaProvider
     * redaction 解析器（Xml.newPullParser()/kXML）的行为：未声明前缀直接抛异常。
     * 2026-10-14 真机事故：漏声明 xmlns:dc -> 设备上所有非机主应用
     * （含本应用）再也无法通过 content:// 打开该照片。
     */
    private fun parseStrictOrThrow(payload: String): Document {
        val xml = payload.removePrefix(ns)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // 不允许 DOCTYPE，贴近 kXML 的宽松度
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val builder = factory.newDocumentBuilder()
        return builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun dcSubjectTexts(doc: Document): List<String> {
        val out = mutableListOf<String>()
        val nodes = doc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "subject")
        for (i in 0 until nodes.length) {
            val items = (nodes.item(i) as org.w3c.dom.Element)
                .getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "li")
            for (j in 0 until items.length) out.add(items.item(j).textContent)
        }
        return out
    }

    @Test
    fun spliceXmp_fresh_packet_is_namespace_well_formed() {
        val payload = TagEmbedder.spliceXmp(null, listOf("甲", "乙"), "甲、乙")
        val doc = parseStrictOrThrow(payload)
        assertEquals(listOf("甲", "乙"), dcSubjectTexts(doc))
    }

    @Test
    fun spliceXmp_existing_without_dc_declaration_self_heals() {
        // 旧版本写入的畸形包：用了 dc: 但没声明 xmlns:dc
        val malformed = ns +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<dc:subject><rdf:Bag><rdf:li>old</rdf:li></rdf:Bag></dc:subject>" +
            "</rdf:RDF></x:xmpmeta>"
        val out = TagEmbedder.spliceXmp(malformed, listOf("old", "new"), null)
        assertEquals(listOf("old", "new"), TagEmbedder.parseSubjectItems(out))
        assertEquals(listOf("old", "new"), dcSubjectTexts(parseStrictOrThrow(out)))
    }

    @Test
    fun spliceXmp_append_description_path_is_namespace_well_formed() {
        val existing = ns +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\">" +
            "<xmp:CreatorTool xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">AlbumMarker</xmp:CreatorTool>" +
            "</rdf:Description>" +
            "</rdf:RDF></x:xmpmeta>"
        val out = TagEmbedder.spliceXmp(existing, listOf("甲"), "甲")
        assertEquals(listOf("甲"), dcSubjectTexts(parseStrictOrThrow(out)))
        assertTrue(out.contains("AlbumMarker"))
    }

    @Test
    fun spliceXmp_replace_subject_path_is_namespace_well_formed() {
        val existing = ns +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:subject><rdf:Bag><rdf:li>old</rdf:li></rdf:Bag></dc:subject>" +
            "</rdf:Description>" +
            "</rdf:RDF></x:xmpmeta>"
        val out = TagEmbedder.spliceXmp(existing, listOf("old", "旅行"), null)
        assertEquals(listOf("old", "旅行"), dcSubjectTexts(parseStrictOrThrow(out)))
    }

    // ---- JPEG 段手术 ----

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(),
            ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) + payload
    }

    private fun jpegBytes(vararg segments: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        segments.forEach { out.write(it) }
        out.write(0xFF); out.write(0xDA) // SOS：头部结束
        out.write(byteArrayOf(0x00, 0x02, 0x01, 0x00))
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    private fun markers(jpeg: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var i = 2
        while (i < jpeg.size - 1 && jpeg[i] == 0xFF.toByte()) {
            val m = jpeg[i + 1].toInt() and 0xFF
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
            if (m == 0xD9 || m == 0xDA) break
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            out.add(m)
            i += 2 + len
        }
        return out
    }

    @Test
    fun insertOrReplaceXmp_inserts_after_app0() {
        val base = jpegBytes(
            segment(0xE0, "JFIF\u0000\u0001\u0001".toByteArray()),
            segment(0xE2, "ICC_PROFILE\u0000".toByteArray())
        )
        val payload = TagEmbedder.spliceXmp(null, listOf("甲"), "甲").toByteArray()
        val out = TagEmbedder.insertOrReplaceXmp(base, payload)
        assertEquals(listOf(0xE0, 0xE1, 0xE2), markers(out))
        assertEquals(listOf("甲"), TagEmbedder.parseSubjectItems(TagEmbedder.findXmpPayload(out)!!))
    }

    @Test
    fun insertOrReplaceXmp_inserts_after_soi_when_no_app0() {
        val base = jpegBytes(segment(0xE2, "ICC_PROFILE\u0000".toByteArray()))
        val payload = TagEmbedder.spliceXmp(null, listOf("甲"), "甲").toByteArray()
        val out = TagEmbedder.insertOrReplaceXmp(base, payload)
        assertEquals(listOf(0xE1, 0xE2), markers(out))
    }

    @Test
    fun insertOrReplaceXmp_replaces_existing_in_place() {
        val oldPayload = TagEmbedder.spliceXmp(null, listOf("old"), "old").toByteArray()
        val base = jpegBytes(
            segment(0xE0, "JFIF\u0000".toByteArray()),
            segment(0xE1, oldPayload),
            segment(0xE2, "ICC_PROFILE\u0000".toByteArray())
        )
        val newPayload = TagEmbedder.spliceXmp(null, listOf("new"), "new").toByteArray()
        val out = TagEmbedder.insertOrReplaceXmp(base, newPayload)
        assertEquals(listOf(0xE0, 0xE1, 0xE2), markers(out))
        val found = TagEmbedder.findXmpPayload(out)!!
        assertEquals(listOf("new"), TagEmbedder.parseSubjectItems(found))
        assertFalse(found.contains("old"))
    }

    @Test
    fun isJpeg_detects_soi() {
        assertTrue(TagEmbedder.isJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse(TagEmbedder.isJpeg(byteArrayOf(0x89.toByte(), 0x50, 0x4E)))
    }
}
