package com.mo.fakeloc.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 已安装的可启动应用。 */
data class InstalledApp(val label: String, val pkg: String, val system: Boolean)

private fun loadLaunchableApps(ctx: Context): List<InstalledApp> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved.mapNotNull { ri ->
        val ai = ri.activityInfo ?: return@mapNotNull null
        val pkg = ai.packageName ?: return@mapNotNull null
        if (pkg == ctx.packageName) return@mapNotNull null
        InstalledApp(
            label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg),
            pkg = pkg,
            system = ((ai.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
        )
    }.distinctBy { it.pkg }.sortedBy { it.label }
}

/**
 * 作用域白名单选择。
 *
 * 说明：模块是否被注入某个应用，**首先**由 LSPosed 的作用域决定；
 * 这里的白名单是在"已经注入"的前提下再做一次收窄，用来避免影响
 * 不想伪造位置的 App（比如导航、运动记录）。
 * 因此系统进程 `android` / `com.android.location.fused` 恒定放行，不在此列表中。
 */
@Composable
fun AppScopeDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val apps = remember { loadLaunchableApps(ctx) }
    var chosen by remember { mutableStateOf(selected) }
    var keyword by remember { mutableStateOf("") }

    val filtered = remember(keyword, apps) {
        if (keyword.isBlank()) apps
        else apps.filter {
            it.label.contains(keyword, true) || it.pkg.contains(keyword, true)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "作用域白名单（已选 ${chosen.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "留空 = 对所有已注入进程生效。此设置不能替代 LSPosed 作用域。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("搜索") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .padding(vertical = 6.dp)
                ) {
                    items(filtered, key = { it.pkg }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chosen = if (chosen.contains(app.pkg)) {
                                        chosen - app.pkg
                                    } else {
                                        chosen + app.pkg
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Checkbox(
                                checked = chosen.contains(app.pkg),
                                onCheckedChange = { checked ->
                                    chosen = if (checked) chosen + app.pkg else chosen - app.pkg
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    app.label + if (app.system) "  ·系统" else "",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    app.pkg,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(onClick = { chosen = emptySet() }, modifier = Modifier.weight(1f)) {
                        Text("清空")
                    }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    Button(onClick = { onConfirm(chosen) }, modifier = Modifier.weight(1f)) {
                        Text("确定")
                    }
                }
            }
        }
    }
}
