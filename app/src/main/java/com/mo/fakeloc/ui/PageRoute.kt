package com.mo.fakeloc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mo.fakeloc.data.ConfigStore
import com.mo.fakeloc.data.RoutePoint
import com.mo.fakeloc.service.RouteEngine

/**
 * 路线模拟页。
 *
 * 速度单位是 **km/min**（跑步场景下 km/h 刻度太粗，0.1 km/min 一档更顺手）。
 */
@Composable
internal fun PageRoute(st: AppState) {
    val ctx = st.context()

    PageColumn {

        SectionCard(
            "路线",
            "在地图上依次落点形成折线；播放时沿折线**连续**推进，每秒重算一次位置。"
        ) {
            val totalMeters = remember(st.route) { RouteEngine.totalLength(st.route) }
            Text(
                "途经点 ${st.route.size} 个 · 总长 ${fmt2(totalMeters / 1000.0)} km",
                style = MaterialTheme.typography.bodyMedium
            )

            Button(
                onClick = { st.showRouteMap = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Place, contentDescription = null)
                Text("  在地图上规划路线")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val list = st.route + RoutePoint(st.cfg.latitude, st.cfg.longitude)
                        st.route = list
                        ConfigStore.saveRoute(ctx, list)
                        st.toast("已添加途经点 ${list.size}")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  添加当前点")
                }
                OutlinedButton(
                    onClick = {
                        st.route = emptyList()
                        ConfigStore.saveRoute(ctx, emptyList())
                    },
                    enabled = st.route.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("清空路线") }
            }

            if (st.route.isNotEmpty()) {
                Text(
                    st.route.joinToString(" → ") { String.format("%.4f,%.4f", it.lat, it.lon) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3
                )
            }
        }

        SectionCard("播放参数") {
            LabeledSlider(
                label = "速度",
                value = st.routeSpeed,
                range = ConfigStore.MIN_ROUTE_SPEED..ConfigStore.MAX_ROUTE_SPEED,
                display = String.format(
                    "%.2f km/min · %.1f km/h",
                    st.routeSpeed, kmPerMinToKmh(st.routeSpeed)
                ),
                onChange = {
                    st.routeSpeed = it
                    ConfigStore.saveRouteSpeed(ctx, it)
                }
            )
            Hint(
                "参考：走路约 0.08、慢跑约 0.15、快跑约 0.25、骑行约 0.35 km/min。"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("循环播放", modifier = Modifier.weight(1f))
                Switch(
                    checked = st.routeLoop,
                    onCheckedChange = {
                        st.routeLoop = it
                        ConfigStore.saveRouteLoop(ctx, it)
                    }
                )
            }
            if (st.routeLoop) {
                Hint("走完一圈会从起点重新开始 —— 小程序的轨迹上会看到一次「瞬移」，需要连贯轨迹就关掉它。")
            }
        }

        SectionCard(
            "里程提醒",
            "跑到设定公里数时弹一条会响铃/震动的通知，方便盲跑。"
        ) {
            LabeledSlider(
                label = "目标里程",
                value = st.routeNotifyKm,
                range = 0.0..10.0,
                display = if (st.routeNotifyKm <= 0.0) "关闭"
                else "${fmt2(st.routeNotifyKm)} km",
                onChange = {
                    // 0 附近吸附到关闭，避免 0.01 这种没意义的设定
                    val v = if (it < 0.05) 0.0 else it
                    st.routeNotifyKm = v
                    ConfigStore.saveRouteNotifyKm(ctx, v)
                },
                steps = 19
            )
            if (st.routeNotifyKm > 0.0) {
                Hint("每趟只提醒一次；循环播放时会重新计时。通知走独立通道，不受常驻通知静音影响。")
            } else {
                Hint("拖到最左边即关闭。")
            }
        }

        SectionCard("开始 / 停止") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (st.route.size < 2) {
                            st.toast("至少需要 2 个途经点")
                        } else {
                            st.startService()
                            st.toast("开始路线模拟")
                        }
                    },
                    enabled = st.route.size >= 2,
                    modifier = Modifier.weight(1f)
                ) { Text("开始路线") }

                OutlinedButton(
                    onClick = {
                        st.stopService()
                        st.toast("已停止")
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("停止") }
            }
            Hint(
                "开始前请确认总开关是开的；「总览」页能看到当前是否在运行。"
            )
        }
    }
}
