package com.albummarker.data

/**
 * 需求 4：把输入框里的原始文本切成标签。
 * 支持中英文逗号、顿号、分号、空白、斜杠分隔；去空白、去重、限量。
 */
object TagParser {

    const val MAX_TAG_LENGTH = 24
    const val MAX_TAGS = 20

    private val SEPARATORS = charArrayOf(
        ',', '，', '、', ';', '；', ' ', '\t', '\n', '\r', '/', '|', '　'
    )

    /** 解析为标签列表：保序去重（大小写不敏感），截断超长标签，最多 MAX_TAGS 个。 */
    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val result = LinkedHashMap<String, String>() // lower -> 原始写法（保留首次出现）
        for (piece in raw.split(delimiters = SEPARATORS)) {
            val tag = piece.trim().trim('"', '\'', '“', '”', '‘', '’')
            if (tag.isEmpty()) continue
            val finalTag = if (tag.length > MAX_TAG_LENGTH) tag.take(MAX_TAG_LENGTH) else tag
            result.putIfAbsent(finalTag.lowercase(), finalTag)
            if (result.size >= MAX_TAGS) break
        }
        return result.values.toList()
    }

    /** 过滤掉已存在的标签（精确匹配，大小写不敏感），返回真正要新增的部分。 */
    fun filterExisting(incoming: List<String>, existingTags: Collection<String>): List<String> {
        if (existingTags.isEmpty()) return incoming
        val existingLower = existingTags.mapTo(HashSet()) { it.lowercase() }
        return incoming.filter { it.lowercase() !in existingLower }
    }

    /** 自动打标模型可能输出的分类前缀，如「场景：公园」「主要对象：树木」。 */
    private val CATEGORY_PREFIX = Regex(
        "^\\s*(?:" +
            "场景或地点|场景|地点|" +
            "主要对象|对象|主体|" +
            "动作或事件|动作|事件|" +
            "明显属性(?:（[^）]*）|\\([^)]*\\))?|属性|" +
            "颜色|天气|时间段|时间|" +
            "内容类别|类别|类型" +
            ")\\s*[：:]\\s*"
    )

    /** 去掉标签开头的分类前缀；没有前缀时原样返回。 */
    fun stripCategoryPrefix(raw: String): String {
        var text = raw.trim()
        // 最多剥两层，兼容「场景：主要对象：树木」这类重复前缀
        repeat(2) { text = text.replace(CATEGORY_PREFIX, "").trim() }
        return text
    }
}
