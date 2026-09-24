package com.mo.fakeloc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mo.fakeloc.data.ConfigStore
import com.mo.fakeloc.ui.theme.WarnAmber

/**
 * 位置页：坐标输入、地图选点、精度/速度/航向，以及常用位置收藏。
 */
@Composable
internal fun PageLocation(st: AppState) {
    val ctx = st.context()

    PageColumn {

        SectionCard(
            "坐标模拟",
            "这里设的是**基准点**。点「应用坐标」只切坐标，不动任何开关。"
        ) {
            if (st.routeRunning) {
                Hint(
                    "路线模拟正在跑，它会覆盖这里的位置 —— 现在改了也要等停了路线才看得到效果。",
                    WarnAmber
                )
            }

            OutlinedButton(
                onClick = { st.showMap = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Place, contentDescription = null)
                Text("  在地图上选点")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = st.latText,
                    onValueChange = { st.latText = it },
                    label = { Text("纬度") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = st.lonText,
                    onValueChange = { st.lonText = it },
                    label = { Text("经度") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = st.altText,
                    onValueChange = { st.altText = it },
                    label = { Text("海拔(m)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val lat = st.latText.toDoubleOrNull()
                        val lon = st.lonText.toDoubleOrNull()
                        if (lat == null || lon == null ||
                            lat !in -90.0..90.0 || lon !in -180.0..180.0
                        ) {
                            st.toast("经纬度不合法")
                        } else {
                            // 只切坐标：不碰总开关、不碰路线状态
                            st.applyCoords(lat, lon, st.altText.toDoubleOrNull())
                            st.toast("已切换坐标")
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp)
                ) { Text("应用坐标") }
            }

            LabeledSlider(
                label = "水平精度",
                value = st.cfg.accuracy.toDouble(),
                range = 1.0..100.0,
                display = "${st.cfg.accuracy.toInt()} m",
                onChange = { st.persist(st.cfg.copy(accuracy = it.toFloat())) }
            )
            LabeledSlider(
                label = "静止时的速度",
                value = (st.cfg.speed * 3.6f).toDouble(),
                range = 0.0..150.0,
                display = String.format("%.1f km/h", st.cfg.speed * 3.6f),
                onChange = { st.persist(st.cfg.copy(speed = (it / 3.6).toFloat())) }
            )
            LabeledSlider(
                label = "航向",
                value = st.cfg.bearing.toDouble(),
                range = 0.0..359.0,
                display = "${st.cfg.bearing.toInt()}°",
                onChange = { st.persist(st.cfg.copy(bearing = it.toFloat())) }
            )

            Hint(
                "路线模拟运行时，速度和航向会被路线自动覆盖，这里的值只在静止模式生效。"
            )
        }

        SectionCard("常用位置") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val name = String.format("%.4f, %.4f", st.cfg.latitude, st.cfg.longitude)
                        val list = st.favorites + ConfigStore.Favorite(
                            name, st.cfg.latitude, st.cfg.longitude, System.currentTimeMillis()
                        )
                        st.favorites = list
                        ConfigStore.saveFavorites(ctx, list)
                        st.toast("已收藏")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Star, contentDescription = null)
                    Text("  收藏当前")
                }
                OutlinedButton(
                    onClick = {
                        st.favorites = emptyList()
                        ConfigStore.saveFavorites(ctx, emptyList())
                    },
                    enabled = st.favorites.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("清空收藏") }
            }

            if (st.favorites.isEmpty()) {
                Hint("还没有收藏。把常用坐标存下来，一键切换。")
            } else {
                st.favorites.forEach { f ->
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
                            st.applyCoords(f.lat, f.lon)
                            st.toast("已切换")
                        }) { Text("使用") }
                        OutlinedButton(onClick = {
                            val list = st.favorites.filter { it.addedAt != f.addedAt }
                            st.favorites = list
                            ConfigStore.saveFavorites(ctx, list)
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }
    }
}
