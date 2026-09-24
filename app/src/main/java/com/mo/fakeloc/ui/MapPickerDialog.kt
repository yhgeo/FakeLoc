package com.mo.fakeloc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mo.fakeloc.data.RoutePoint
import com.mo.fakeloc.geo.CoordinateConverter
import com.mo.fakeloc.service.RouteEngine
import com.mo.fakeloc.ui.theme.ErrRed
import com.mo.fakeloc.ui.theme.WarnAmber

/**
 * 地图选点弹窗（单点模式）。
 *
 * 用**原生 Canvas 地图**（[TileMapView]）而不是 WebView —— 这台设备的 WebView
 * 图像合成已经坏了（详见 [TileMapView] 的注释），HTML/CSS 能渲染但图片不上屏。
 *
 * 坐标约定：
 *  - 内部存储与下发统一 **WGS-84**；
 *  - 高德图层是 **GCJ-02**，从图上点出来的要换算回 WGS-84，
 *    反过来把 WGS-84 画到图上时也要先正向换算，否则标记会偏几百米。
 */
@Composable
fun MapPickerDialog(
    initialLat: Double,
    initialLon: Double,
    onDismiss: () -> Unit,
    onConfirm: (lat: Double, lon: Double) -> Unit
) {
    val mapRef = remember { mutableStateOf<TileMapView?>(null) }

    var datum by remember { mutableStateOf("GCJ02") }
    var status by remember { mutableStateOf("") }
    var pickedWgs by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // 图上点出来的原始值，用于提示坐标系偏移量
    var rawLat by remember { mutableStateOf(initialLat) }
    var rawLon by remember { mutableStateOf(initialLon) }
    var rawDatum by remember { mutableStateOf("GCJ02") }

    // 图层变了 → 按新坐标系重新摆放标记
    LaunchedEffect(datum, pickedWgs) {
        val v = mapRef.value ?: return@LaunchedEffect
        val (wLat, wLon) = pickedWgs ?: (initialLat to initialLon)
        val (dLat, dLon) = toDisplay(wLat, wLon, datum)
        v.setMarker(dLat, dLon)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MapSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    mapRef = mapRef,
                    initialLat = initialLat,
                    initialLon = initialLon,
                    onPick = { lat, lon, d ->
                        rawLat = lat
                        rawLon = lon
                        rawDatum = d
                        pickedWgs = toWgs(lat, lon, d)
                        status = String.format("已选点 %.6f, %.6f（%s）", lat, lon, d)
                    },
                    onDatumChanged = { datum = it },
                    onStatus = { status = it }
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text(
                        text = if (pickedWgs != null) "已选点（WGS-84）" else "点击地图选择位置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = pickedWgs?.let { String.format("%.6f, %.6f", it.first, it.second) }
                            ?: String.format("%.6f, %.6f", initialLat, initialLon),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (pickedWgs != null && rawDatum == "GCJ02") {
                        val drift = CoordinateConverter.distanceMeters(
                            rawLat, rawLon, pickedWgs!!.first, pickedWgs!!.second
                        )
                        Text(
                            text = String.format(
                                "高德图层为 GCJ-02，已自动换算回 WGS-84（偏移 %.0f 米）", drift
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (status.isNotEmpty()) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = WarnAmber,
                            maxLines = 2
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) { Text("取消") }

                        Button(
                            onClick = {
                                val p = pickedWgs ?: return@Button
                                onConfirm(p.first, p.second)
                            },
                            enabled = pickedWgs != null,
                            modifier = Modifier.weight(1f)
                        ) { Text("使用此位置") }
                    }
                }
            }
        }
    }
}

/**
 * 路线规划弹窗：在地图上依次点击落途经点，连成折线，确认后整条路线写进配置。
 */
