package com.albummarker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一条标签记录：标签文本 + 来源（手动/自动）+ 入库时间。 */
data class TagEntry(
    val tag: String,
    val source: String,
    val createdAt: Long,
) {
    val isAuto: Boolean get() = source != SOURCE_MANUAL

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_AUTO = "auto"
        const val SOURCE_HEURISTIC = "heuristic"
    }
}

/** 一条带标签的媒体（[TagStore.allTagged] 的返回行，批量写标签用）。 */
data class TaggedMedia(
    val hash: String,
    val uri: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long,
    val tags: List<String>,
)

/**
 * 需求 5 的存储侧：以文件哈希为主键保存标签。
 *
 * 表结构：
 *  - media_tags：哈希 -> 媒体元信息（便于按文件反查）
 *  - tags：哈希 -> 标签（多对一），(hash, tag) 主键去重
 *  - hash_cache：Uri + (size, date_modified) -> 哈希，避免重复计算大文件哈希
 *
 * 内存里持有一份全量 Map 并通过 StateFlow 暴露，UI 直接订阅；
 * 写操作先落库再更新 Flow，保持最终一致。
 */
class TagStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "album_marker.db"
        const val DB_VERSION = 1

        private const val TABLE_MEDIA = "media_tags"
        private const val TABLE_TAGS = "tags"
        private const val TABLE_HASH_CACHE = "hash_cache"
    }

    private val _tagsFlow = MutableStateFlow<Map<String, List<TagEntry>>>(emptyMap())
    val tagsFlow: StateFlow<Map<String, List<TagEntry>>> = _tagsFlow.asStateFlow()

    /**
     * 哈希 -> 媒体 Uri（打标签那一刻的 Uri）。
     *
     * 标签以哈希为键、而首页网格的媒体以 Uri 为键，这个映射把两者接上。
     * 与 [_tagsFlow] 一起在 [loadAll] 时读入内存，写操作同步维护。
     */
    private val uriByHash = mutableMapOf<String, String>()

    /** 保护 uriByHash 与 _tagsFlow 的读改写，避免批量写与查看器同时更新时丢标签。 */
    private val mapLock = Any()

    /** 应用启动时调用一次，把全量标签读进内存。 */
    fun loadAll() {
        val map = mutableMapOf<String, MutableList<TagEntry>>()
        val uris = mutableMapOf<String, String>()
        // LEFT JOIN：tags 表可能有行没有对应的 media_tags 行（加标签时没带媒体信息），
        // 这类标签仍要进 _tagsFlow；media_tags.uri 取不到时留空即可。
        readableDatabase.rawQuery(
            """
            SELECT t.hash, t.tag, t.source, t.created_at, m.uri
            FROM $TABLE_TAGS t
            LEFT JOIN $TABLE_MEDIA m ON m.hash = t.hash
            ORDER BY t.created_at ASC
            """.trimIndent(),
            null
        ).use { c ->
            val iHash = c.getColumnIndexOrThrow("hash")
            val iTag = c.getColumnIndexOrThrow("tag")
            val iSource = c.getColumnIndexOrThrow("source")
            val iCreated = c.getColumnIndexOrThrow("created_at")
            val iUri = c.getColumnIndexOrThrow("uri")
            while (c.moveToNext()) {
                val hash = c.getString(iHash)
                map.getOrPut(hash) { mutableListOf() }
                    .add(TagEntry(c.getString(iTag), c.getString(iSource), c.getLong(iCreated)))
                if (!c.isNull(iUri)) uris.putIfAbsent(hash, c.getString(iUri))
            }
        }
        synchronized(mapLock) {
            uriByHash.clear()
            uriByHash.putAll(uris)
            _tagsFlow.value = map
        }
    }

    fun tagsFor(hash: String): List<TagEntry> = _tagsFlow.value[hash].orEmpty()

    /**
     * 添加标签。返回实际新增的条数（已存在的会被忽略）。
     * @param media 可选，写入 media_tags 元信息行
     */
    fun addTags(
        hash: String,
        tags: List<String>,
        source: String,
        media: MediaItem? = null,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (tags.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            if (media != null) {
                val values = ContentValues().apply {
                    put("hash", hash)
                    put("uri", media.uri.toString())
                    put("display_name", media.displayName)
                    put("mime", media.mimeType)
                    put("size", media.sizeBytes)
                    put("date_modified", media.dateModifiedSec)
                    put("updated_at", now)
                }
                db.insertWithOnConflict(TABLE_MEDIA, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (tag in tags) {
                val values = ContentValues().apply {
                    put("hash", hash)
                    put("tag", tag)
                    put("source", source)
                    put("created_at", now)
                }
                try {
                    db.insertOrThrow(TABLE_TAGS, null, values)
                    inserted++
                } catch (_: SQLiteConstraintException) {
                    // (hash, tag) 已存在，跳过
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (inserted > 0) refreshHash(hash)
        if (media != null) {
            synchronized(mapLock) { uriByHash[hash] = media.uri.toString() }
        }
        return inserted
    }

    /** 删除单个标签；若该哈希下已无标签，连同 media_tags 行一起清理。 */
    fun removeTag(hash: String, tag: String) {
        val db = writableDatabase
        db.delete(TABLE_TAGS, "hash = ? AND tag = ?", arrayOf(hash, tag))
        val remaining = db.rawQuery(
            "SELECT COUNT(*) FROM tags WHERE hash = ?", arrayOf(hash)
        ).use { it.moveToFirst() && it.getInt(0) > 0 }
        if (!remaining) {
            db.delete(TABLE_MEDIA, "hash = ?", arrayOf(hash))
            synchronized(mapLock) { uriByHash.remove(hash) }
        }
        refreshHash(hash)
    }

    /** 重新读取某个哈希的标签并更新 Flow。 */
    private fun refreshHash(hash: String) {
        val list = mutableListOf<TagEntry>()
        readableDatabase.rawQuery(
            "SELECT tag, source, created_at FROM tags WHERE hash = ? ORDER BY created_at ASC",
            arrayOf(hash)
        ).use { c ->
            val iTag = c.getColumnIndexOrThrow("tag")
            val iSource = c.getColumnIndexOrThrow("source")
            val iCreated = c.getColumnIndexOrThrow("created_at")
            while (c.moveToNext()) {
                list.add(TagEntry(c.getString(iTag), c.getString(iSource), c.getLong(iCreated)))
            }
        }
        synchronized(mapLock) {
            val current = _tagsFlow.value
            val mutable = current.toMutableMap()
            if (list.isEmpty()) mutable.remove(hash) else mutable[hash] = list
            _tagsFlow.value = mutable
        }
    }

    // ---- hash_cache：避免对大视频反复做全量 SHA-256 ----

    /**
     * 标签键迁移：[TagEmbedder] 把标签写进文件本体后，文件字节变化 -> SHA-256 变化，
     * 必须把标签行从旧哈希整体搬到新哈希，否则界面上的 chips 会全部消失。
     *
     * 三张表一起搬（tags / media_tags / hash_cache），单事务；
     * 新哈希下已存在的同名标签（PK 冲突）保留原行、跳过迁移行。
     * @param newSize 可选，写入后的文件大小（刷新 media_tags 里的旧体积）
     */
    fun migrateHash(oldHash: String, newHash: String, newSize: Long? = null) {
        if (oldHash == newHash) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 先插入到新哈希（新哈希已有同名标签时保留原行），再删除旧哈希的所有行。
            // 直接用 UPDATE OR IGNORE 会在冲突时把旧行留在旧哈希下，形成幽灵标签。
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE_TAGS (hash, tag, source, created_at) " +
                    "SELECT ?, tag, source, created_at FROM $TABLE_TAGS WHERE hash = ?",
                arrayOf(newHash, oldHash)
            )
            db.execSQL("DELETE FROM $TABLE_TAGS WHERE hash = ?", arrayOf(oldHash))
            db.execSQL("UPDATE OR REPLACE $TABLE_MEDIA SET hash=? WHERE hash=?", arrayOf(newHash, oldHash))
            db.execSQL("UPDATE OR REPLACE $TABLE_HASH_CACHE SET hash=? WHERE hash=?", arrayOf(newHash, oldHash))
            if (newSize != null) {
                db.execSQL("UPDATE $TABLE_MEDIA SET size=? WHERE hash=?", arrayOf<Any?>(newSize, newHash))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshHash(oldHash)
        refreshHash(newHash)
        // 内存映射跟着搬家：标签行换到新哈希，Uri 归属不变
        synchronized(mapLock) {
            uriByHash.remove(oldHash)?.let { uriByHash[newHash] = it }
        }
    }

    /** 使某个 Uri 的哈希缓存失效（文件内容被本应用改写后调用，下次查询重新计算）。 */
    fun invalidateHashCache(uri: String) {
        writableDatabase.delete(TABLE_HASH_CACHE, "uri = ?", arrayOf(uri))
    }

    /** 全量带标签的媒体（批量写标签用；直接读库，无需对整相册重新算哈希）。 */
    fun allTagged(): List<TaggedMedia> {
        val out = mutableListOf<TaggedMedia>()
        readableDatabase.rawQuery(
            """
            SELECT m.hash, m.uri, m.display_name, m.mime, m.size,
                   group_concat(t.tag, '; ') AS tags
            FROM $TABLE_MEDIA m JOIN tags t ON t.hash = m.hash
            GROUP BY m.hash
            ORDER BY m.display_name
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TaggedMedia(
                        hash = c.getString(0),
                        uri = c.getString(1),
                        displayName = c.getString(2) ?: "",
                        mime = c.getString(3) ?: "",
                        sizeBytes = c.getLong(4),
                        // 标签不可能含 ';'（TagParser 以分号为分隔符），可安全拆分
                        tags = (c.getString(5) ?: "").split(";")
                            .map { it.trim() }.filter { it.isNotEmpty() },
                    )
                )
            }
        }
        return out
    }

    /**
     * 查询缓存哈希。仅当 size 与 date_modified 都与传入值一致时才视为有效
     * （文件被修改后这两个值会变，缓存自然失效）。
     */
    fun cachedHash(uri: String, size: Long, dateModified: Long): String? {
        readableDatabase.rawQuery(
            "SELECT hash FROM $TABLE_HASH_CACHE WHERE uri = ? AND size = ? AND date_modified = ?",
            arrayOf(uri.toString(), size.toString(), dateModified.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun putCachedHash(uri: String, size: Long, dateModified: Long, hash: String) {
        val values = ContentValues().apply {
            put("uri", uri)
            put("size", size)
            put("date_modified", dateModified)
            put("hash", hash)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_HASH_CACHE, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * 媒体 Uri -> 标签列表（首页网格浮层用）。
     *
     * 直接由内存态推导（[tagsFlow] 的哈希 -> 标签 + 哈希 -> Uri），
     * 不再查库：切换「显示标签」是纯内存操作，代价与相册总量无关。
     *
     * 已知局限：Uri 是打标签那一刻的，文件被改名/移动后这里对不上
     * （查看器按内容哈希算，仍能查到标签）。
     */
    fun tagsByUri(): Map<String, List<TagEntry>> {
        val out = mutableMapOf<String, MutableList<TagEntry>>()
        val tagsSnapshot = _tagsFlow.value
        val uriSnapshot = synchronized(mapLock) { uriByHash.toMap() }
        for ((hash, entries) in tagsSnapshot) {
            val uri = uriSnapshot[hash] ?: continue
            out.getOrPut(uri) { mutableListOf() }.addAll(entries)
        }
        return out
    }

    /**
     * 按标签模糊搜索（空格 / 逗号分隔多个关键词，任一命中即返回），
     * 返回匹配媒体在 media_tags 中记录的 Uri 字符串集合。
     */
    fun searchMediaUris(query: String): Set<String> {
        val tokens = query.trim()
            .split(' ', '\t', '\n', ',', '，', '、')
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptySet()

        val result = mutableSetOf<String>()
        val db = readableDatabase
        val memoryTags = _tagsFlow.value
        val memoryUris = synchronized(mapLock) { uriByHash.toMap() }
        for (token in tokens) {
            val escaped = token
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            db.rawQuery(
                "SELECT DISTINCT m.uri FROM $TABLE_TAGS t " +
                    "JOIN $TABLE_MEDIA m ON m.hash = t.hash " +
                    "WHERE t.tag LIKE ? ESCAPE '\\'",
                arrayOf("%$escaped%"),
            ).use { c ->
                while (c.moveToNext()) result += c.getString(0)
            }
            // 有些历史标签没有 media_tags 行（loadAll 的 LEFT JOIN 注释），
            // 这些标签也要能在内存映射里被搜到。
            for ((hash, entries) in memoryTags) {
                if (entries.any { it.tag.contains(token, ignoreCase = true) }) {
                    memoryUris[hash]?.let { result += it }
                }
            }
        }
        return result
    }

    /**
     * 清理历史自动标签里的分类前缀（如「场景：公园」→「公园」）。
     * 只处理 source=auto，手动标签保持原样；冲突时保留已存在的干净标签。
     * @return 被修改/删除的行数
     */
    fun cleanAutoTagNoise(): Int {
        val rows = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT hash, tag FROM $TABLE_TAGS WHERE source = ?",
            arrayOf(TagEntry.SOURCE_AUTO)
        ).use { c ->
            while (c.moveToNext()) rows.add(c.getString(0) to c.getString(1))
        }
        if (rows.isEmpty()) return 0

        val db = writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            for ((hash, tag) in rows) {
                val clean = TagParser.stripCategoryPrefix(tag)
                val noisy = clean.isEmpty() || TagParser.isAutoTagNoise(clean)
                if (!noisy && clean == tag) continue
                val alreadyExists = !noisy && db.rawQuery(
                    "SELECT 1 FROM $TABLE_TAGS WHERE hash = ? AND tag = ?",
                    arrayOf(hash, clean)
                ).use { it.moveToFirst() }
                if (noisy || alreadyExists) {
                    db.delete(TABLE_TAGS, "hash = ? AND tag = ?", arrayOf(hash, tag))
                } else {
                    val values = ContentValues().apply { put("tag", clean) }
                    db.update(TABLE_TAGS, values, "hash = ? AND tag = ?", arrayOf(hash, tag))
                }
                changed++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_MEDIA (
                hash TEXT PRIMARY KEY,
                uri TEXT NOT NULL,
                display_name TEXT,
                mime TEXT,
                size INTEGER,
                date_modified INTEGER,
                updated_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_TAGS (
                hash TEXT NOT NULL,
                tag TEXT NOT NULL,
                source TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (hash, tag)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_HASH_CACHE (
                uri TEXT PRIMARY KEY,
                size INTEGER,
                date_modified INTEGER,
                hash TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 目前只有一个版本；后续在此做迁移。
    }
}
