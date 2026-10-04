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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Share
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
    onOpenStats: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenAdmin: () -> Unit,
) {
    var status by remember { mutableStateOf(shizukuStatusText()) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        status = shizukuStatusText()
        delay(1500)
        tick++
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

        SettingsGroup("核心功能")
        SettingsEntry(
            icon = Icons.Default.Star,
            title = "方案库",
            subtitle = "常用 App 一键配置，误删可一键恢复",
            onClick = onOpenPresets
        )
        SettingsEntry(
            icon = Icons.Default.PlayArrow,
            title = "场景模式",
            subtitle = "方案集合一键启用/停用",
            onClick = onOpenScenes
        )
        SettingsEntry(
            icon = Icons.Default.PlayArrow,
            title = "专注会话",
            subtitle = "一键锁定所选 App，到时自动恢复",
            onClick = onOpenFocus
        )
        SettingsEntry(
            icon = Icons.Default.DateRange,
            title = "定时计划",
            subtitle = "睡眠模式：指定时段自动全部上锁",
            onClick = onOpenSchedules
        )
        SettingsEntry(
            icon = Icons.Default.Info,
            title = "时长限额",
            subtitle = "每日使用上限，超额当天拦截",
            onClick = onOpenQuota
        )
        SettingsGroup("数据与工具")
        SettingsEntry(
            icon = Icons.Default.Notifications,
            title = "拦截统计",
            subtitle = "今日与累计拦截次数、最近事件",
            onClick = onOpenStats
        )
        SettingsEntry(
            icon = Icons.Default.List,
            title = "锁定历史",
            subtitle = "历史记录查询，删除后也能找回",
            onClick = onOpenHistory
        )
        SettingsEntry(
            icon = Icons.Default.Search,
            title = "Activity 查询",
            subtitle = "悬浮显示当前界面的包名和类名",
            onClick = onOpenInspector
        )
        SettingsEntry(
            icon = Icons.Default.Share,
            title = "URL Scheme 库",
            subtitle = "常用深链查询、复制与测试",
            onClick = onOpenSchemes
        )
        SettingsGroup("系统")
        SettingsEntry(
            icon = Icons.Default.Build,
            title = "连接",
            subtitle = "Shizuku 状态与授权",
            onClick = onOpenConnection
        )
        SettingsEntry(
            icon = Icons.Default.Build,
            title = "防卸载保护",
            subtitle = "激活后无法直接卸载或清除数据",
            onClick = onOpenAdmin
        )
        SettingsEntry(
            icon = Icons.Default.Lock,
            title = "解锁验证",
            subtitle = "全局默认条件与防绕过",
            onClick = onOpenVerify
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "应用锁 v0.4.0",
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
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
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
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
