package com.mo.fakeloc.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mo.fakeloc.data.ConfigStore
import com.mo.fakeloc.data.HookReport
import com.mo.fakeloc.data.HookReportRegistry
import com.mo.fakeloc.data.LocConfig
import com.mo.fakeloc.data.RoutePoint
import com.mo.fakeloc.root.RootHelper
import com.mo.fakeloc.root.RootShell
import com.mo.fakeloc.service.FakeLocationService
import com.mo.fakeloc.service.RouteEngine
import com.mo.fakeloc.ui.theme.ErrRed
import com.mo.fakeloc.ui.theme.OkGreen
import com.mo.fakeloc.ui.theme.WarnAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun fmt6(v: Double) = String.format("%.6f", v)
private fun fmt1(v: Double) = String.format("%.1f", v)

/** LSPosed 管理器的常见包名。 */
private val LSPOSED_PACKAGES = listOf(
    "org.lsposed.manager",
    "org.lsposed.manager.debug"
)

private fun findLsposed(ctx: Context): String? = LSPOSED_PACKAGES.firstOrNull { pkg ->
    try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }
}

/** 把时间戳说成人话。 */
private fun agoText(seenAt: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - seenAt).coerceAtLeast(0L)
    return when {
        d < 3_000L -> "刚刚"
        d < 60_000L -> "${d / 1000} 秒前"
        d < 3_600_000L -> "${d / 60_000} 分钟前"
        else -> "${d / 3_600_000} 小时前"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(openMapOnStart: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var cfg by remember { mutableStateOf(ConfigStore.load(ctx)) }
    var latText by remember { mutableStateOf(fmt6(cfg.latitude)) }
    var lonText by remember { mutableStateOf(fmt6(cfg.longitude)) }
    var altText by remember { mutableStateOf(fmt1(cfg.altitude)) }

    var favorites by remember { mutableStateOf(ConfigStore.loadFavorites(ctx)) }
    var route by remember { mutableStateOf(ConfigStore.loadRoute(ctx)) }
    var routeSpeed by remember { mutableStateOf(ConfigStore.routeSpeedKmh(ctx)) }
    var routeLoop by remember { mutableStateOf(ConfigStore.routeLoop(ctx)) }
    var engineMode by remember { mutableStateOf(ConfigStore.engineMode(ctx)) }

    var rootState by remember { mutableStateOf<Boolean?>(null) }
    var magiskState by remember { mutableStateOf<Boolean?>(null) }
    var lsposedPkg by remember { mutableStateOf<String?>(null) }

    var showMap by remember { mutableStateOf(false) }
    var showRouteMap by remember { mutableStateOf(false) }
    var showScope by remember { mutableStateOf(false) }

    // 支持从 adb / 快捷方式直接打开地图弹窗（排查与自动化测试用）
    LaunchedEffect(openMapOnStart) {
        if (openMapOnStart) showMap = true
    }

    // hook 回执：hook 端每次来要配置时会顺路报一次到，这里轮询展示
    var hookReports by remember { mutableStateOf<List<HookReport>>(emptyList()) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        HookReportRegistry.loadIfNeeded(ctx)
        rootState = withContext(Dispatchers.IO) { RootShell.hasRoot() }
        magiskState = withContext(Dispatchers.IO) { RootHelper.isMagiskModuleInstalled() }
        lsposedPkg = withContext(Dispatchers.IO) { findLsposed(ctx) }
    }

    // 回执轮询：1.5 秒刷新一次，同时驱动"多久前"的显示
    LaunchedEffect(Unit) {
        while (true) {
            hookReports = HookReportRegistry.snapshot()
            nowTick = System.currentTimeMillis()
            delay(1500)
        }
    }

    /** 统一的落盘入口：写 prefs + 发布中继 + 通知服务刷新。 */
    var lastRelayAt by remember { mutableStateOf(0L) }
    fun persist(updated: LocConfig) {
        cfg = updated
        ConfigStore.save(ctx, updated)
        if (updated.enabled) FakeLocationService.refresh(ctx)

        // 发布到 Settings.Global 中继（hook 侧跨进程读配置靠它），走 root，节流 2 秒
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FakeLoc", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "LSPosed 虚拟定位 · Android 14 / Magisk",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ============================================================ 状态
            SectionCard("运行状态") {
                StatusRow("Root 权限", rootState)
                StatusRow("Magisk 模块", magiskState)
                StatusRow(
                    "工作模式",
                    if (engineMode == ConfigStore.MODE_LSPOSED) true else null,
                    if (engineMode == ConfigStore.MODE_LSPOSED) "LSPosed 通道" else "开发者模拟位置"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val next = cfg.copy(enabled = !cfg.enabled)
                            persist(next)
                            if (next.enabled) {
                                FakeLocationService.start(ctx)
                                toast("已启动虚拟定位")
                            } else {
                                FakeLocationService.stop(ctx)
                                toast("已停止")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            if (cfg.enabled) Icons.Default.Warning else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Text(if (cfg.enabled) "  停止" else "  启动")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                rootState = withContext(Dispatchers.IO) { RootShell.hasRoot() }
                                magiskState = withContext(Dispatchers.IO) { RootHelper.isMagiskModuleInstalled() }
                                toast("已重新检测")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("  重新检测")
                    }
                }

                if (rootState == false) {
                    Hint(
                        "未检测到 Root。LSPosed 通道仍然可用（LSPosed 自己就是 root 的），" +
                            "但 Magisk 模块的备份/权限辅助功能会不可用。",
                        WarnAmber
                    )
                }
                if (engineMode == ConfigStore.MODE_MOCK_PROVIDER) {
                    Hint(
                        "开发者模拟位置模式：需在「开发者选项 → 选择模拟位置信息应用」里选中本应用，" +
                            "否则系统会拒绝写入位置。",
                        WarnAmber
                    )
                }
            }

            // ============================================================ 位置
            SectionCard(
                "伪造坐标",
                "存储与下发的统一是 WGS-84；国内地图选点会自动换算。"
            ) {
                OutlinedButton(
                    onClick = { showMap = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Place, contentDescription = null)
                    Text("  在地图上选点")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("纬度") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = { Text("经度") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = altText,
                        onValueChange = { altText = it },
                        label = { Text("海拔(m)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val lat = latText.toDoubleOrNull()
                            val lon = lonText.toDoubleOrNull()
                            if (lat == null || lon == null ||
                                lat !in -90.0..90.0 || lon !in -180.0..180.0
                            ) {
                                toast("经纬度不合法")
                            } else {
                                val alt = altText.toDoubleOrNull() ?: cfg.altitude
                                persist(cfg.copy(latitude = lat, longitude = lon, altitude = alt))
                                toast("已应用")
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 8.dp)
                    ) { Text("应用坐标") }
                }

                LabeledSlider(
                    label = "水平精度",
                    value = cfg.accuracy.toDouble(),
                    range = 1.0..100.0,
                    display = "${cfg.accuracy.toInt()} m",
                    onChange = { persist(cfg.copy(accuracy = it.toFloat())) }
                )
                LabeledSlider(
                    label = "速度",
                    value = (cfg.speed * 3.6f).toDouble(),
                    range = 0.0..150.0,
                    display = String.format("%.1f km/h", cfg.speed * 3.6f),
                    onChange = { persist(cfg.copy(speed = (it / 3.6).toFloat())) }
                )
                LabeledSlider(
                    label = "航向",
                    value = cfg.bearing.toDouble(),
                    range = 0.0..359.0,
                    display = "${cfg.bearing.toInt()}°",
                    onChange = { persist(cfg.copy(bearing = it.toFloat())) }
                )
            }

            // ============================================================ 收藏
            SectionCard("常用位置") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            val name = String.format("%.4f, %.4f", cfg.latitude, cfg.longitude)
                            val list = favorites + ConfigStore.Favorite(
                                name, cfg.latitude, cfg.longitude, System.currentTimeMillis()
                            )
                            favorites = list
                            ConfigStore.saveFavorites(ctx, list)
                            toast("已收藏")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null)
                        Text("  收藏当前")
                    }
                    OutlinedButton(
                        onClick = {
                            favorites = emptyList()
                            ConfigStore.saveFavorites(ctx, emptyList())
                        },
                        enabled = favorites.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text("清空收藏") }
                }

                if (favorites.isEmpty()) {
                    Hint("还没有收藏。把常用坐标存下来，一键切换。", MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    favorites.forEach { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    String.format("%.6f, %.6f", f.lat, f.lon),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(onClick = {
                                applyCoords(f.lat, f.lon)
                                toast("已切换")
                            }) { Text("使用") }
                            OutlinedButton(onClick = {
                                val list = favorites.filter { it.addedAt != f.addedAt }
                                favorites = list
                                ConfigStore.saveFavorites(ctx, list)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }

            // ============================================================ 路线
            SectionCard(
                "路线模拟",
                "按设定速度沿折线移动，每秒推进一次。用于跑步/骑行类场景。"
            ) {
                val totalMeters = remember(route) { RouteEngine.totalLength(route) }
                Text(
                    "途经点 ${route.size} 个 · 总长 ${String.format("%.2f", totalMeters / 1000.0)} km",
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    onClick = { showRouteMap = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Place, contentDescription = null)
                    Text("  在地图上规划路线")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            val p = RoutePoint(cfg.latitude, cfg.longitude)
                            val list = route + p
                            route = list
                            ConfigStore.saveRoute(ctx, list)
                            toast("已添加途经点 ${list.size}")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("  添加当前点")
                    }
                    OutlinedButton(
                        onClick = {
                            route = emptyList()
                            ConfigStore.saveRoute(ctx, emptyList())
                        },
                        enabled = route.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text("清空路线") }
                }

                if (route.isNotEmpty()) {
                    Text(
                        route.joinToString(" → ") { String.format("%.4f,%.4f", it.lat, it.lon) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )
                }

                LabeledSlider(
                    label = "路线速度",
                    value = routeSpeed,
                    range = 5.0..120.0,
                    display = String.format("%.0f km/h", routeSpeed),
                    onChange = {
                        routeSpeed = it
                        ConfigStore.saveRouteSpeed(ctx, it)
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("循环播放", modifier = Modifier.weight(1f))
                    Switch(
                        checked = routeLoop,
                        onCheckedChange = {
                            routeLoop = it
                            ConfigStore.saveRouteLoop(ctx, it)
                        }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (route.size < 2) {
                                toast("至少需要 2 个途经点")
                            } else {
                                persist(cfg.copy(enabled = true))
                                FakeLocationService.start(ctx)
                                toast("开始路线模拟")
                            }
                        },
                        enabled = route.size >= 2,
                        modifier = Modifier.weight(1f)
                    ) { Text("开始路线") }

                    OutlinedButton(
                        onClick = {
                            persist(cfg.copy(enabled = false))
                            FakeLocationService.stop(ctx)
                            toast("已停止")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("停止") }
                }
            }

            // ============================================================ 行为
            SectionCard("反检测与作用域") {
                SwitchRow(
                    title = "隐藏模拟位置标记",
                    desc = "抹掉 Location.isMock 与 extras 里的 mockLocation，规避简单检测",
                    checked = cfg.hideMock,
                    onChange = { persist(cfg.copy(hideMock = it)) }
                )
                SwitchRow(
                    title = "自然抖动",
                    desc = "让坐标以米级幅度缓慢漂移，避免「纹丝不动」这一特征",
                    checked = cfg.jitterEnabled,
                    onChange = { persist(cfg.copy(jitterEnabled = it)) }
                )
                LabeledSlider(
                    label = "抖动幅度",
                    value = cfg.jitterMeters,
                    range = 0.0..20.0,
                    display = String.format("%.1f m", cfg.jitterMeters),
                    onChange = { persist(cfg.copy(jitterMeters = it)) }
                )

                SwitchRow(
                    title = "阻断网络定位（WiFi / 基站）",
                    desc = "腾讯/高德/百度的 SDK 会把 WiFi BSSID + 基站指纹上报服务端算位置，" +
                        "这条路径绕过系统定位，不掐掉会出现「位置被拉回真实坐标」",
                    checked = cfg.blockNetworkPos,
                    onChange = { persist(cfg.copy(blockNetworkPos = it)) }
                )

                SwitchRow(
                    title = "启用作用域白名单",
                    desc = "只对选定应用生效；系统进程恒定放行",
                    checked = cfg.scopeWhitelistEnabled,
                    onChange = { persist(cfg.copy(scopeWhitelistEnabled = it)) }
                )
                OutlinedButton(
                    onClick = { showScope = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择应用（已选 ${cfg.scopePackages.size}）")
                }

                Text(
                    "工作模式",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = engineMode == ConfigStore.MODE_LSPOSED,
                        onClick = {
                            engineMode = ConfigStore.MODE_LSPOSED
                            ConfigStore.saveEngineMode(ctx, ConfigStore.MODE_LSPOSED)
                            FakeLocationService.refresh(ctx)
                        },
                        label = { Text("LSPosed（推荐）") }
                    )
                    FilterChip(
                        selected = engineMode == ConfigStore.MODE_MOCK_PROVIDER,
                        onClick = {
                            engineMode = ConfigStore.MODE_MOCK_PROVIDER
                            ConfigStore.saveEngineMode(ctx, ConfigStore.MODE_MOCK_PROVIDER)
                            FakeLocationService.refresh(ctx)
                        },
                        label = { Text("开发者模拟位置") }
                    )
                }
            }

            // ============================================================ hook 接入状态
            SectionCard(
                "LSPosed 接入状态",
                "hook 每次来取配置时会顺路报一次到。看到「已注入进程」才算真的生效 —— " +
                    "否则定位不会变。"
            ) {
                StatusRow(
                    "LSPosed 管理器",
                    if (lsposedPkg != null) true else null,
                    lsposedPkg ?: "未检测到"
                )

                val alive = hookReports.filter { it.isAlive(nowTick) }
                StatusRow(
                    "已注入进程",
                    when {
                        alive.isEmpty() -> false
                        else -> true
                    },
                    if (alive.isEmpty()) {
                        "0（还没有任何进程挂上 hook）"
                    } else {
                        "${alive.size} 个正在回报 / 共 ${hookReports.size} 条记录"
                    }
                )

                if (hookReports.isEmpty()) {
                    Hint(
                        "① 在 LSPosed 里启用本模块；② 作用域勾选目标应用（默认只勾了系统框架）；" +
                            "③ 重启目标应用 —— hook 只在进程启动时挂载，不重启不生效。" +
                            "④ 若只勾了系统框架，system_server 应该会回报。",
                        WarnAmber
                    )
                } else {
                    hookReports.take(6).forEach { r ->
                        HookReportRow(r, nowTick)
                    }
                    if (hookReports.size > 6) {
                        Hint("仅显示最近 6 条，共 ${hookReports.size} 条", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            hookReports = HookReportRegistry.snapshot()
                            nowTick = System.currentTimeMillis()
                            toast("已刷新（${hookReports.size} 条）")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("  刷新")
                    }
                    OutlinedButton(
                        onClick = {
                            HookReportRegistry.clear(ctx)
                            hookReports = emptyList()
                            toast("已清空回执记录")
                        },
                        enabled = hookReports.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text("清空回执") }
                    OutlinedButton(
                        onClick = {
                            val pkg = lsposedPkg ?: LSPOSED_PACKAGES.first()
                            val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
                            if (i == null) {
                                toast("打不开 LSPosed 管理器")
                            } else {
                                try {
                                    ctx.startActivity(
                                        Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (_: Throwable) {
                                    toast("打不开 LSPosed 管理器")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("打开 LSPosed") }
                }
            }

            // ============================================================ root 辅助
            SectionCard(
                "Root 辅助（可选）",
                "把配置备份到 /data/adb/fakeloc/，清数据或重装后可恢复；也方便排查 hook 读到了什么。"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    RootHelper.backupConfig(ctx, cfg.toJson())
                                }
                                toast(if (ok) "已备份到 /data/adb/fakeloc/" else "备份失败（无 root？）")
                            }
                        },
                        enabled = rootState == true,
                        modifier = Modifier.weight(1f)
                    ) { Text("备份配置") }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { RootHelper.restoreInto(ctx) }
                                if (ok) {
                                    cfg = ConfigStore.load(ctx)
                                    latText = fmt6(cfg.latitude)
                                    lonText = fmt6(cfg.longitude)
                                    toast("已从备份恢复")
                                } else {
                                    toast("没有可恢复的备份")
                                }
                            }
                        },
                        enabled = rootState == true,
                        modifier = Modifier.weight(1f)
                    ) { Text("恢复配置") }
                }
            }

            Text(
                "配置变更会在 1 秒内被 hook 端读到；若目标应用已在运行，" +
                    "建议重启该应用以重新挂载 hook。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }

    // ---------------------------------------------------------------- 弹窗

    if (showMap) {
        MapPickerDialog(
            initialLat = cfg.latitude,
            initialLon = cfg.longitude,
            onDismiss = { showMap = false },
            onConfirm = { lat, lon ->
                applyCoords(lat, lon)
                showMap = false
                toast("已选点并应用")
            }
        )
    }

    if (showRouteMap) {
        RoutePickerDialog(
            initialLat = cfg.latitude,
            initialLon = cfg.longitude,
            initialRoute = route,
            onDismiss = { showRouteMap = false },
            onConfirm = { pts ->
                route = pts
                ConfigStore.saveRoute(ctx, pts)
                showRouteMap = false
                toast("已保存路线：${pts.size} 个途经点")
            }
        )
    }

    if (showScope) {
        AppScopeDialog(
            selected = cfg.scopePackages.toSet(),
            onDismiss = { showScope = false },
            onConfirm = { set ->
                persist(cfg.copy(scopePackages = set.toList()))
                showScope = false
                toast("已保存作用域：${set.size} 个应用")
            }
        )
    }
}

// ==================================================================== 通用组件

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, state: Boolean?, text: String? = null) {
    val (color, value) = when (state) {
        true -> OkGreen to (text ?: "正常")
        false -> ErrRed to (text ?: "不可用")
        null -> WarnAmber to (text ?: "未知 / 检测中")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Hint(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

/**
 * 一条 hook 回执。
 *
 * 读法：
 *  - 有「hook 摘要」= 该进程真的被注入了，`LM:14` 表示挂了 14 个 LocationManager 相关的点；
 *  - 「配置」显示该进程读到的 seq 与开关。如果 App 已经开了总开关，
 *    这里却还是「关」，说明配置通道没打通（而不是 hook 没挂上）。
 */
@Composable
private fun HookReportRow(r: HookReport, now: Long) {
    val alive = r.isAlive(now)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                r.pkg,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (alive) OkGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                agoText(r.seenAt, now),
                style = MaterialTheme.typography.labelSmall,
                color = if (alive) OkGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            buildString {
                if (r.process != r.pkg) append("进程 ${r.process} · ")
                append("hook ")
                append(r.hooks.ifEmpty { "无" })
                append(" · 通道 ")
                append(r.channel.ifEmpty { "?" })
                append(" · 配置#")
                append(r.cfgSeq)
                append(if (r.cfgEnabled) "（开）" else "（关）")
                append(" · v")
                append(r.moduleVersion)
                append(" · ${r.hits} 次")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    display: String,
    onChange: (Double) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                display,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            // Material3 的 Slider 是 Float 实现，这里做一次转换，
            // 对外仍然保持 Double 接口（经纬度/速度都是 Double 语义）
            value = value.coerceIn(range.start, range.endInclusive).toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat()
        )
    }
}
