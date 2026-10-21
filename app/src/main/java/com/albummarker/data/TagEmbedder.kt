package com.albummarker.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 把标签写进照片本体：JPEG 的 XMP APP1 段（dc:subject / dc:description）。
 *
 * 为什么选 XMP 而不是 EXIF/IPTC（2026-10-14 本机 Windows 11 实测）：
 *  - XMP dc:subject -> 资源管理器「属性 -> 详细信息 -> 标记」列直接显示，中文完好；
 *    Adobe Bridge / Lightroom / digiKam / exiftool 同样读 dc:subject。
 *  - IPTC APP13（2:25 Keywords）：资源管理器不显示。
 *  - androidx ExifInterface 只能写 US-ASCII 的 ImageDescription / UserComment
 *    （中文变 '?'），且没有 XPKeywords 常量；手写 EXIF IFD 手术风险高。
 *  - XMP 是 UTF-8 XML，段级插入/替换即可，不动图像数据与其它元数据。
 *
 * 语义：合并——不破坏文件里已有的 XMP 属性；同名标签（大小写不敏感）跳过。
 * 仅支持 image/jpeg（PNG / 视频的元数据通道本期不做，UI 会提示）。
 *
 * 重要：写入后文件字节变化 -> SHA-256 变化，调用方必须调用
 * [TagStore.migrateHash] 把标签行从旧哈希搬到新哈希，否则 chips 会消失。
 */
object TagEmbedder {

    private const val TAG = "AlbumMarker"

    /** XMP APP1 段的命名空间头（标准签名，Adobe/Windows 均以此识别 XMP）。 */
    private const val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/\u0000"

    /** 防止把离谱的大文件整个读进内存。 */
    private const val MAX_FILE_BYTES = 128L * 1024 * 1024

    /**
     * dc:subject / dc:description 的 RDF/XML 模板片段。
     *
     * 注意：xmlns:dc 必须在此声明——Android MediaProvider 的 redaction 解析器
     * （Xml.newPullParser()，命名空间严格）会解析每个媒体文件的 XMP，
     * 未声明前缀直接抛 XmlPullParserException -> IOException("Failed to redact")
     * -> 应用（及其它无 ACCESS_MEDIA_LOCATION 的应用）将无法再通过 content:// 读取该文件。
     * 2026-10-14 真机实测：Windows 资源管理器容忍未声明前缀，但 kXML 不容忍。
     */
    private val XMP_HEAD = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>" +
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
        "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
    private val XMP_TAIL = "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"

    data class Result(
        /** 写入后的新 SHA-256（调用方用于 migrateHash）。 */
        val newHash: String,
        /** 实际写入文件的标签（合并去重后的全集）。 */
        val written: List<String>,
        /** 写入后的文件字节数。 */
        val newSize: Long,
    )

    // ---------- 纯函数（JVM 单测直接覆盖） ----------

    /**
     * 合并语义：existing 在前、新标签追加在后；大小写不敏感去重（保留首次出现的写法）；
     * 去空白、忽略空项。
     */
    fun merge(existing: Collection<String>, tags: Collection<String>): List<String> {
        val merged = LinkedHashMap<String, String>() // lower -> 原始写法
        (existing + tags).forEach { raw ->
            val t = raw.trim()
            if (t.isNotEmpty()) merged.putIfAbsent(t.lowercase(), t)
        }
        return merged.values.toList()
    }

