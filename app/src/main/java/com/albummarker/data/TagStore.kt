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

    /** 应用启动时调用一次，把全量标签读进内存。 */
    fun loadAll() {
        val map = mutableMapOf<String, MutableList<TagEntry>>()
        readableDatabase.rawQuery(
            "SELECT hash, tag, source, created_at FROM tags ORDER BY created_at ASC",
            null
        ).use { c ->
            val iHash = c.getColumnIndexOrThrow("hash")
            val iTag = c.getColumnIndexOrThrow("tag")
            val iSource = c.getColumnIndexOrThrow("source")
            val iCreated = c.getColumnIndexOrThrow("created_at")
            while (c.moveToNext()) {
                val hash = c.getString(iHash)
                map.getOrPut(hash) { mutableListOf() }
                    .add(TagEntry(c.getString(iTag), c.getString(iSource), c.getLong(iCreated)))
            }
        }
        _tagsFlow.value = map
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
        val current = _tagsFlow.value
        val mutable = current.toMutableMap()
        if (list.isEmpty()) mutable.remove(hash) else mutable[hash] = list
        _tagsFlow.value = mutable
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
            db.execSQL("UPDATE OR IGNORE $TABLE_TAGS SET hash=? WHERE hash=?", arrayOf(newHash, oldHash))
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
