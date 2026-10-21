package com.albummarker.data.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 需求 6：Qwen3.5-0.8B 本地模型的安装与管理。
 *
 * - 模型保存于应用私有目录 files/models/（无需额外存储权限）；
 * - 支持两条安装路径：SAF 文件导入、URL 直链下载（.part 落盘后原子改名）；
 * - 状态通过 StateFlow 暴露：未安装 / 已就绪 / 异常。
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val PREFS = "model_manager"
        private const val KEY_PATH = "active_model_path"
        private const val KEY_NAME = "active_model_name"

        /** MediaPipe LLM Inference 可加载的模型后缀。 */
        val SUPPORTED_EXTENSIONS = listOf(".task", ".litert", ".tflite", ".bin")

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 1 shl 16
    }

    sealed class ModelState {
        object NotInstalled : ModelState()
        data class Ready(val file: File, val sizeBytes: Long) : ModelState()
        data class Broken(val message: String) : ModelState()
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _state = MutableStateFlow<ModelState>(ModelState.NotInstalled)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** 当前激活的模型文件；未安装返回 null。 */
    val activeModelPath: String?
        get() = prefs.getString(KEY_PATH, null)?.takeIf { path ->
            val f = File(path)
            f.isFile && f.length() > 0 && f.canRead()
        }

    fun refresh() {
        val path = prefs.getString(KEY_PATH, null)
        if (path == null) {
            _state.value = ModelState.NotInstalled
            return
        }
        val file = File(path)
        _state.value = when {
            !file.exists() -> ModelState.Broken("模型文件不存在: ${file.name}")
            file.length() <= 0 -> ModelState.Broken("模型文件为空: ${file.name}")
            !file.canRead() -> ModelState.Broken("模型文件不可读: ${file.name}")
            else -> ModelState.Ready(file, file.length())
        }
    }

    /** 从 SAF 选中的 Uri 复制模型文件到私有目录。 */
    suspend fun importFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(uri) ?: "model_${System.currentTimeMillis()}"
            if (SUPPORTED_EXTENSIONS.none { displayName.endsWith(it, ignoreCase = true) }) {
                throw IllegalArgumentException(
                    "不支持的文件格式（需要 ${SUPPORTED_EXTENSIONS.joinToString(" / ")}）: $displayName"
                )
            }
            val target = File(modelsDir, sanitizeName(displayName))
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, BUFFER_SIZE) }
            } ?: throw IllegalStateException("无法读取所选文件")
            activate(target)
            target
        }
    }

    /** 从 URL 下载模型（.part 临时文件 + 原子改名），onProgress 回调 0-100。 */
    suspend fun downloadFromUrl(
        url: String,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
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
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                            }
                            read = input.read(buffer)
                        }
                    }
                }
                if (!part.renameTo(target)) {
                    // 极端情况下 rename 失败：拷贝后删除 part
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
            } finally {
                connection.disconnect()
            }
            if (target.length() <= 0) throw IllegalStateException("下载得到空文件")
            activate(target)
            target
        }
    }

    /** 删除已安装模型（含 .part 残留）。 */
    fun deleteModel() {
        val path = prefs.getString(KEY_PATH, null)
        if (path != null) runCatching { File(path).delete() }
        modelsDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".part")) runCatching { f.delete() }
        }
        prefs.edit { remove(KEY_PATH); remove(KEY_NAME) }
        refresh()
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
