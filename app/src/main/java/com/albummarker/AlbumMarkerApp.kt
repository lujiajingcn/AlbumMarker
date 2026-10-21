package com.albummarker

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import com.albummarker.data.TagStore
import com.albummarker.data.model.ModelManager

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
        tagStore = TagStore(context.applicationContext).also { it.loadAll() }
        modelManager = ModelManager(context.applicationContext).also { it.refresh() }
    }
}