    /** XMP 属性值 XML 转义（标签可能含 & < > " '）。 */
    fun escapeXml(s: String): String = buildString(s.length + 16) {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    private val SUBJECT_BLOCK = Regex("<dc:subject[^>]*>.*?</dc:subject>", RegexOption.DOT_MATCHES_ALL)
    private val RDF_LI = Regex("<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)
    private val DESCRIPTION_BLOCK = Regex("<dc:description[^>]*>.*?</dc:description>", RegexOption.DOT_MATCHES_ALL)

    /** 从 XMP payload 中解析已有 dc:subject 的所有 rdf:li 文本。 */
    fun parseSubjectItems(xmpPayload: String): List<String> {
        val block = SUBJECT_BLOCK.find(xmpPayload) ?: return emptyList()
        return RDF_LI.findAll(block.value).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** 已有 dc:description 的首个 li 文本（没有则 null，调用方据此决定是否补写）。 */
    fun parseDescription(xmpPayload: String): String? {
        val block = DESCRIPTION_BLOCK.find(xmpPayload) ?: return null
        return RDF_LI.find(block.value)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun subjectItemsXml(items: List<String>): String =
        "<dc:subject><rdf:Bag>" + items.joinToString("") { "<rdf:li>${escapeXml(it)}</rdf:li>" } +
        "</rdf:Bag></dc:subject>"

    private fun descriptionXml(text: String): String =
        "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${escapeXml(text)}</rdf:li>" +
        "</rdf:Alt></dc:description>"

    /**
     * 生成最终 XMP 段 payload（含命名空间头）：
     *  - existing == null：全新数据包；
     *  - 已有 dc:subject：整块替换为合并后的 items（保留包内其它所有属性）；
     *  - 没有 dc:subject：在 </rdf:RDF> 前追加一个 rdf:Description；
     *  - description 仅在文件原本没有 dc:description 时才补写。
     */
    fun spliceXmp(existingPayload: String?, items: List<String>, description: String?): String {
        val subjectXml = subjectItemsXml(items)
        // isNullOrBlank() 不做智能转型，先取成非空局部变量再传
        val descText = description?.takeIf { it.isNotBlank() }
        val descXml = if (descText == null || parseDescription(existingPayload ?: "") != null) {
            null
        } else {
            descriptionXml(descText)
        }
        if (existingPayload == null) {
            val body = StringBuilder(subjectXml)
            if (descXml != null) body.append(descXml)
            return XMP_NAMESPACE + XMP_HEAD + body + XMP_TAIL
        }
        // 防御：既有 XMP 若未声明 xmlns:dc（如旧版本写入的畸形包），先补声明再动内容，
        // 否则替换/追加后仍是命名空间非法的 XML，会引爆 MediaProvider redaction 解析器。
        val xml = ensureDcDeclaration(existingPayload.removePrefix(XMP_NAMESPACE))
        if (SUBJECT_BLOCK.containsMatchIn(xml)) {
            var out = SUBJECT_BLOCK.replace(xml) { subjectXml }
            if (descXml != null) {
                val idx = out.indexOf("</rdf:Description>")
                out = when {
                    idx >= 0 -> out.substring(0, idx) + descXml + out.substring(idx)
                    else -> {
                        // 没有 Description 闭合标签（结构异常）：放弃补写 description，subject 已生效
                        Log.w(TAG, "spliceXmp: 既有 XMP 无 </rdf:Description>，跳过 description 补写")
                        out
                    }
                }
            }
            return XMP_NAMESPACE + out
        }
        // 没有 dc:subject：追加一个新的 Description（RDF 允许多 Description，消费方取并集）
        val closeIdx = xml.lastIndexOf("</rdf:RDF>")
        if (closeIdx < 0) {
            Log.w(TAG, "spliceXmp: 既有 XMP 无 </rdf:RDF>，整体替换（可能丢失其它 XMP 属性）")
            return XMP_NAMESPACE + XMP_HEAD + subjectXml + (descXml ?: "") + XMP_TAIL
        }
        val descBlock = "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            subjectXml + (descXml ?: "") + "</rdf:Description>"
        return XMP_NAMESPACE + xml.substring(0, closeIdx) + descBlock + xml.substring(closeIdx)
    }

    /** 在既有 XMP 的根元素上补声明 xmlns:dc（已声明则原样返回）。 */
    private fun ensureDcDeclaration(xml: String): String {
        if (xml.contains("xmlns:dc")) return xml
        val decl = " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
        for (tag in listOf("<x:xmpmeta", "<rdf:RDF")) {
            val idx = xml.indexOf(tag)
            if (idx >= 0) {
                val insertAt = idx + tag.length
                return xml.substring(0, insertAt) + decl + xml.substring(insertAt)
            }
        }
        return xml
    }

    /** 是否 JPEG（SOI 开头）。 */
    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    /**
     * 在 JPEG 字节流中插入或替换 XMP APP1 段。
     * 有则原址替换（保留段序），无则插在 APP0（JFIF）之后、没有 APP0 则 SOI 之后。
     */
    fun insertOrReplaceXmp(jpeg: ByteArray, xmpPayload: ByteArray): ByteArray {
        require(isJpeg(jpeg)) { "not jpeg" }
        var i = 2
        var app0End = 2
        var xmpStart = -1
        var xmpEnd = -1
        while (i < jpeg.size - 1) {
            if (jpeg[i] != 0xFF.toByte()) break
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            if (marker == 0xD9 || marker == 0xDA) break // EOI / SOS：头部结束
            if (i + 4 > jpeg.size) break
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > jpeg.size) break
            val payloadStart = i + 4
            val payloadEnd = i + 2 + length
            if (marker == 0xE0) app0End = payloadEnd
            if (marker == 0xE1 && startsWithXmpNamespace(jpeg, payloadStart, payloadEnd)) {
                xmpStart = i
                xmpEnd = payloadEnd
                break
            }
            i = payloadEnd
        }
        val newSegment = buildXmpSegment(xmpPayload)
        val out = ByteArrayOutputStream(jpeg.size + newSegment.size)
        if (xmpStart >= 0) {
            out.write(jpeg, 0, xmpStart)
            out.write(newSegment)
            out.write(jpeg, xmpEnd, jpeg.size - xmpEnd)
        } else {
            out.write(jpeg, 0, app0End)
            out.write(newSegment)
            out.write(jpeg, app0End, jpeg.size - app0End)
        }
        return out.toByteArray()
    }

    private fun startsWithXmpNamespace(jpeg: ByteArray, from: Int, to: Int): Boolean {
        val ns = XMP_NAMESPACE.toByteArray(Charsets.US_ASCII)
        if (to - from < ns.size) return false
        for (k in ns.indices) if (jpeg[from + k] != ns[k]) return false
        return true
    }

    private fun buildXmpSegment(payload: ByteArray): ByteArray {
        val length = payload.size + 2
        require(length in 2..0xFFFF) { "xmp segment too large: $length" }
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte()) +
            byteArrayOf(((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()) + payload
    }

    /** 找到 XMP APP1 段的 payload（不含段头与命名空间头），没有返回 null。 */
    fun findXmpPayload(jpeg: ByteArray): String? {
        var i = 2
        while (i < jpeg.size - 1) {
            if (jpeg[i] != 0xFF.toByte()) break
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            if (marker == 0xD9 || marker == 0xDA) break
            if (i + 4 > jpeg.size) break
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > jpeg.size) break
            val payloadStart = i + 4
            val payloadEnd = i + 2 + length
            if (marker == 0xE1 && startsWithXmpNamespace(jpeg, payloadStart, payloadEnd)) {
                return String(jpeg, payloadStart, payloadEnd - payloadStart, Charsets.UTF_8)
            }
            i = payloadEnd
        }
        return null
    }

    // ---------- 写入 ----------

    /**
     * 把 tags 写入 uri 对应 JPEG 的 XMP 段并返回新哈希。
     *
     * 前置条件（调用方负责）：
     *  - Android 10+ 修改非本应用创建的媒体文件需先 MediaStore.createWriteRequest() 授权；
     *  - 仅 image/jpeg。
     */
    suspend fun embed(context: Context, uri: Uri, tags: List<String>): Result = withContext(Dispatchers.IO) {
        require(tags.isNotEmpty()) { "tags 不能为空" }
        val resolver = context.contentResolver
        val original = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("无法读取文件: $uri")
        if (original.size > MAX_FILE_BYTES) {
            throw IOException("文件过大（>128MB），暂不支持写入标签")
        }
        if (!isJpeg(original)) {
            throw IOException("仅支持 JPEG 照片（当前文件不是 JPEG）")
        }

        val existingPayload = findXmpPayload(original)
        val existingItems = existingPayload?.let { parseSubjectItems(it) }.orEmpty()
        val merged = merge(existingItems, tags)
        val description = merged.joinToString("、")
        val newPayload = spliceXmp(existingPayload, merged, description)
        if (newPayload.length + 2 > 0xFFFF) {
            throw IOException("文件的 XMP 数据过大（段将超过 64KB），暂不支持追加标签")
        }
        val newBytes = insertOrReplaceXmp(original, newPayload.toByteArray(Charsets.UTF_8))
        val newHash = MediaHasher.hashBytes(newBytes)
        Log.d(TAG, "embed: $uri tags=${tags.size} merged=${merged.size} " +
            "bytes ${original.size}->${newBytes.size} newHash=${newHash.take(16)}")

        resolver.openOutputStream(uri, "w")?.use { it.write(newBytes) }
            ?: throw IOException("无法写入文件: $uri")

        // 回读校验：哈希迁移的正确性完全依赖「落盘内容 == 我们以为的内容」。
        // 但平台存在已知 quirk：写完立刻 openInputStream 可能抛 IOException
        // （如 MediaProvider redaction 一次性授权被消耗后的瞬时不可读）。
        // 此时不能直接失败——文件已落盘，中止会让标签行留在旧哈希下与文件脱步；
        // 退化为文件大小校验（能查询到时），大小一致即认为写入完整。
        val writtenBack = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            // 回读可能抛 IOException，也可能被 MediaProvider redaction 包成
            // IllegalStateException("Failed to redact ...")——一律退化为大小校验。
            Log.w(TAG, "embed: 回读失败，退化为大小校验: ${e.message}")
            null
        }
        if (writtenBack != null) {
            val actualHash = MediaHasher.hashBytes(writtenBack)
            if (actualHash != newHash) {
                throw IOException("写入校验失败（回读哈希不一致）")
            }
        } else {
            val sizeNow = queryFileSize(resolver, uri)
            if (sizeNow != null && sizeNow != newBytes.size.toLong()) {
                throw IOException("写入校验失败（文件大小 $sizeNow != ${newBytes.size}）")
            }
            Log.w(TAG, "embed: 回读不可用且大小校验通过（$sizeNow），按内存哈希迁移")
        }
        Result(newHash, merged, newBytes.size.toLong())
    }

    /** 查询 MediaStore 记录的文件大小；查不到返回 null（不视为失败）。 */
    private fun queryFileSize(resolver: android.content.ContentResolver, uri: Uri): Long? =
        try {
            resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
        } catch (e: Exception) {
            Log.w(TAG, "embed: 查询文件大小失败: ${e.message}")
            null
        }
}
