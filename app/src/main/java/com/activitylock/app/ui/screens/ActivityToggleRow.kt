package com.activitylock.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.LockEngine
import com.activitylock.app.ui.components.AppIcon
import kotlinx.coroutines.launch

/**
 * 单个 Activity 行：显示状态开关。锁定成功后自动加入收藏夹。
 * 组件禁用受系统限制时自动降级为拦截模式。
 */
@Composable
fun ActivityToggleRow(
    packageName: String,
    cls: String,
    title: String,
    subtitle: String,
    isLauncher: Boolean = false,
    showAppIcon: Boolean = false,
) {
    val key = "$packageName/$cls"
    val scope = rememberCoroutineScope()
    var busy by remember(key) { mutableStateOf(false) }
    var locked by remember(key) { mutableStateOf(AppStore.isDisabled(packageName, cls)) }
    var mode by remember(key) { mutableStateOf("pm") }
    var confirmLock by remember { mutableStateOf(false) }

    fun doApply(wantLock: Boolean) {
        busy = true
        scope.launch {
            val o = LockEngine.apply(packageName, cls, wantLock)
            if (o.locked) {
                locked = true
                mode = o.mode
                AppStore.toast(
                    if (o.mode == "block") "已改用拦截模式：该界面打开时自动退出"
                    else "锁定完成（组件已禁用）"
                )
            } else {
                locked = AppStore.isDisabled(packageName, cls)
                if (!locked) mode = "pm"
                AppStore.toast("操作失败：${o.detail}")
            }
            busy = false
        }
    }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showAppIcon) {
                AppIcon(packageName, 40.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isLauncher) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "桌面入口",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        locked && mode == "block" -> "🚫 拦截模式（启动即拦截）"
                        locked -> "🔒 已禁用"
                        else -> "正常"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (locked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = locked,
                enabled = !busy,
                onCheckedChange = { wantLock ->
                    if (wantLock && isLauncher && !locked) {
                        confirmLock = true
                    } else {
                        doApply(wantLock)
                    }
                }
            )
        }
    }

    if (confirmLock) {
        AlertDialog(
            onDismissRequest = { confirmLock = false },
            title = { Text("确认锁定桌面入口？") },
            text = {
                Text(
                    "该界面是应用在桌面上的主入口。锁定后：\n\n" +
                        "· 桌面图标可能会消失\n" +
                        "· 无法再从桌面直接打开该应用\n" +
                        "· 应用内其他功能不受影响\n\n" +
                        "之后可以随时在本 App 的收藏夹中解除锁定。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLock = false
                    doApply(true)
                }) { Text("仍然锁定") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLock = false }) { Text("取消") }
            }
        )
    }
}
