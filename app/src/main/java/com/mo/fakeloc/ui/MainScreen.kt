package com.mo.fakeloc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mo.fakeloc.data.ConfigStore
import com.mo.fakeloc.ui.theme.OkGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 侧边栏分类。 */
enum class HomePage(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
) {
    OVERVIEW("总览", "状态与启停", Icons.Default.Home),
    LOCATION("位置", "坐标与常用位置", Icons.Default.Place),
    ROUTE("路线模拟", "折线播放与里程提醒", Icons.Default.PlayArrow),
    STEALTH("反检测", "隐藏特征与作用域", Icons.Default.Warning),
    DIAGNOSTICS("诊断", "hook 接入状态与备份", Icons.Default.Build)
}

/**
 * 主界面：左侧抽屉分页。
 *
 * 之前是一整条长滚动页，功能堆到十几张卡片，找东西要滚很久。
 * 现在按用途拆成 5 个分类，抽屉切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(openMapOnStart: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = rememberAppState(ctx, scope)

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var page by remember { mutableStateOf(HomePage.OVERVIEW) }

    // 支持从 adb / 快捷方式直接打开地图弹窗（排查与自动化测试用）
    LaunchedEffect(openMapOnStart) {
        if (openMapOnStart) st.showMap = true
    }

    LaunchedEffect(Unit) { st.bootstrap() }

    // hook 回执轮询：同时驱动"多久前"的显示
    LaunchedEffect(Unit) {
        while (true) {
            st.refreshReports()
            delay(1500)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(20.dp))
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        "FakeLoc",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "LSPosed 虚拟定位 · Android 14",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                HomePage.entries.forEach { p ->
                    NavigationDrawerItem(
                        label = { Text(p.title) },
                        icon = { Icon(p.icon, contentDescription = null) },
                        selected = page == p,
                        onClick = {
                            page = p
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }

                Spacer(Modifier.weight(1f))
                HorizontalDivider()
                Column(modifier = Modifier.padding(24.dp)) {
                    StatusRow(
                        "总开关",
                        if (st.cfg.enabled) true else null,
                        if (st.cfg.enabled) "运行中" else "已停止"
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    },
                    title = {
                        Column {
                            Text(page.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                page.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        // 右上角小圆点：一眼看出总开关状态
                        Box(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(10.dp)
                                .background(
                                    if (st.cfg.enabled) OkGreen
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (page) {
                    HomePage.OVERVIEW -> PageOverview(st)
                    HomePage.LOCATION -> PageLocation(st)
                    HomePage.ROUTE -> PageRoute(st)
                    HomePage.STEALTH -> PageStealth(st)
                    HomePage.DIAGNOSTICS -> PageDiagnostics(st)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 弹窗

    if (st.showMap) {
        MapPickerDialog(
            initialLat = st.cfg.latitude,
            initialLon = st.cfg.longitude,
            onDismiss = { st.showMap = false },
            onConfirm = { lat, lon ->
                st.applyCoords(lat, lon)
                st.showMap = false
                st.toast("已选点并应用")
            }
        )
    }

    if (st.showRouteMap) {
        RoutePickerDialog(
            initialLat = st.cfg.latitude,
            initialLon = st.cfg.longitude,
            initialRoute = st.route,
            onDismiss = { st.showRouteMap = false },
            onConfirm = { pts ->
                st.route = pts
                ConfigStore.saveRoute(ctx, pts)
                st.showRouteMap = false
                st.toast("已保存路线：${pts.size} 个途经点")
            }
        )
    }

    if (st.showScope) {
        AppScopeDialog(
            selected = st.cfg.scopePackages.toSet(),
            onDismiss = { st.showScope = false },
            onConfirm = { set ->
                st.persist(st.cfg.copy(scopePackages = set.toList()))
                st.showScope = false
                st.toast("已保存作用域：${set.size} 个应用")
            }
        )
    }
}
