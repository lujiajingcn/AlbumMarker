package com.albummarker.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 统一取媒体字节流的入口。
 *
 * 优先走 [android.content.ContentResolver.openInputStream]。但在 Android 12(SDK 31) 上，
 * 当 MediaProvider 的「位置脱敏(redaction)」失败时，openInputStream 会抛
 * IllegalStateException("java.io.IOException: Failed to redact ...")（真机红米 K30 Pro 实测），
 * 导致查看器黑屏、无法算哈希、无法打标。
 *
 * 此时回退方案：解析 MediaStore 记录里的真实文件路径（DATA 列），用 [File] 直读绕过
 * MediaProvider。仅 Android 12L(SDK 32) 及以下、且持有 READ_EXTERNAL_STORAGE 时可行
 * （33+ 强制分区存储，File 直读他人媒体会被拒）。
 */
object MediaStreams {

    private const val TAG = "AlbumMarker"

    /**
     * 打开媒体流；ContentResolver 失败时回退文件直读。彻底打不开返回 null。
     * 调用方负责 close()（建议 `use { }`）。
     */
    fun open(context: Context, uri: Uri): InputStream? {
        try {
            context.contentResolver.openInputStream(uri)?.let { return it }
        } catch (e: Exception) {
            // 涵盖 IOException 与 Android 12 redaction 的 IllegalStateException。
            Log.w(
                TAG,
                "openInputStream 失败(${e.javaClass.simpleName}: ${e.message})，尝试文件直读回退: $uri"
            )
        }
        return file(context, uri)?.let { f ->
            try {
                if (f.canRead()) FileInputStream(f) else null
            } catch (e: Exception) {
                Log.w(TAG, "文件直读回退失败: ${f.path}", e)
                null
            }
        }
    }

    /**
     * 解析可直读的真实文件（供 Coil `load(File)` 等使用）。
     * 仅在 SDK ≤ 32 且文件存在可读时返回，否则 null。
     */
    fun file(context: Context, uri: Uri): File? {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) return null
        val path = queryDataPath(context, uri) ?: return null
        return try {
            val f = File(path)
            if (f.exists() && f.canRead()) f else null
        } catch (e: Exception) {
            null
        }
    }

    private fun queryDataPath(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null, null, null
        )?.use { c ->
            val i = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getString(i) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "查询 DATA 路径失败: $uri", e)
        null
    }
}
