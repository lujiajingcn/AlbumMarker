package com.albummarker.data.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地 LiteRT-LM 模型管理（支持多个 `.litertlm` 共存）。
 *
 * - 模型保存在应用私有目录 files/models/，无需额外存储权限；
 * - 可同时安装 Qwen3.5-0.8B-VL、Qwen3.5-2B-VL 等，记录一个「当前模型」；
 * - 自动打标时如果安装了多个模型，会让用户选择；
 * - 支持 SAF 导入、URL 下载（.part 落盘后原子改名）、按模型删除。
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val PREFS = "model_manager"
        private const val KEY_PATH = "active_model_path"
        private const val KEY_NAME = "active_model_name"

        /** LiteRT-LM 可加载的模型后缀。 */
        val SUPPORTED_EXTENSIONS = listOf(".litertlm")

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 1 shl 16
    }

    /** 已安装的模型文件。 */
    data class InstalledModel(val file: File) {
        val sizeBytes: Long get() = file.length()

        /** 去掉后缀、下划线换成空格的展示名，如 `Qwen3.5-2B-VL`。 */
        val displayName: String
            get() = file.name
                .removeSuffix(".litertlm")
                .replace('_', ' ')
                .trim()
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _models = MutableStateFlow<List<InstalledModel>>(emptyList())

    /** 已安装模型列表（按文件名排序）。 */
    val models: StateFlow<List<InstalledModel>> = _models.asStateFlow()

    /** 当前激活的模型文件；未安装或仍是旧格式时返回 null。 */
    val activeModelPath: String?
        get() = prefs.getString(KEY_PATH, null)?.takeIf { path ->
            path.endsWith(".litertlm", ignoreCase = true) &&
                File(path).let { it.isFile && it.length() > 0 && it.canRead() }
        }

    val activeModel: InstalledModel?
        get() = activeModelPath?.let { InstalledModel(File(it)) }

    init {
        refresh()
    }

    /** 重新扫描 models/ 目录，校正当前模型（失效时自动选第一个）。 */
    fun refresh() {
        val installed = modelsDir.listFiles()
            ?.filter {
                it.isFile &&
                    it.name.endsWith(".litertlm", ignoreCase = true) &&
                    it.length() > 0 &&
                    it.canRead()
            }
            ?.sortedBy { it.name.lowercase() }
            ?.map { InstalledModel(it) }
            .orEmpty()

        val savedPath = prefs.getString(KEY_PATH, null)
        val validSaved = installed.firstOrNull { it.file.absolutePath == savedPath }?.file?.absolutePath
        val activePath = validSaved ?: installed.firstOrNull()?.file?.absolutePath

        if (activePath != savedPath) {
            prefs.edit {
                if (activePath == null) {
                    remove(KEY_PATH)
                    remove(KEY_NAME)
                } else {
                    putString(KEY_PATH, activePath)
                    putString(KEY_NAME, File(activePath).name)
                }
            }
        }

        _models.value = installed
    }

    /** 切换当前模型（自动打标的默认选项）。 */
    fun setActive(model: InstalledModel) {
        if (!model.file.isFile || model.file.length() <= 0) {
            refresh()
            return
        }
        prefs.edit {
            putString(KEY_PATH, model.file.absolutePath)
            putString(KEY_NAME, model.file.name)
        }
        refresh()
    }

    /** 从 SAF 选中的 Uri 复制模型文件到私有目录（先写 .part，成功后原子改名）。 */
    suspend fun importFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        try {
            val displayName = queryDisplayName(uri) ?: "model_${System.currentTimeMillis()}"
            if (SUPPORTED_EXTENSIONS.none { displayName.endsWith(it, ignoreCase = true) }) {
                throw IllegalArgumentException(
                    "不支持的文件格式（需要 ${SUPPORTED_EXTENSIONS.joinToString(" / ")}）: $displayName"
                )
            }
            val target = File(modelsDir, sanitizeName(displayName))
            val part = File(target.absolutePath + ".part")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw IllegalStateException("无法读取所选文件")
                if (part.length() <= 0) throw IllegalStateException("导入得到空文件")
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
            } catch (t: Throwable) {
                part.delete()
                throw t
            }
            activate(target)
            Result.success(target)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(t)
        }
    }

    /** 从 URL 下载模型（.part 临时文件 + 原子改名），onProgress 回调 0-100。 */
    suspend fun downloadFromUrl(
        url: String,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fileName = fileNameFromUrl(url)
                ?: throw IllegalArgumentException("无法从链接解析文件名，请检查 URL")
            if (SUPPORTED_EXTENSIONS.none { fileName.endsWith(it, ignoreCase = true) }) {
                throw IllegalArgumentException(
                    "链接文件名必须以 ${SUPPORTED_EXTENSIONS.joinToString(" / ")} 结尾: $fileName"
                )
            }
            val target = File(modelsDir, sanitizeName(fileName))
            val part = File(target.absolutePath + ".part")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val total = connection.contentLengthLong
                var downloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read = input.read(buffer)
                        while (read != -1) {
                            currentCoroutineContext().ensureActive()
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                            }
                            read = input.read(buffer)
                        }
                    }
                }
                if (total > 0 && downloaded != total) {
                    throw IllegalStateException("下载不完整：$downloaded / $total 字节")
                }
                if (!part.renameTo(target)) {
                    // 极端情况下 rename 失败：拷贝后删除 part
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
            } catch (t: Throwable) {
                part.delete()
                throw t
            } finally {
                connection.disconnect()
            }
            if (target.length() <= 0) throw IllegalStateException("下载得到空文件")
            activate(target)
            Result.success(target)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.failure(t)
        }
    }

    /** 删除指定模型；如果删的是当前模型，自动切换到另一个。 */
    fun deleteModel(model: InstalledModel) {
        runCatching { model.file.delete() }
        // 同时清掉该模型首次加载生成的 XNNPACK 权重缓存（可能数 GB）
        runCatching {
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith(model.file.name) }
                ?.forEach { it.delete() }
        }
        if (prefs.getString(KEY_PATH, null) == model.file.absolutePath) {
            prefs.edit {
                remove(KEY_PATH)
                remove(KEY_NAME)
            }
        }
        refresh()
    }

    /** 删除当前模型（兼容旧入口）。 */
    fun deleteModel() {
        val active = activeModel
        if (active != null) deleteModel(active) else refresh()
    }

    private fun activate(file: File) {
        prefs.edit {
            putString(KEY_PATH, file.absolutePath)
            putString(KEY_NAME, file.name)
        }
        refresh()
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !c.isNull(index)) return c.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun fileNameFromUrl(url: String): String? {
        val path = runCatching { URL(url).path }.getOrNull() ?: return null
        val name = path.substringAfterLast('/')
        return name.takeIf { it.isNotBlank() }
    }

    private fun sanitizeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._\\-]"), "_").take(120)
}

/** SAF 选模型文件的 Intent 构造。 */
fun buildModelPickerIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }
