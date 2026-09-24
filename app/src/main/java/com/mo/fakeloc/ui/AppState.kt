package com.mo.fakeloc.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mo.fakeloc.data.ConfigStore
import com.mo.fakeloc.data.HookReport
import com.mo.fakeloc.data.HookReportRegistry
import com.mo.fakeloc.data.LocConfig
import com.mo.fakeloc.root.RootHelper
import com.mo.fakeloc.root.RootShell
import com.mo.fakeloc.service.FakeLocationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 界面共享状态。
 *
 * 之前所有状态都堆在 `MainScreen` 的局部变量里，加侧边栏分页之后必须提到外面，
 * 否则切页会丢状态、或者要往下传几十个参数。
 *
 * 刻意不用 ViewModel：这里的生命周期就是 Activity 本身，没有跨配置变更保持的需求，
 * 一个 `remember` 出来的普通类更直白。
 */
class AppState(
    private val ctx: Context,
    private val scope: CoroutineScope
) {

    // ---------------------------------------------------------------- 配置

    var cfg by mutableStateOf(ConfigStore.load(ctx))

    var latText by mutableStateOf(fmt6(cfg.latitude))
    var lonText by mutableStateOf(fmt6(cfg.longitude))
    var altText by mutableStateOf(fmt1(cfg.altitude))

    var favorites by mutableStateOf(ConfigStore.loadFavorites(ctx))
    var route by mutableStateOf(ConfigStore.loadRoute(ctx))

    /** 路线配速，单位 **min/km**（跑步界通用说法）。 */
    var routePace by mutableStateOf(ConfigStore.routePaceMinPerKm(ctx))
    var routeLoop by mutableStateOf(ConfigStore.routeLoop(ctx))

    /** 跑到多少公里后提醒；0 = 关闭。 */
    var routeNotifyKm by mutableStateOf(ConfigStore.routeNotifyKm(ctx))

    var engineMode by mutableStateOf(ConfigStore.engineMode(ctx))

    // ---------------------------------------------------------------- 环境探测

    var rootState by mutableStateOf<Boolean?>(null)
    var magiskState by mutableStateOf<Boolean?>(null)

    /** LSPosed 框架是否装了（走 root 看 /data/adb/lspd，不依赖包名）。 */
    var lsposedState by mutableStateOf<Boolean?>(null)

    /** 管理器包名，能查到就直接跳转；查不到就置空。 */
    var lsposedPkg by mutableStateOf<String?>(null)

    // ---------------------------------------------------------------- 弹窗

    var showMap by mutableStateOf(false)
    var showRouteMap by mutableStateOf(false)
    var showScope by mutableStateOf(false)

    // ---------------------------------------------------------------- hook 回执

    var hookReports by mutableStateOf<List<HookReport>>(emptyList())
        private set

    var nowTick by mutableStateOf(System.currentTimeMillis())
        private set

    private var lastRelayAt = 0L

    // ---------------------------------------------------------------- 动作

    /** 分页里需要直接访问 Context 的地方（收藏、作用域、备份等）。 */
    fun context(): Context = ctx

    /** 启动时探测一次环境。 */
    suspend fun bootstrap() {
        HookReportRegistry.loadIfNeeded(ctx)
        redetect()
    }

    suspend fun redetect() {
        rootState = withContext(Dispatchers.IO) { RootShell.hasRoot() }
        magiskState = withContext(Dispatchers.IO) { RootHelper.isMagiskModuleInstalled() }
        lsposedPkg = withContext(Dispatchers.IO) { findLsposed(ctx) }
        // 包名查不到不代表没装 —— LSPosed 的「隐藏管理器」会换包名，
        // 所以再走 root 看数据目录兜底
        lsposedState = withContext(Dispatchers.IO) {
            lsposedPkg != null || RootHelper.isLsposedInstalled()
        }
    }

    fun refreshReports() {
        hookReports = HookReportRegistry.snapshot()
        nowTick = System.currentTimeMillis()
    }

    fun clearReports() {
        HookReportRegistry.clear(ctx)
        hookReports = emptyList()
    }

    /**
     * 统一的落盘入口：写 prefs + 发布中继 + 通知服务刷新。
     *
     * 中继走 root，节流 2 秒 —— 拖动滑块时不能每帧都去 `su`。
     */
    fun persist(updated: LocConfig) {
        cfg = updated
        ConfigStore.save(ctx, updated)
        if (updated.enabled) FakeLocationService.refresh(ctx)

        val now = System.currentTimeMillis()
        if (now - lastRelayAt > 2_000L) {
            lastRelayAt = now
            val json = updated.toJson()
            scope.launch {
                withContext(Dispatchers.IO) { RootHelper.publishRelay(json) }
            }
        }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun applyCoords(lat: Double, lon: Double) {
        latText = fmt6(lat)
        lonText = fmt6(lon)
        persist(cfg.copy(latitude = lat, longitude = lon))
    }

    /** 从存储重新读一遍（恢复备份后用）。 */
    fun reloadFromStore() {
        cfg = ConfigStore.load(ctx)
        latText = fmt6(cfg.latitude)
        lonText = fmt6(cfg.longitude)
        altText = fmt1(cfg.altitude)
        route = ConfigStore.loadRoute(ctx)
        favorites = ConfigStore.loadFavorites(ctx)
    }

    fun startService() {
        persist(cfg.copy(enabled = true))
        FakeLocationService.start(ctx)
    }

    fun stopService() {
        persist(cfg.copy(enabled = false))
        FakeLocationService.stop(ctx)
    }
}

@Composable
fun rememberAppState(ctx: Context, scope: CoroutineScope): AppState =
    remember { AppState(ctx, scope) }
