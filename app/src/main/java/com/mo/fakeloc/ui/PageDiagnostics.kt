package com.mo.fakeloc.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mo.fakeloc.BuildConfig
import com.mo.fakeloc.data.ConfigStore
import com.mo.fakeloc.root.RootHelper
import com.mo.fakeloc.ui.theme.WarnAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断页：LSPosed 接入状态、root 辅助、版本信息。
 *
 * 「已注入进程」是排查"LSPosed 到底有没有生效"的第一手证据 ——
 * hook 每次来取配置时会顺路报一次到，这里轮询展示。
 */
@Composable
internal fun PageDiagnostics(st: AppState) {
    val ctx = st.context()
    val scope = rememberCoroutineScope()

    PageColumn {

        SectionCard(
            "LSPosed 接入状态",
            "hook 每次来取配置时会顺路报一次到。看到「已注入进程」才算真的生效 —— " +
                "否则定位不会变。"
        ) {
            val alive = st.hookReports.filter { it.isAlive(st.nowTick) }
            StatusRow(
                "已注入进程",
                alive.isEmpty(),
                if (alive.isEmpty()) {
                    "0（还没有任何进程挂上 hook）"
                } else {
                    "${alive.size} 个正在回报 / 共 ${st.hookReports.size} 条记录"
                }
            )

            if (st.hookReports.isEmpty()) {
                Hint(
                    "① 在 LSPosed 里启用本模块；② 作用域勾选目标应用（默认只勾了系统框架）；" +
                        "③ 重启目标应用 —— hook 只在进程启动时挂载，不重启不生效。" +
                        "④ 若只勾了系统框架，system_server 应该会回报。",
                    WarnAmber
                )
            } else {
                st.hookReports.take(8).forEach { r ->
                    HookReportRow(r, st.nowTick)
                }
                if (st.hookReports.size > 8) {
                    Hint("仅显示最近 8 条，共 ${st.hookReports.size} 条")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        st.refreshReports()
                        st.toast("已刷新（${st.hookReports.size} 条）")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("  刷新")
                }
                OutlinedButton(
                    onClick = {
                        st.clearReports()
                        st.toast("已清空回执记录")
                    },
                    enabled = st.hookReports.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("清空回执") }
                OutlinedButton(
                    onClick = {
                        val pkg = st.lsposedPkg ?: LSPOSED_PACKAGES.first()
                        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
                        if (i == null) {
                            st.toast("打不开 LSPosed 管理器")
                        } else {
                            try {
                                ctx.startActivity(
                                    Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Throwable) {
                                st.toast("打不开 LSPosed 管理器")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("打开 LSPosed") }
            }
        }

        SectionCard(
            "Root 辅助（可选）",
            "把配置备份到 /data/adb/fakeloc/，清数据或重装后可恢复；" +
                "同时会在 /data/local/tmp 放一份世界可读副本给 hook 读。"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                RootHelper.backupConfig(ctx, st.cfg.toJson())
                            }
                            st.toast(if (ok) "已备份" else "备份失败（无 root？）")
                        }
                    },
                    enabled = st.rootState == true,
                    modifier = Modifier.weight(1f)
                ) { Text("备份配置") }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { RootHelper.restoreInto(ctx) }
                            if (ok) {
                                st.reloadFromStore()
                                st.toast("已从备份恢复")
                            } else {
                                st.toast("没有可恢复的备份")
                            }
                        }
                    },
                    enabled = st.rootState == true,
                    modifier = Modifier.weight(1f)
                ) { Text("恢复配置") }
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            RootHelper.publishRelay(st.cfg.toJson())
                        }
                        st.toast(if (ok) "已发布到 Settings 中继" else "发布失败（无 root？）")
                    }
                },
                enabled = st.rootState == true,
                modifier = Modifier.fillMaxWidth()
            ) { Text("手动发布配置中继") }
        }

        SectionCard("关于") {
            InfoRow("版本", "v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            InfoRow("包名", "com.mo.fakeloc")
            InfoRow("配置存储", "prefs: ${ConfigStore.PREFS_NAME}")
            InfoRow("工作模式", if (st.engineMode == ConfigStore.MODE_LSPOSED) "LSPosed" else "开发者模拟位置")
            Hint(
                "排查思路：定位不变 → 先看「已注入进程」；位置对但不动 → 看配置文件有没有在刷新；" +
                    "位置被拉回真实坐标 → 打开「阻断网络定位」。"
            )
        }
    }
}
