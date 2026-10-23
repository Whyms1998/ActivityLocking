package com.activitylock.app.ui.screens

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.AdminReceiver
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.ShizukuManager

/**
 * 启动条件检查：强制类功能（拦截、限额、计划、专注）的可靠性
 * 依赖这些条件——任何一项不满足，对应功能就可能失效。
 * 参考 FrostHush 可靠性检查与不做手机控的权限引导。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReliabilityScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }

    // ★ 「刷新」按钮为什么之前没反应：这些检查项全是普通函数调用（背后不是 Compose
    //   state），如果不在组合中读取 refreshTick，点击只是写了一次无人消费的状态 →
    //   不触发重组，界面永远停留在首次进入时的采样结果。
    //   用 remember(refreshTick) 把每次采样的结果固定下来即可让刷新真正生效。
    val checks = remember(refreshTick) {
        listOf(
            "Shizuku 已连接并授权" to (ShizukuManager.installed() && ShizukuManager.granted()),
            "拦截进程存活" to AmsController.isRunning(),
            "防卸载保护（设备管理员）" to isActiveAdmin(ctx),
            "通知权限" to notificationsEnabled(ctx),
            "电池优化已豁免" to batteryExempt(ctx),
        )
    }
    val adminOk = checks[2].second
    val batteryOk = checks[4].second

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("启动条件检查", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "拦截、限额、计划、专注的强制力依赖以下条件——任何一项失效，对应功能就可能被系统绕过。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            items(checks) { (title, ok) -> CheckRow(title, ok) }
            item {
                Column(Modifier.padding(16.dp)) {
                    if (!adminOk) {
                        Button(onClick = {
                            ctx.startActivity(
                                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                    .putExtra(
                                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                        ComponentName(ctx, AdminReceiver::class.java)
                                    )
                                    .putExtra(
                                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                        "激活后「应用锁」无法被直接卸载，用于保障自律计划不被绕过。"
                                    )
                            )
                        }, modifier = Modifier.fillMaxWidth()) { Text("激活防卸载保护") }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (!batteryOk) {
                        Button(onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }, modifier = Modifier.fillMaxWidth()) { Text("去设置电池优化豁免") }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "电池豁免路径：设置 → 省电与电池 → 应用智能省电 → 应用锁 → 无限制。" +
                            "同时建议在最近任务中锁定本 App。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun isActiveAdmin(ctx: Context): Boolean {
    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
    return dpm.isAdminActive(ComponentName(ctx, AdminReceiver::class.java))
}

private fun notificationsEnabled(ctx: Context): Boolean =
    androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()

private fun batteryExempt(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

@Composable
private fun CheckRow(title: String, ok: Boolean) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                // ★ 规范 §1：原来 "title · 未满足" 用中点串状态 → 括号收进标题里
                title + (if (ok) "" else "（未满足）"),
                style = MaterialTheme.typography.bodyMedium,
                color = if (ok) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error
            )
        }
    }
}
