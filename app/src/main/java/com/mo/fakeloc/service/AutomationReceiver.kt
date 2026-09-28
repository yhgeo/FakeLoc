package com.mo.fakeloc.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mo.fakeloc.data.ConfigStore

/**
 * 自动化入口：允许外部工具通过**显式广播**启停路线模拟。
 *
 * 为什么单独开一个 Receiver，而不是把 Service 改成 exported：
 *  - Service 保持 `exported=false` 是刻意的 —— 不给任何 App "直接拉起模拟" 的能力；
 *  - 这里只开放两个语义明确的动作，且**只能改「是否跑路线」**，
 *    改不了坐标、配速、反检测开关（那些仍只在 UI 内可控）；
 *  - 用显式广播（带 package 或 component）调用，不受 Android 8+ 隐式广播限制。
 *
 * 为什么不加自定义权限：
 *  加 signature 级权限会让 Tasker / MacroDroid / AutoX.js 等第三方工具无法调用，
 *  而这两个动作既不泄露数据、也不改变伪造内容，扩大不了实质攻击面。
 *
 * 调用方式：
 * ```
 * adb shell am broadcast -a com.mo.fakeloc.automation.START_ROUTE -p com.mo.fakeloc
 * adb shell am broadcast -a com.mo.fakeloc.automation.STOP_ROUTE  -p com.mo.fakeloc
 * ```
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_ROUTE -> {
                // 与 UI 的 startRoute() 完全一致：先落盘开关，再启服务。
                // 顺序不能反 —— 服务 onStartCommand 会重新 load 配置，
                // 读到 enabled=false 会立刻 stopEverything 退出。
                ConfigStore.saveRouteRunning(ctx, true)
                ConfigStore.save(ctx, ConfigStore.load(ctx).copy(enabled = true))
                FakeLocationService.start(ctx)
                Log.i(TAG, "start route via broadcast")
            }

            ACTION_STOP_ROUTE -> {
                ConfigStore.saveRouteRunning(ctx, false)
                ConfigStore.save(ctx, ConfigStore.load(ctx).copy(enabled = false))
                // 服务可能已不在运行，stop 会抛 IllegalStateException（后台服务启动限制），
                // 这里吞掉即可 —— 状态已经落盘，服务停不停都不影响正确性。
                runCatching { FakeLocationService.stop(ctx) }
                    .onFailure { Log.w(TAG, "stop service failed: ${it.message}") }
                Log.i(TAG, "stop route via broadcast")
            }

            else -> Log.w(TAG, "unknown action: ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "FakeLoc/Automation"

        /** 启动路线模拟。等价于 UI 的「开始路线」按钮。 */
        const val ACTION_START_ROUTE = "com.mo.fakeloc.automation.START_ROUTE"

        /** 停止路线模拟并关闭总开关。等价于 UI 的「停止路线」+「总开关关闭」。 */
        const val ACTION_STOP_ROUTE = "com.mo.fakeloc.automation.STOP_ROUTE"
    }
}
