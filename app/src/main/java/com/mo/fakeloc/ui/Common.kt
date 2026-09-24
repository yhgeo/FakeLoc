package com.mo.fakeloc.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mo.fakeloc.data.HookReport
import com.mo.fakeloc.ui.theme.ErrRed
import com.mo.fakeloc.ui.theme.OkGreen
import com.mo.fakeloc.ui.theme.WarnAmber

// ==================================================================== 格式化 / 工具

internal fun fmt6(v: Double) = String.format("%.6f", v)

internal fun fmt1(v: Double) = String.format("%.1f", v)

internal fun fmt2(v: Double) = String.format("%.2f", v)

/** LSPosed 管理器的常见包名。 */
internal val LSPOSED_PACKAGES = listOf(
    "org.lsposed.manager",
    "org.lsposed.manager.debug"
)

internal fun findLsposed(ctx: Context): String? = LSPOSED_PACKAGES.firstOrNull { pkg ->
    try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }
}

/** 把时间戳说成人话。 */
internal fun agoText(seenAt: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - seenAt).coerceAtLeast(0L)
    return when {
        d < 3_000L -> "刚刚"
        d < 60_000L -> "${d / 1000} 秒前"
        d < 3_600_000L -> "${d / 60_000} 分钟前"
        else -> "${d / 3_600_000} 小时前"
    }
}

/** km/min → km/h，界面上两个都给，方便对照。 */
internal fun kmPerMinToKmh(v: Double): Double = v * 60.0

// ==================================================================== 通用组件

/**
 * 每个分页统一的外层：占满 + 可滚动 + 统一留白。
 *
 * 底部额外留出导航栏的高度 —— `enableEdgeToEdge()` 之后内容会画到导航栏下面，
 * 实测最后一个按钮的 bounds 能到 y=2396（屏幕高 2400），基本点不到。
 */
@Composable
internal fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
internal fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
internal fun StatusRow(label: String, state: Boolean?, text: String? = null) {
    val (color, value) = when (state) {
        true -> OkGreen to (text ?: "正常")
        false -> ErrRed to (text ?: "不可用")
        null -> WarnAmber to (text ?: "未知 / 检测中")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 一行「标签 —— 值」，值用中性色，用于展示纯信息。 */
@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
internal fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun LabeledSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    display: String,
    onChange: (Double) -> Unit,
    steps: Int = 0
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                display,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            // Material3 的 Slider 是 Float 实现，这里做一次转换，
            // 对外仍然保持 Double 接口（经纬度/速度都是 Double 语义）
            value = value.coerceIn(range.start, range.endInclusive).toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = steps
        )
    }
}

/**
 * 一条 hook 回执。
 *
 * 读法：
 *  - 有「hook 摘要」= 该进程真的被注入了，`LM:20` 表示挂了 20 个 LocationManager 相关的点；
 *  - 「配置」显示该进程读到的 seq 与开关。如果 App 已经开了总开关，
 *    这里却还是「关」，说明配置通道没打通（而不是 hook 没挂上）。
 */
@Composable
internal fun HookReportRow(r: HookReport, now: Long) {
    val alive = r.isAlive(now)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                r.pkg,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (alive) OkGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                agoText(r.seenAt, now),
                style = MaterialTheme.typography.labelSmall,
                color = if (alive) OkGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            buildString {
                if (r.process != r.pkg) append("进程 ${r.process} · ")
                append("hook ")
                append(r.hooks.ifEmpty { "无" })
                append(" · 通道 ")
                append(r.channel.ifEmpty { "?" })
                append(" · 配置#")
                append(r.cfgSeq)
                append(if (r.cfgEnabled) "（开）" else "（关）")
                append(" · v")
                append(r.moduleVersion)
                append(" · ${r.hits} 次")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
