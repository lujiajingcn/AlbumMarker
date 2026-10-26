package com.albummarker

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import com.albummarker.data.TagStore
import com.albummarker.data.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AlbumMarkerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/** 简易服务定位器：进程内单例，避免引入 DI 框架。 */
// 持有的是 Application Context（不会泄漏 Activity），这是进程级单例的常规用法。
@SuppressLint("StaticFieldLeak")
object Graph {
    lateinit var tagStore: TagStore
        private set
    lateinit var modelManager: ModelManager
        private set

    fun init(context: Context) {
        if (::tagStore.isInitialized) return
        tagStore = TagStore(context.applicationContext)
        // 全表标签读取不阻塞 Application.onCreate / 首帧；UI 通过 tagsFlow 订阅结果。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // 清理旧版本自动标签里的分类前缀、提示词/思考过程噪声，再加载到内存。
            tagStore.cleanAutoTagNoise()
            tagStore.loadAll()
        }
        modelManager = ModelManager(context.applicationContext).also { it.refresh() }
    }
}
