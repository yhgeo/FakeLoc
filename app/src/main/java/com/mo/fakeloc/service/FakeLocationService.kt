package com.mo.fakeloc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mo.fakeloc.MainActivity
import com.mo.fakeloc.R
import com.mo.fakeloc.data.ConfigStore
import com.mo.fakeloc.data.LocConfig
import com.mo.fakeloc.root.RootHelper

/**
 * 常驻前台服务：整条链路的"大脑"。
 *
 * 职责有三：
 * 1. **路线播放** —— 每秒按设定速度推进路线，把新坐标写进配置；
 *    hook 端 1 秒内通过配置通道读到，位置就动起来了。
 * 2. **开发者模拟位置兜底** —— 没有 LSPosed 时，用 `addTestProvider` /
 *    `setTestProviderLocation` 走系统自带的模拟位置通道（需在开发者选项里
 *    把本应用选为"模拟位置信息应用"）。
 * 3. **保活与状态展示** —— 前台通知显示当前伪造坐标，方便一眼确认在跑。
 */
class FakeLocationService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var travelledMeters = 0.0
    private var testProviderReady = false
    private var tickCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_REFRESH, null -> startEverything()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTicking()
        teardownTestProvider()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 启停

    private fun startEverything() {
        val cfg = ConfigStore.load(this)
        if (!cfg.enabled) {
            stopEverything()
            return
        }

        startForegroundCompat(buildNotification(cfg))

        // 只在真正开始的时候把配置备份到 root 目录（避免每秒一次 su 调用）
        Thread {
            try {
                RootHelper.backupConfig(this, cfg.toJson())
                RootHelper.publishRelay(cfg.toJson())
            } catch (_: Throwable) {
            }
        }.start()

        if (ConfigStore.engineMode(this) == ConfigStore.MODE_MOCK_PROVIDER) {
            setupTestProvider()
        }

        startTicking()
    }

    private fun stopEverything() {
        stopTicking()
        teardownTestProvider()
        stopForegroundCompat()
        stopSelf()
    }

    // ------------------------------------------------------------------ 定时推进

    private fun startTicking() {
        stopTicking()
        val r = object : Runnable {
            override fun run() {
                try {
                    onTick()
                } catch (t: Throwable) {
                    Log.e(TAG, "tick failed", t)
                }
                handler.postDelayed(this, TICK_MS)
            }
        }
        ticker = r
        handler.post(r)
    }

    private fun stopTicking() {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    private fun onTick() {
        val cfg = ConfigStore.load(this)
        if (!cfg.enabled) {
            stopEverything()
            return
        }
        tickCount++

        // ---- 路线推进 ----
        val route = ConfigStore.loadRoute(this)
        if (route.size >= 2) {
            val speedMs = ConfigStore.routeSpeedKmh(this) * 1000.0 / 3600.0
            val loop = ConfigStore.routeLoop(this)
            travelledMeters += speedMs * (TICK_MS / 1000.0)

            val pose = RouteEngine.poseAt(route, travelledMeters, loop)
            if (pose != null) {
                cfg.latitude = pose.lat
                cfg.longitude = pose.lon
                cfg.bearing = pose.bearing
                cfg.speed = speedMs.toFloat()
                ConfigStore.save(this, cfg)

                if (pose.finished && !loop) {
                    Log.i(TAG, "route finished")
                    travelledMeters = 0.0
                }
            }
        }

        // ---- 开发者模拟位置兜底 ----
        if (ConfigStore.engineMode(this) == ConfigStore.MODE_MOCK_PROVIDER) {
            publishMockLocation(cfg)
        }

        // ---- 通知节流更新 ----
        if (tickCount % 3 == 0) {
            notify(buildNotification(cfg))
        }
    }

    // ------------------------------------------------------------------ 开发者模拟位置

    @Suppress("DEPRECATION")
    private fun setupTestProvider() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        try {
            if (!testProviderReady) {
                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                testProviderReady = true
                Log.i(TAG, "test provider ready")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "addTestProvider failed: 需要先在开发者选项里把本应用设为模拟位置应用", t)
        }
    }

    private fun teardownTestProvider() {
        if (!testProviderReady) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        try {
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            lm.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Throwable) {
        }
        testProviderReady = false
    }

    private fun publishMockLocation(cfg: LocConfig) {
        val lm = getSystemService(LocationManager::class.java) ?: return
        try {
            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = cfg.latitude
                longitude = cfg.longitude
                altitude = cfg.altitude
                accuracy = cfg.accuracy
                speed = cfg.speed
                bearing = cfg.bearing
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            // API 31+ 要求测试 provider 的位置必须带 mock 标记，否则系统拒收。
            // setMock 是隐藏 API，用反射调，避免不同版本编译期签名差异。
            try {
                Location::class.java
                    .getMethod("setMock", Boolean::class.javaPrimitiveType)
                    ?.invoke(loc, true)
            } catch (_: Throwable) {
            }
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        } catch (t: Throwable) {
            Log.w(TAG, "setTestProviderLocation failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "虚拟定位运行状态"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(cfg: LocConfig): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FakeLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mode = when (ConfigStore.engineMode(this)) {
            ConfigStore.MODE_MOCK_PROVIDER -> "模拟位置通道"
            else -> "LSPosed 通道"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("虚拟定位运行中 · $mode")
            .setContentText(
                String.format(
                    "%.6f, %.6f  精度%.0fm  %.0fkm/h",
                    cfg.latitude, cfg.longitude, cfg.accuracy, cfg.speed * 3.6f
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent)
            .addAction(0, "停止", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(n: Notification) {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFY_ID, n)
        } catch (_: Throwable) {
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFY_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val TAG = "FakeLoc/Service"
        private const val CHANNEL_ID = "fakeloc_run"
        private const val NOTIFY_ID = 0x10C
        private const val TICK_MS = 1000L

        const val ACTION_START = "com.mo.fakeloc.action.START"
        const val ACTION_STOP = "com.mo.fakeloc.action.STOP"
        const val ACTION_REFRESH = "com.mo.fakeloc.action.REFRESH"

        fun start(ctx: Context) {
            val i = Intent(ctx, FakeLocationService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun refresh(ctx: Context) {
            val i = Intent(ctx, FakeLocationService::class.java).setAction(ACTION_REFRESH)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FakeLocationService::class.java).setAction(ACTION_STOP))
        }
    }
}