@Composable
fun RoutePickerDialog(
    initialLat: Double,
    initialLon: Double,
    initialRoute: List<RoutePoint>,
    onDismiss: () -> Unit,
    onConfirm: (List<RoutePoint>) -> Unit
) {
    val mapRef = remember { mutableStateOf<TileMapView?>(null) }

    var datum by remember { mutableStateOf("GCJ02") }
    var status by remember { mutableStateOf("") }

    // 原生侧镜像，全部以 WGS-84 保存
    var routeWgs by remember { mutableStateOf(initialRoute.map { it.lat to it.lon }) }

    // 路线（WGS-84）→ 按当前图层坐标系画到图上
    LaunchedEffect(datum, routeWgs) {
        val v = mapRef.value ?: return@LaunchedEffect
        v.setRoute(routeWgs.map { toDisplay(it.first, it.second, datum) })
    }

    val totalMeters = RouteEngine.totalLength(routeWgs.map { RoutePoint(it.first, it.second) })

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MapSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    mapRef = mapRef,
                    initialLat = initialLat,
                    initialLon = initialLon,
                    onPick = { lat, lon, d ->
                        routeWgs = routeWgs + (toWgs(lat, lon, d))
                        status = "已添加第 ${routeWgs.size} 个途经点"
                    },
                    onDatumChanged = { datum = it },
                    onStatus = { status = it }
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text(
                        text = "途经点 ${routeWgs.size} 个 · 总长 " +
                            String.format("%.2f km", totalMeters / 1000.0),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "在地图上依次点击落点；坐标按当前图层换算回 WGS-84 存储",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (status.isNotEmpty()) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = WarnAmber,
                            maxLines = 2
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { if (routeWgs.isNotEmpty()) routeWgs = routeWgs.dropLast(1) },
                            enabled = routeWgs.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text("撤销") }

                        OutlinedButton(
                            onClick = { routeWgs = emptyList() },
                            enabled = routeWgs.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text("清空") }

                        OutlinedButton(
                            onClick = {
                                mapRef.value?.fitBounds(
                                    routeWgs.map { toDisplay(it.first, it.second, datum) }
                                )
                            },
                            enabled = routeWgs.size >= 2,
                            modifier = Modifier.weight(1f)
                        ) { Text("全览") }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) { Text("取消") }

                        Button(
                            onClick = {
                                onConfirm(routeWgs.map { RoutePoint(it.first, it.second) })
                            },
                            enabled = routeWgs.size >= 2,
                            modifier = Modifier.weight(1f)
                        ) { Text("使用这条路线") }
                    }
                }
            }
        }
    }
}

// ==================================================================== 公共部件

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MapSurface(
    modifier: Modifier,
    mapRef: androidx.compose.runtime.MutableState<TileMapView?>,
    initialLat: Double,
    initialLon: Double,
    onPick: (Double, Double, String) -> Unit,
    onDatumChanged: (String) -> Unit,
    onStatus: (String) -> Unit
) {
    var view by remember { mutableStateOf<TileMapView?>(null) }
    var layer by remember { mutableStateOf(TileMapView.Layer.AMAP) }

    Column(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                TileMapView(ctx).apply {
                    listener = object : TileMapView.Listener {
                        override fun onPick(lat: Double, lon: Double, datum: String) {
                            onPick(lat, lon, datum)
                        }

                        override fun onViewChanged(datum: String, zoom: Double) {
                            onDatumChanged(datum)
                        }

                        override fun onTileFallback() {
                            layer = TileMapView.Layer.GRID
                            onStatus("瓦片源不可用，已自动切换为离线经纬网格（选点照常可用）")
                        }
                    }
                    val (dLat, dLon) = toDisplay(initialLat, initialLon, TileMapView.Layer.AMAP.datum)
                    setView(dLat, dLon, 15.0)
                    setMarker(dLat, dLon)
                    view = this
                    mapRef.value = this
                }
            }
        )

        // 图层切换 + 缩放
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TileMapView.Layer.values().forEach { l ->
                FilterChip(
                    selected = layer == l,
                    onClick = {
                        layer = l
                        view?.setLayer(l)
                    },
                    label = { Text(l.label) }
                )
            }
        }
    }
}

// ==================================================================== 坐标系辅助

/** WGS-84 → 当前图层坐标系（用于在图上摆放标记）。 */
internal fun toDisplay(latWgs: Double, lonWgs: Double, datum: String): Pair<Double, Double> =
    if (datum.equals("GCJ02", ignoreCase = true)) {
        CoordinateConverter.wgs84ToGcj02(latWgs, lonWgs)
    } else {
        latWgs to lonWgs
    }

/** 当前图层坐标系 → WGS-84（用于落库）。 */
internal fun toWgs(lat: Double, lon: Double, datum: String): Pair<Double, Double> =
    if (datum.equals("GCJ02", ignoreCase = true)) {
        CoordinateConverter.gcj02ToWgs84(lat, lon)
    } else {
        lat to lon
    }
