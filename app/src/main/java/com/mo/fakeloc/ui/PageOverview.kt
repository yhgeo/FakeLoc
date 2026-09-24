package com.mo.fakeloc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mo.fakeloc.data.ConfigStore
import com.mo.fakeloc.service.RouteEngine
import com.mo.fakeloc.ui.theme.WarnAmber
import kotlinx.coroutines.launch

/**
 * 总览页：环境状态 + 启停 + 当前坐标/路线的摘要。
 *
 * 这一页只放"一眼要看的东西"，具体调参数去各自的分类页。
 */
@Composable
internal fun PageOverview(st: AppState) {
    val scope = rememberCoroutineScope()

    PageColumn {

        SectionCard("运行状态") {
            StatusRow("Root 权限", st.rootState)
            StatusRow(
                "Magisk 辅助模块",
                st.magiskState,
                if (st.magiskState == true) "已刷入" else "未刷入（可选）"
            )
            StatusRow(
                "LSPosed 框架",
                st.lsposedState,
                when {
                    st.lsposedState == true && st.lsposedPkg == null -> "已安装（管理器包名被隐藏）"
                    st.lsposedState == true -> "已安装"
                    else -> "未检测到"
                }
            )
            StatusRow(
                "工作模式",
                if (st.engineMode == ConfigStore.MODE_LSPOSED) true else null,
                if (st.engineMode == ConfigStore.MODE_LSPOSED) "LSPosed 通道" else "开发者模拟位置"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (st.cfg.enabled) {
                            st.stopService()
                            st.toast("已停止")
                        } else {
                            st.startService()
                            st.toast("已启动虚拟定位")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (st.cfg.enabled) Icons.Default.Warning else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Text(if (st.cfg.enabled) "  停止" else "  启动")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            st.redetect()
                            st.toast("已重新检测")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("  重新检测")
                }
            }

            if (st.rootState == false) {
                Hint(
                    "未检测到 Root。LSPosed 通道仍然可用（LSPosed 自己就是 root 的），" +
                        "但配置中继、备份这些 root 辅助功能会不可用。",
                    WarnAmber
                )
            }
            if (st.engineMode == ConfigStore.MODE_MOCK_PROVIDER) {
                Hint(
                    "开发者模拟位置模式：需在「开发者选项 → 选择模拟位置信息应用」里选中本应用，" +
                        "否则系统会拒绝写入位置。",
                    WarnAmber
                )
            }
        }

        SectionCard("当前坐标", "存储与下发的统一是 WGS-84；国内地图选点会自动换算。") {
            Text(
                String.format("%.6f, %.6f", st.cfg.staticLatitude, st.cfg.staticLongitude),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            InfoRow("模拟模式", if (st.routeRunning) "路线模拟（覆盖坐标）" else "坐标模拟")
            if (st.routeRunning) {
                // 路线跑着的时候当前位置每秒都在变，和设定值分开显示
                InfoRow(
                    "当前位置",
                    String.format("%.6f, %.6f", st.cfg.latitude, st.cfg.longitude)
                )
            }
            InfoRow("海拔", "${fmt1(st.cfg.altitude)} m")
            InfoRow("水平精度", "${st.cfg.accuracy.toInt()} m")

            OutlinedButton(
                onClick = { st.showMap = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Place, contentDescription = null)
                Text("  在地图上选点")
            }
        }

        SectionCard("当前路线") {
            if (st.route.size < 2) {
                Hint("还没有可用路线（至少需要 2 个途经点）。去「路线模拟」页规划。")
            } else {
                val total = RouteEngine.totalLength(st.route)
                InfoRow("状态", if (st.routeRunning) "运行中" else "已停止")
                InfoRow("途经点", "${st.route.size} 个")
                InfoRow("总长", "${fmt2(total / 1000.0)} km")
                InfoRow(
                    "配速",
                    "${RouteEngine.formatPace(st.routePace)}（${fmt1(RouteEngine.paceToKmh(st.routePace))} km/h）"
                )
                InfoRow(
                    "里程提醒",
                    if (st.routeNotifyKm <= 0.0) "关闭" else "跑到 ${fmt2(st.routeNotifyKm)} km"
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (st.route.size < 2) {
                            st.toast("至少需要 2 个途经点")
                        } else {
                            st.startRoute()
                            st.toast("开始路线模拟")
                        }
                    },
                    enabled = st.route.size >= 2 && !st.routeRunning,
                    modifier = Modifier.weight(1f)
                ) { Text("开始路线") }

                OutlinedButton(
                    onClick = {
                        st.stopRoute()
                        st.toast("已停止路线模拟（总开关保持开启）")
                    },
                    enabled = st.routeRunning,
                    modifier = Modifier.weight(1f)
                ) { Text("停止路线") }
            }
            if (st.routeRunning) {
                Hint("停止路线只停路线本身，总开关不变 —— 位置会落回上面设定的坐标。")
            }
        }

        Text(
            "配置变更会在 1 秒内被 hook 端读到；若目标应用已在运行，" +
                "建议重启该应用以重新挂载 hook。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
