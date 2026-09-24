package com.mo.fakeloc.xposed

import android.app.Application
import android.content.Context
import com.mo.fakeloc.xposed.hooks.FusedLocationHook
import com.mo.fakeloc.xposed.hooks.LocationManagerHook
import com.mo.fakeloc.xposed.hooks.SystemServerHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * LSPosed 模块入口。
 *
 * `assets/xposed_init` 里写的就是这个类的全限定名，由框架按名字加载。
 * 同时实现 [IXposedHookZygoteInit]（拿 modulePath）和 [IXposedHookLoadPackage]（干正事）。
 *
 * ## 注入策略
 * | 进程 | 挂什么 |
 * |------|--------|
 * | `android`（system_server） | [SystemServerHook] —— 从源头改定位 |
 * | 任意应用进程 | [LocationManagerHook] —— 覆盖面最广的一层 |
 * | 任意进程（若有 GMS） | [FusedLocationHook] —— 融合定位兜底 |
 *
 * 所有进程都会启动 [ConfigBridge]，让 hook 能拿到 App 下发的配置；
 * 同时把「本进程挂了哪些 hook」登记进去，随配置请求一起回报给 App。
 */
class XposedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        try {
            modulePath = startupParam?.modulePath
            HookLog.i("zygote init: modulePath=$modulePath")
        } catch (t: Throwable) {
            HookLog.e("initZygote failed", t)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        val pkg = lpparam?.packageName ?: return
        val process = lpparam.processName ?: pkg
        val cl = lpparam.classLoader ?: return

        // 不给自己注入（我们的 App 不需要虚拟定位，也避免 hook 自身 UI）。
        // 注意要同时判 process：App 里跑 WebView 时，LSPosed 可能按
        // com.google.android.webview 这个作用域条目匹配进来，此时 packageName 是
        // WebView 的包名、processName 才是我们自己的 —— 只判 pkg 会漏掉。
        if (pkg == SELF_PACKAGE || pkg == SELF_PACKAGE_DEBUG) return
        if (process == SELF_PACKAGE || process == SELF_PACKAGE_DEBUG) return

        // 同一进程只初始化一次
        if (INITIALIZED.putIfAbsent(process, true) != null) return

        try {
            // 0) 先登记身份 —— 回执里要带出去，越早越好
            ConfigBridge.setProcess(pkg, process)

            // 1) 配置桥：后台线程按需拉取 App 下发的配置
            ConfigBridge.start()

            // 2) 尽早拿到 Context，供 Provider 通道使用
            hookApplicationContext(cl)

            if (pkg == PACKAGE_ANDROID) {
                // system_server：直接注入系统 Context
                ConfigBridge.attachContextProvider { systemContext() }
                val n = SystemServerHook.install(cl)
                ConfigBridge.markHook("SS:$n")
                HookLog.i(">>> hooked SYSTEM_SERVER process=$process hooks=$n")
            } else {
                val n = LocationManagerHook.install(cl)
                ConfigBridge.markHook("LM:$n")
                HookLog.i(">>> hooked pkg=$pkg process=$process hooks=$n")
            }

            // 3) GMS 融合定位：类不存在会自动跳过
            val nf = FusedLocationHook.install(cl)
            if (nf > 0) ConfigBridge.markHook("Fused:$nf")

        } catch (t: Throwable) {
            HookLog.e("handleLoadPackage($pkg) failed: ${t.message}", t)
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 挂 `Instrumentation.callApplicationOnCreate`，在 Application 创建后
     * 立刻把 Context 交给 [ConfigBridge]。
     *
     * 不用 `AndroidAppHelper.currentApplication()` 是因为它依赖内部缓存，
     * 在部分 ROM 上拿到的时间点偏晚；直接挂钩更确定。
     */
    private fun hookApplicationContext(cl: ClassLoader) {
        try {
            val inst = XposedHelpers.findClassIfExists("android.app.Instrumentation", cl) ?: return
            XposedHelpers.findAndHookMethod(
                inst, "callApplicationOnCreate", Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.args[0] as? Application ?: return
                        ConfigBridge.attachContextProvider { app }
                        HookLog.i("app context attached: ${app.packageName}")
                    }
                }
            )
        } catch (t: Throwable) {
            HookLog.v("hookApplicationContext skipped: ${t.message}")
        }
    }

    /** 取 system_server 的 SystemContext。 */
    private fun systemContext(): Context? = try {
        val atClz = Class.forName("android.app.ActivityThread")
        val at = atClz.getMethod("currentActivityThread").invoke(null)
        if (at == null) null
        else atClz.getMethod("getSystemContext").invoke(at) as? Context
    } catch (t: Throwable) {
        null
    }

    companion object {
        const val PACKAGE_ANDROID = "android"
        const val SELF_PACKAGE = "com.mo.fakeloc"
        const val SELF_PACKAGE_DEBUG = "com.mo.fakeloc.debug"

        /** 模块 APK 路径，zygote 阶段拿到。 */
        @Volatile
        var modulePath: String? = null
            private set

        private val INITIALIZED = ConcurrentHashMap<String, Boolean>()
    }
}
