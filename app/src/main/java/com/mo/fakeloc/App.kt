package com.mo.fakeloc

import android.app.Application
import com.mo.fakeloc.data.ConfigStore
import com.mo.fakeloc.root.RootHelper

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 首次启动就把默认配置落盘。
        // XSharedPreferences 兜底通道读的是 prefs 文件，文件不存在 = 通道失效。
        // 这一步保证用户哪怕一次都没点过「应用坐标」，hook 也能读到一份合法配置。
        try {
            ConfigStore.ensureInitialized(this)
        } catch (_: Throwable) {
            // 落盘失败不阻塞启动，ContentProvider 通道仍然可用
        }

        // 后台把配置写一份到 /data/local/tmp（世界可读），并发布到 Settings.Global，
        // 给 hook 当兜底 / 中继通道。都需要 root；没有 root 时安静跳过。
        Thread {
            try {
                val json = ConfigStore.load(this).toJson()
                RootHelper.backupConfig(this, json)
                RootHelper.publishRelay(json)
            } catch (_: Throwable) {
            }
        }.start()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
