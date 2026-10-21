package com.albummarker.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * 需求 5：为图片/视频计算 SHA-256 哈希值，以哈希而非 Uri 作为标签的稳定键
 * （文件被移动/重命名后仍能对上，同一文件的不同 Uri 视图也归一）。
 */
object MediaHasher {

    const val ALGORITHM = "SHA-256"
    private const val BUFFER_SIZE = 1 shl 20 // 1 MiB

    /** 打开 Uri 对应内容并流式哈希；大视频也不会整文件进内存。 */
    suspend fun hashOf(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        // MediaStreams.open 会优先 ContentResolver，失败（含 Android 12 redaction 的
        // IllegalStateException）时回退文件直读；彻底打不开返回 null，归一成 IOException。
        val stream = MediaStreams.open(context, uri)
            ?: throw IOException("无法打开媒体流: $uri")
        stream.use { hashStream(it) }
    }

    /**
     * 带缓存的哈希：命中 (uri, size, date_modified) 缓存则免算，否则算后回填。
     * 查看器与批量写标签共用；文件内容变化后 size/date 会变，缓存自然失效
     * （或被 [TagStore.invalidateHashCache] 显式清除）。
     */
    suspend fun hashOfCached(context: Context, store: TagStore, media: MediaItem): String {
        val uri = media.uri.toString()
        store.cachedHash(uri, media.sizeBytes, media.dateModifiedSec)?.let { return it }
        val hash = hashOf(context, media.uri)
        store.putCachedHash(uri, media.sizeBytes, media.dateModifiedSec, hash)
        return hash
    }

    /** 纯函数：对输入流做 SHA-256，返回小写十六进制串。 */
    fun hashStream(input: InputStream): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(BUFFER_SIZE)
        var read = input.read(buffer)
        while (read != -1) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
        return digest.digest().toHexLower()
    }

    /** 供单测/调试用：直接哈希字节数组。 */
    fun hashBytes(bytes: ByteArray): String =
        MessageDigest.getInstance(ALGORITHM).digest(bytes).toHexLower()

    private fun ByteArray.toHexLower(): String {
        val hex = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val v = byte.toInt() and 0xFF
            out.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return out.toString()
    }
}
