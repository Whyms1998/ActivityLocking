package com.activitylock.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.ShizukuManager
import kotlinx.coroutines.delay

private fun shizukuStatusText(): String = when {
    !ShizukuManager.installed() -> "未检测到 Shizuku 服务"
    !ShizukuManager.granted() -> "已连接，未授权"
    else -> "已连接并授权"
}

@Composable
fun SettingsScreen(
    onOpenConnection: () -> Unit,
    onOpenInspector: () -> Unit,
    onOpenSchemes: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenVerify: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenQuota: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenStrength: () -> Unit,
    onOpenAutoRelock: () -> Unit,
) {
    // Shizuku 状态实时刷新，显示在「连接」入口的副标题里。
    // （原先这段轮询算了值却没有任何地方消费，属于无效刷新。）
    var status by remember { mutableStateOf(shizukuStatusText()) }
    LaunchedEffect(Unit) {
        while (true) {
            status = shizukuStatusText()
            delay(1500)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        // ---------- 核心自律 ----------
        // ★「解锁验证」原先被归在「系统」组、排在「防卸载保护」旁边 —— 它是全 App
        //   最核心的自律机制（决定所有界面怎么解锁），却和运维项混在一起，位置完全不对。
        SettingsGroup("核心自律")
        SettingsEntry(
            icon = Icons.Default.Fingerprint,
            title = "解锁验证",
            onClick = onOpenVerify
        )
        SettingsEntry(
            icon = Icons.Default.Policy,
            title = "锁定强度",
            onClick = onOpenStrength
        )

        // ---------- 自动执行 ----------
        // ★ 自动锁闭 / 自动冻结 归到同一组，副标题明确区分「图标保留」vs「整包禁用」，
        //   避免这两个名字相近、行为不同（一个是黑名单、一个是白名单）的功能互相混淆。
        SettingsGroup("自动执行")
        SettingsEntry(
            icon = Icons.Default.LockClock,
            title = "自动锁闭",
            // ★ 规范 §1：原来「解锁后 / 退出后 / 锁屏后重新上锁 · 图标保留、点击被拦」，
            //   一条副标题里塞了 3 个时机 + 2 个特性、还用中点串了起来 → 顿号 + 括号归拢
            onClick = onOpenAutoRelock
        )
        SettingsEntry(
            icon = Icons.Default.DateRange,
            title = "定时计划",
            onClick = onOpenSchedules
        )

        // ---------- 专注与限额 ----------
        SettingsGroup("专注与限额")
        SettingsEntry(
            icon = Icons.Default.Timer,
            title = "专注会话",
            onClick = onOpenFocus
        )
        SettingsEntry(
            icon = Icons.Default.HourglassBottom,
            title = "时长限额",
            onClick = onOpenQuota
        )
        SettingsEntry(
            icon = Icons.Default.AutoAwesome,
            title = "场景模式",
            onClick = onOpenScenes
        )

        // ---------- 方案与工具 ----------
        // 「拦截统计」已从此处移除：底部 Tab 已有独立的「统计」，同功能双入口是混乱来源之一。
        SettingsGroup("方案与工具")
        SettingsEntry(
            icon = Icons.Default.CollectionsBookmark,
            title = "方案库",
            onClick = onOpenPresets
        )
        SettingsEntry(
            icon = Icons.Default.HistoryToggleOff,
            title = "锁定历史",
            onClick = onOpenHistory
        )
        SettingsEntry(
            icon = Icons.Default.Search,
            title = "Activity 查询",
            onClick = onOpenInspector
        )
        SettingsEntry(
            icon = Icons.Default.Link,
            title = "URL Scheme 库",
            onClick = onOpenSchemes
        )

        // ---------- 系统 ----------
        SettingsGroup("系统")
        SettingsEntry(
            icon = Icons.Default.Cable,
            title = "连接",
            subtitle = "Shizuku：$status",
            onClick = onOpenConnection
        )
        SettingsEntry(
            icon = Icons.Default.AdminPanelSettings,
            title = "防卸载保护",
            onClick = onOpenAdmin
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "应用锁 v${com.activitylock.app.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun SettingsGroup(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                // 副标题默认不渲染：标题本身已说明功能，附注反而添乱
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
