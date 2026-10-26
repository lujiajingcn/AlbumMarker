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

    /** 单独出现（没有冒号后缀）的分类名称，例如「场景」「主要对象」。 */
    private val CATEGORY_TAG = Regex(
        "^(?:" +
            "场景或地点|场景|地点|" +
            "主要对象|对象|主体|" +
            "动作或事件|动作|事件|" +
            "明显属性(?:（[^）]*）|\\([^)]*\\))?|属性|" +
            "颜色|天气|时间段|时间|" +
            "内容类别|类别|类型" +
            ")(?:）|\\))?$"
    )

    /** 模型把提示词/思考过程当标签输出时的明显噪声。 */
    private val NOISE_EXACT = setOf(
        "好的", "好", "当然", "可以", "以下是", "标签", "图片标签助手", "图片标签",
    )

    private val NOISE_CONTAINS = listOf(
        "标签助手", "请根据", "用简体中文", "用中文列出", "图片内容", "你是一个",
        "图片展示", "图中展示", "画面中", "其中包含", "提示词", "分类名称",
        "只输出", "不要输出", "<think", "</think",
    )

    /** 去掉标签开头的分类前缀；没有前缀时原样返回。 */
    fun stripCategoryPrefix(raw: String): String {
        var text = raw.trim()
        // 最多剥两层，兼容「场景：主要对象：树木」这类重复前缀
        repeat(2) { text = text.replace(CATEGORY_PREFIX, "").trim() }
        return text
    }

    /**
     * 是否是模型提示词/思考过程/分类名词等噪声。
     * 仅用于自动打标结果，手工标签不做过滤。
     */
    fun isAutoTagNoise(raw: String): Boolean {
        val tag = raw.trim()
        if (tag.isEmpty()) return true
        if (tag.length > MAX_TAG_LENGTH) return true
        if (CATEGORY_TAG.matches(tag)) return true
        if (tag in NOISE_EXACT) return true
        if (NOISE_CONTAINS.any { tag.contains(it, ignoreCase = true) }) return true
        return false
    }
}
