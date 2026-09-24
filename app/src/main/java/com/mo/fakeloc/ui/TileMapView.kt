package com.mo.fakeloc.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.mo.fakeloc.geo.CoordinateConverter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * 原生 Canvas 瓦片地图。
 *
 * ## 为什么要自己画，而不是继续用 WebView + Leaflet
 * 真机上实测：这台 realme RMX3366（ColorOS 14，系统 WebView 117.0.5938.60）
 * 的 WebView **画不出图片**。证据链：
 *  - 页面正常加载，`page finished`，HUD / 按钮等 HTML+CSS 全部正常渲染；
 *  - 瓦片 HTTP 200、`tileload` 事件触发、`naturalWidth=256`；
 *  - DOM 探针显示瓦片元素 `256x256`、`visibility: visible`、`opacity: 1`；
 *  - 关掉 `translate3d`（Leaflet any3d）改用 left/top 定位后**依然一片黑**；
 *  - 期间还伴随 `Renderer process crash detected (code -1)` 与
 *    `webview_service` 的 SIGSEGV。
 *
 * 结论是这台设备的 WebView 图像合成已经坏了，不是我们的页面写法问题。
 * 于是改成原生绘制：**不依赖 WebView、不依赖 Leaflet**，顺带把 160KB 的
 * leaflet.js/css 从 APK 里去掉。
 *
 * ## 实现要点
 *  - Web Mercator 投影，支持小数级缩放（瓦片按比例拉伸，手感连续）；
 *  - 瓦片走 LruCache + 4 线程异步下载，主线程只做绘制；
 *  - 双指缩放 + 单指拖动；单指轻点 = 选点；
 *  - 瓦片全失败时自动降级成**离线经纬网格**（原生画线），选点照常可用。
 */
class TileMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ------------------------------------------------------------------ 对外接口

    /** 图层。 */
    enum class Layer(val label: String, val datum: String) {
        AMAP("高德", "GCJ02"),
        SAT("影像", "GCJ02"),
        OSM("OSM", "WGS84"),
        GRID("网格", "WGS84")
    }

    interface Listener {
        /** 用户在地图上点了一下（坐标是**当前图层坐标系**）。 */
        fun onPick(lat: Double, lon: Double, datum: String)

        /** 视图变化（平移/缩放/换图层），用于同步原生侧标记与路线。 */
        fun onViewChanged(datum: String, zoom: Double)

        /** 瓦片源不可用，已自动降级到离线网格。 */
        fun onTileFallback()
    }

    var listener: Listener? = null

    var layer: Layer = Layer.AMAP
        private set

    val datum: String get() = layer.datum

    // ------------------------------------------------------------------ 状态

    private var zoom = 15.0
    private var centerLat = 39.908722
    private var centerLon = 116.397499

    private var markerLat: Double? = null
    private var markerLon: Double? = null

    /** 路线点，**图层坐标系**（调用方负责换算）。 */
    private val route = mutableListOf<Pair<Double, Double>>()

    private val tiles = LruCache<String, Bitmap>(240)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(4)

    private var tileOk = 0
    private var tileFail = 0
    private var fallbackDone = false

    // ------------------------------------------------------------------ 画笔

    private val bgPaint = Paint().apply { color = BG }
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GRID_LINE
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GRID_TEXT
        textSize = 26f
        typeface = Typeface.MONOSPACE
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        style = Paint.Style.FILL
    }
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BG
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val wpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wpTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BG
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val path = Path()
    private val rect = RectF()

    // ------------------------------------------------------------------ 手势

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor.toDouble()
                setZoom(zoom + ln(factor) / LN2)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                panBy(dx, dy)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val (lat, lon) = screenToLatLon(e.x, e.y)
                listener?.onPick(lat, lon, datum)
                return true
            }
        }
    )

    // ------------------------------------------------------------------ 生命周期

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pool.shutdownNow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ------------------------------------------------------------------ 对外操作

    fun setLayer(l: Layer) {
        if (layer == l) return
        layer = l
        tileOk = 0
        tileFail = 0
        fallbackDone = (l == Layer.GRID)
        invalidate()
        listener?.onViewChanged(datum, zoom)
    }

    /** 定位到某个坐标（**图层坐标系**）。 */
    fun setView(lat: Double, lon: Double, z: Double? = null) {
        centerLat = lat.coerceIn(-85.0, 85.0)
        centerLon = normalizeLon(lon)
        if (z != null) zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidate()
        listener?.onViewChanged(datum, zoom)
    }

    fun setMarker(lat: Double?, lon: Double?) {
        markerLat = lat
        markerLon = lon
        invalidate()
    }

    fun setRoute(points: List<Pair<Double, Double>>) {
        route.clear()
        route.addAll(points)
        invalidate()
    }

    fun centerZoom(): Double = zoom

    fun zoomBy(delta: Double) = setZoom(zoom + delta)

    fun currentCenter(): Pair<Double, Double> = centerLat to centerLon

    /** 缩放到刚好装下这些点（图层坐标系）。 */
    fun fitBounds(points: List<Pair<Double, Double>>, paddingPx: Int = 70) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            setView(points[0].first, points[0].second, 16.0)
            return
        }
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }

        val w = (width - paddingPx * 2).coerceAtLeast(1).toDouble()
        val h = (height - paddingPx * 2).coerceAtLeast(1).toDouble()

        var lo = MIN_ZOOM
        var hi = MAX_ZOOM
        repeat(22) {
            val mid = (lo + hi) / 2.0
            val tz = mid.toInt().coerceIn(MIN_TILE_Z, MAX_TILE_Z)
            val s = 2.0.pow(mid - tz)
            val dx = (worldX(maxLon, tz) - worldX(minLon, tz)) * s
            val dy = (worldY(minLat, tz) - worldY(maxLat, tz)) * s
            if (dx <= w && dy <= h) lo = mid else hi = mid
        }

        zoom = lo.coerceIn(MIN_ZOOM, MAX_ZOOM)
        centerLat = ((minLat + maxLat) / 2.0).coerceIn(-85.0, 85.0)
        centerLon = normalizeLon((minLon + maxLon) / 2.0)
        invalidate()
        listener?.onViewChanged(datum, zoom)
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val tz = tileZoom()
        val scale = tileScale(tz)
        val tileSize = TILE_PX * scale

        val originX = worldX(centerLon, tz) * scale - w / 2.0
        val originY = worldY(centerLat, tz) * scale - h / 2.0

        val drewAny = if (layer == Layer.GRID) {
            false
        } else {
            drawTiles(canvas, tz, scale, tileSize, originX, originY, w, h)
        }

        // 网格：要么用户主动选的，要么一张瓦片都没下来（离线兜底）
        if (layer == Layer.GRID || !drewAny) drawGrid(canvas, tz, scale, originX, originY, w, h)

        drawRoute(canvas, tz, scale, originX, originY)
        drawMarker(canvas, tz, scale, originX, originY)
    }

    private fun drawTiles(
        canvas: Canvas,
        tz: Int,
        scale: Double,
        tileSize: Double,
        originX: Double,
        originY: Double,
        w: Int,
        h: Int
    ): Boolean {
        val n = 1 shl tz
        val firstTx = floor(originX / tileSize).toInt()
        val lastTx = floor((originX + w) / tileSize).toInt()
        val firstTy = floor(originY / tileSize).toInt()
        val lastTy = floor((originY + h) / tileSize).toInt()

        var drew = false

        for (ty in firstTy..lastTy) {
            if (ty < 0 || ty >= n) continue
            for (tx in firstTx..lastTx) {
                val wrapped = ((tx % n) + n) % n
                val left = (tx * tileSize - originX).toFloat()
                val top = (ty * tileSize - originY).toFloat()

                val key = "${layer.name}/$tz/$wrapped/$ty"
                val bmp = tiles.get(key)
                if (bmp != null) {
                    rect.set(left, top, left + tileSize.toFloat(), top + tileSize.toFloat())
                    canvas.drawBitmap(bmp, null, rect, bitmapPaint)
                    drew = true
                } else {
                    requestTile(tz, wrapped, ty, key)
                }
            }
        }
        return drew
    }

    /** 离线经纬网格：完全不依赖网络，保证地图在任何情况下都能用来选点。 */
    private fun drawGrid(
        canvas: Canvas,
        tz: Int,
        scale: Double,
        originX: Double,
        originY: Double,
        w: Int,
        h: Int
    ) {
        val step = gridStep(zoom)
        val nw = worldToLatLon(originX / scale, originY / scale, tz)
        val se = worldToLatLon((originX + w) / scale, (originY + h) / scale, tz)

        val lonFrom = floor(nw.second / step) * step
        var lon = lonFrom
        var guard = 0
        while (lon <= se.second && guard++ < 120) {
            val x = (worldX(lon, tz) * scale - originX).toFloat()
            canvas.drawLine(x, 0f, x, h.toFloat(), gridPaint)
            canvas.drawText(fmtDeg(lon, step), x + 6f, 34f, gridTextPaint)
            lon += step
        }

        val latFrom = floor(se.first / step) * step
        var lat = latFrom
        guard = 0
        while (lat <= nw.first && guard++ < 120) {
            val y = (worldY(lat, tz) * scale - originY).toFloat()
            canvas.drawLine(0f, y, w.toFloat(), y, gridPaint)
            canvas.drawText(fmtDeg(lat, step), 8f, y - 8f, gridTextPaint)
            lat += step
        }
    }

    private fun drawRoute(
        canvas: Canvas,
        tz: Int,
        scale: Double,
        originX: Double,
        originY: Double
    ) {
        if (route.isEmpty()) return

        path.reset()
        route.forEachIndexed { i, p ->
            val x = (worldX(p.second, tz) * scale - originX).toFloat()
            val y = (worldY(p.first, tz) * scale - originY).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (route.size >= 2) canvas.drawPath(path, routePaint)

        route.forEachIndexed { i, p ->
            val x = (worldX(p.second, tz) * scale - originX).toFloat()
            val y = (worldY(p.first, tz) * scale - originY).toFloat()
            wpPaint.color = when (i) {
                0 -> START_GREEN
                route.size - 1 -> END_PINK
                else -> ACCENT
            }
            canvas.drawCircle(x, y, 20f, markerRing)
            canvas.drawCircle(x, y, 20f, wpPaint)
            canvas.drawText((i + 1).toString(), x, y + 8f, wpTextPaint)
        }
    }

    private fun drawMarker(
        canvas: Canvas,
        tz: Int,
        scale: Double,
        originX: Double,
        originY: Double
    ) {
        val lat = markerLat ?: return
        val lon = markerLon ?: return
        val x = (worldX(lon, tz) * scale - originX).toFloat()
        val y = (worldY(lat, tz) * scale - originY).toFloat()
        canvas.drawCircle(x, y, 22f, markerRing)
        canvas.drawCircle(x, y, 16f, markerFill)
    }

    // ------------------------------------------------------------------ 视图变换

    private fun panBy(dx: Float, dy: Float) {
        val tz = tileZoom()
        val scale = tileScale(tz)

        // GestureDetector.onScroll 给的 dx/dy 是「手指向左/向上为正」
        // （内部算的是 lastFocus - focus），也就是内容应该往哪边挪。
        // 内容往左挪 = 视口往东移 = 中心的经度变大，所以这里是 **加**。
        // 之前写成减，拖动方向就整个反了。
        val cx = worldX(centerLon, tz) + dx / scale
        val cy = worldY(centerLat, tz) + dy / scale

        val ll = worldToLatLon(cx, cy, tz)
        centerLat = ll.first
        centerLon = normalizeLon(ll.second)
        invalidate()
        listener?.onViewChanged(datum, zoom)
    }

    private fun setZoom(z: Double) {
        val nz = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(nz - zoom) < 0.001) return
        zoom = nz
        invalidate()
        listener?.onViewChanged(datum, zoom)
    }

    // ------------------------------------------------------------------ 瓦片

    private fun tileZoom(): Int = zoom.toInt().coerceIn(MIN_TILE_Z, MAX_TILE_Z)

    private fun tileScale(tz: Int): Double = 2.0.pow(zoom - tz)

    private fun tileUrl(kind: Layer, z: Int, x: Int, y: Int): String? = when (kind) {
        Layer.AMAP ->
            "https://webrd0${1 + (x + y) % 4}.is.autonavi.com/appmaptile" +
                "?lang=zh_cn&size=1&scale=1&style=8&x=$x&y=$y&z=$z"
        Layer.SAT ->
            "https://webst0${1 + (x + y) % 4}.is.autonavi.com/appmaptile" +
                "?style=6&x=$x&y=$y&z=$z"
        Layer.OSM -> "https://tile.openstreetmap.org/$z/$x/$y.png"
        Layer.GRID -> null
    }

    private fun requestTile(tz: Int, tx: Int, ty: Int, key: String) {
        if (!pending.add(key)) return
        val url = tileUrl(layer, tz, tx, ty)
        if (url == null) {
            pending.remove(key)
            return
        }
        val kind = layer
        pool.execute {
            var bmp: Bitmap? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                conn.setRequestProperty("Referer", "https://fakeloc.local/")
                conn.connect()
                if (conn.responseCode == 200) {
                    bmp = BitmapFactory.decodeStream(conn.inputStream)
                }
                runCatching { conn.disconnect() }
            } catch (_: Throwable) {
            }

            val result = bmp
            post {
                pending.remove(key)
                if (result != null) {
                    tileOk++
                    tiles.put(key, result)
                    invalidate()
                } else {
                    tileFail++
                    maybeFallback()
                }
            }
        }
    }

    /** 连续失败且一张都没成功 → 自动切离线网格，并只通知一次。 */
    private fun maybeFallback() {
        if (fallbackDone || layer == Layer.GRID) return
        if (tileOk == 0 && tileFail >= 6) {
            fallbackDone = true
            layer = Layer.GRID
            tileOk = 0
            tileFail = 0
            invalidate()
            listener?.onTileFallback()
            listener?.onViewChanged(datum, zoom)
        }
    }

    // ------------------------------------------------------------------ 投影

    private fun worldX(lon: Double, z: Int): Double {
        val n = (1 shl z).toDouble()
        return (lon + 180.0) / 360.0 * n * TILE_PX
    }

    private fun worldY(lat: Double, z: Int): Double {
        val n = (1 shl z).toDouble()
        val lat2 = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(lat2)
        val y = (1.0 - ln(tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * n
        return y * TILE_PX
    }

    private fun worldToLatLon(x: Double, y: Double, z: Int): Pair<Double, Double> {
        val n = (1 shl z).toDouble()
        val lon = x / (n * TILE_PX) * 360.0 - 180.0
        val yy = y / (n * TILE_PX)
        val lat = atan(sinh(Math.PI * (1.0 - 2.0 * yy))) * 180.0 / Math.PI
        return lat to lon
    }

    private fun screenToLatLon(sx: Float, sy: Float): Pair<Double, Double> {
        val tz = tileZoom()
        val scale = tileScale(tz)
        val originX = worldX(centerLon, tz) * scale - width / 2.0
        val originY = worldY(centerLat, tz) * scale - height / 2.0
        return worldToLatLon((originX + sx) / scale, (originY + sy) / scale, tz)
    }

    /** 把经纬度换算成屏幕坐标（给外部画自定义元素用）。 */
    fun latLonToScreen(lat: Double, lon: Double): Pair<Float, Float> {
        val tz = tileZoom()
        val scale = tileScale(tz)
        val originX = worldX(centerLon, tz) * scale - width / 2.0
        val originY = worldY(centerLat, tz) * scale - height / 2.0
        return ((worldX(lon, tz) * scale - originX).toFloat()) to
            ((worldY(lat, tz) * scale - originY).toFloat())
    }

    private fun normalizeLon(lon: Double): Double {
        var v = lon
        while (v > 180.0) v -= 360.0
        while (v < -180.0) v += 360.0
        return v
    }

    private fun fmtDeg(v: Double, step: Double): String =
        if (step < 0.01) String.format("%.3f", v) else String.format("%.1f", v)

    private fun gridStep(z: Double): Double = when {
        z >= 17 -> 0.0005
        z >= 16 -> 0.001
        z >= 15 -> 0.002
        z >= 14 -> 0.005
        z >= 13 -> 0.01
        z >= 12 -> 0.02
        z >= 11 -> 0.05
        z >= 9 -> 0.1
        z >= 7 -> 0.5
        z >= 5 -> 1.0
        else -> 5.0
    }

    companion object {
        private const val TILE_PX = 256.0
        private const val MIN_ZOOM = 3.0
        private const val MAX_ZOOM = 19.0
        private const val MIN_TILE_Z = 3
        private const val MAX_TILE_Z = 19
        private const val LN2 = 0.6931471805599453

        private val BG = Color.parseColor("#0B1220")
        private val ACCENT = Color.parseColor("#22D3EE")
        private val GRID_LINE = Color.parseColor("#3322D3EE")
        private val GRID_TEXT = Color.parseColor("#889AA7BD")
        private val START_GREEN = Color.parseColor("#34D399")
        private val END_PINK = Color.parseColor("#F472B6")

        /** 供 UI 做经纬度合法性校验。 */
        fun isValid(lat: Double, lon: Double): Boolean =
            CoordinateConverter.isValid(lat, lon)
    }
}
