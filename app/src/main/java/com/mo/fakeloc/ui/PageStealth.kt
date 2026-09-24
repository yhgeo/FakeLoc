package com.mo.fakeloc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mo.fakeloc.data.ConfigStore
import com.mo.fakeloc.service.FakeLocationService

/**
 * 反检测页：mock 标记、抖动、网络定位阻断、作用域白名单、工作模式。
 */
@Composable
internal fun PageStealth(st: AppState) {
    val ctx = st.context()

    PageColumn {

        SectionCard(
            "反检测",
            "目标应用如果会做完整性校验，这里的开关能挡掉大部分「一眼假」的特征。"
        ) {
            SwitchRow(
                title = "隐藏模拟位置标记",
                desc = "抹掉 Location.isMock 与 extras 里的 mockLocation，规避简单检测",
                checked = st.cfg.hideMock,
                onChange = { st.persist(st.cfg.copy(hideMock = it)) }
            )
            SwitchRow(
                title = "自然抖动",
                desc = "让坐标以米级幅度缓慢漂移，避免「纹丝不动」这一特征",
                checked = st.cfg.jitterEnabled,
                onChange = { st.persist(st.cfg.copy(jitterEnabled = it)) }
            )
            LabeledSlider(
                label = "抖动幅度",
                value = st.cfg.jitterMeters,
                range = 0.0..20.0,
                display = "${fmt1(st.cfg.jitterMeters)} m",
                onChange = { st.persist(st.cfg.copy(jitterMeters = it)) }
            )

            SwitchRow(
                title = "阻断网络定位（WiFi / 基站）",
                desc = "腾讯/高德/百度的 SDK 会把 WiFi BSSID + 基站指纹上报服务端算位置，" +
                    "这条路径绕过系统定位，不掐掉会出现「位置被拉回真实坐标」",
                checked = st.cfg.blockNetworkPos,
                onChange = { st.persist(st.cfg.copy(blockNetworkPos = it)) }
            )
        }

        SectionCard(
            "作用域白名单",
            "默认对所有已注入的进程生效；开了白名单就只对选中的应用生效。"
        ) {
            SwitchRow(
                title = "启用作用域白名单",
                desc = "只对选定应用生效；系统进程恒定放行",
                checked = st.cfg.scopeWhitelistEnabled,
                onChange = { st.persist(st.cfg.copy(scopeWhitelistEnabled = it)) }
            )
            OutlinedButton(
                onClick = { st.showScope = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("选择应用（已选 ${st.cfg.scopePackages.size}）")
            }
            Hint(
                "注意：LSPosed 自己那边的作用域是另一回事 —— 没勾进 LSPosed 作用域的应用，" +
                    "hook 根本不会挂上去，这里的白名单也就无从谈起。"
            )
        }

        SectionCard("工作模式") {
            Text(
                "写入位置的方式",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = st.engineMode == ConfigStore.MODE_LSPOSED,
                    onClick = {
                        st.engineMode = ConfigStore.MODE_LSPOSED
                        ConfigStore.saveEngineMode(ctx, ConfigStore.MODE_LSPOSED)
                        FakeLocationService.refresh(ctx)
                    },
                    label = { Text("LSPosed（推荐）") }
                )
                FilterChip(
                    selected = st.engineMode == ConfigStore.MODE_MOCK_PROVIDER,
                    onClick = {
                        st.engineMode = ConfigStore.MODE_MOCK_PROVIDER
                        ConfigStore.saveEngineMode(ctx, ConfigStore.MODE_MOCK_PROVIDER)
                        FakeLocationService.refresh(ctx)
                    },
                    label = { Text("开发者模拟位置") }
                )
            }
            Hint(
                if (st.engineMode == ConfigStore.MODE_LSPOSED) {
                    "LSPosed 通道：直接改定位管线里的返回值，隐蔽性最好。"
                } else {
                    "开发者模拟位置：不依赖 LSPosed，但需要在开发者选项里把本应用选为" +
                        "「模拟位置信息应用」，而且很多应用能检测到 mock 标记。"
                }
            )
        }
    }
}
