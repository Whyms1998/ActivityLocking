package com.activitylock.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.AutoRelock
import com.activitylock.app.core.AutoRelockService
import com.activitylock.app.core.AutoRelockStore

/**
 * 自动锁闭（自动重锁）设置 —— **仅全局**，一页放得下。
 *
 * 作用域 = 收藏夹（锁定清单）里全部条目：解锁哪个界面，到点重锁哪个界面。
 * 三条触发链路各自独立：开关 + 延迟（秒）。
 * 削弱性修改（关闭 / 调大延迟）走解锁验证；打开 / 调小不需要。
 *
 * ★ v2 简化：应用级 / 界面级三层覆盖已整体移除（历史累计十余轮假开关补丁，
 *   结论是粒度伪需求——见 docs/优化改善方案-v2.md §1.1）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoRelockScreen(
    onBack: () -> Unit,
    onOpenVerify: () -> Unit = {},
) {
    val ctx = LocalContext.current
    AppStore.ensureInit(ctx)
    AutoRelock.init(ctx)

    /** 削弱性修改的验证闸门。 */
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** 正在编辑延迟的链路（AutoRelockStore.LINK_*）。 */
    var valueLink by remember { mutableStateOf<Int?>(null) }

    fun requireVerify(action: () -> Unit) {
        val req = com.activitylock.app.core.VerifyGate.bulkRequirement(ctx, AppStore.rules)
        if (req.isNone) action() else pendingAction = action
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("自动锁闭", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "解锁过的界面，到点自动锁回——防止\"用完忘了锁\"。\n" +
                        "作用范围 = 收藏夹里全部条目（解锁哪个，重锁哪个）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.height(12.dp))

            val anyOn = AutoRelock.anyLinkEnabled
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用自动锁闭", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (anyOn) "三条链路按下方设置生效"
                            else "关闭后三条链路全部停止",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = anyOn,
                        onCheckedChange = { want ->
                            if (want) AutoRelock.setAllLinks(true)
                            else requireVerify { AutoRelock.setAllLinks(false) }
                            AutoRelockService.sync(ctx)
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "触发时机与延迟",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            SceneRowCard(
                title = "解锁后",
                desc = "通过验证解锁后开始计时，到点锁回",
                link = AutoRelockStore.LINK_UNLOCK,
                delaySec = AutoRelock.globalDelaySec,
                onToggle = { want ->
                    if (want) AutoRelock.setRelockAfterUnlock(true)
                    else requireVerify { AutoRelock.setRelockAfterUnlock(false) }
                    AutoRelockService.sync(ctx)
                },
                onEditDelay = { valueLink = AutoRelockStore.LINK_UNLOCK },
            )
            Spacer(Modifier.height(10.dp))
            SceneRowCard(
                title = "退出后",
                desc = "应用切到后台开始计时，到点锁回",
                link = AutoRelockStore.LINK_EXIT,
                delaySec = AutoRelock.globalExitDelaySec,
                onToggle = { want ->
                    if (want) AutoRelock.setRelockOnExit(true)
                    else requireVerify { AutoRelock.setRelockOnExit(false) }
                    AutoRelockService.sync(ctx)
                },
                onEditDelay = { valueLink = AutoRelockStore.LINK_EXIT },
            )
            Spacer(Modifier.height(10.dp))
            SceneRowCard(
                title = "锁屏后",
                desc = "息屏立即或延迟后锁回（锁屏期间仍熄屏才执行）",
                link = AutoRelockStore.LINK_SCREEN_OFF,
                delaySec = AutoRelock.globalScreenOffDelaySec,
                onToggle = { want ->
                    if (want) AutoRelock.setRelockOnScreenOff(true)
                    else requireVerify { AutoRelock.setRelockOnScreenOff(false) }
                    AutoRelockService.sync(ctx)
                },
                onEditDelay = { valueLink = AutoRelockStore.LINK_SCREEN_OFF },
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "延迟只在开关打开时生效；0 秒 = 立即。\n" +
                    "关闭开关或调大延迟属于削弱性修改，需要先通过解锁验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 延迟编辑弹层
    valueLink?.let { link ->
        val sceneName = when (link) {
            AutoRelockStore.LINK_EXIT -> "退出后"
            AutoRelockStore.LINK_SCREEN_OFF -> "锁屏后"
            else -> "解锁后"
        }
        val current = when (link) {
            AutoRelockStore.LINK_EXIT -> AutoRelock.globalExitDelaySec
            AutoRelockStore.LINK_SCREEN_OFF -> AutoRelock.globalScreenOffDelaySec
            else -> AutoRelock.globalDelaySec
        }
        var text by remember { mutableIntStateOf(current) }
        var raw by remember { mutableStateOf(current.toString()) }
        AlertDialog(
            onDismissRequest = { valueLink = null },
            title = { Text("「$sceneName」延迟") },
            text = {
                Column {
                    Text(
                        "到点自动重新上锁（秒，0 = 立即）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = raw,
                        onValueChange = {
                            raw = it.filter { c -> c.isDigit() }.take(9)
                            raw.toIntOrNull()?.let { v -> text = v }
                        },
                        suffix = { Text("秒") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = text in 0..AutoRelockStore.MAX_DELAY_SEC,
                    onClick = {
                        valueLink = null
                        val old = when (link) {
                            AutoRelockStore.LINK_EXIT -> AutoRelock.globalExitDelaySec
                            AutoRelockStore.LINK_SCREEN_OFF -> AutoRelock.globalScreenOffDelaySec
                            else -> AutoRelock.globalDelaySec
                        }
                        val apply = {
                            when (link) {
                                AutoRelockStore.LINK_EXIT -> AutoRelock.setGlobalExitDelay(text)
                                AutoRelockStore.LINK_SCREEN_OFF -> AutoRelock.setGlobalScreenOffDelay(text)
                                else -> AutoRelock.setGlobalDelay(text)
                            }
                        }
                        // 调大 = 削弱（更晚才锁）；调小 = 加强
                        if (text > old) requireVerify(apply) else apply()
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { valueLink = null }) { Text("取消") }
            }
        )
    }

    // 削弱性修改验证
    pendingAction?.let { action ->
        com.activitylock.app.ui.components.UnlockVerifyDialog(
            requirement = com.activitylock.app.core.VerifyGate.bulkRequirement(ctx, AppStore.rules),
            title = "修改自动锁闭",
            hint = "削弱自律约束的修改需要先通过验证",
            enforceWindow = false,
            onConfirmed = {
                pendingAction = null
                action()
            },
            onDismiss = { pendingAction = null }
        )
    }
}

@Composable
private fun SceneRowCard(
    title: String,
    desc: String,
    link: Int,
    delaySec: Int,
    onToggle: (Boolean) -> Unit,
    onEditDelay: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onEditDelay) {
                Text(
                    if (delaySec == 0) "立即" else "${delaySec}s",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(Modifier.width(4.dp))
            Switch(checked = AutoRelock.linkEnabled(link), onCheckedChange = onToggle)
        }
    }
}

private fun AutoRelock.linkEnabled(link: Int): Boolean = when (link) {
    AutoRelockStore.LINK_EXIT -> relockOnExitEnabled
    AutoRelockStore.LINK_SCREEN_OFF -> relockOnScreenOffEnabled
    else -> relockAfterUnlockEnabled
}

/** 延迟秒数的展示文案（其他页面也引用）。 */
internal fun relockDelayLabel(sec: Int): String = when {
    sec <= 0 -> "立即"
    sec < 60 -> "${sec}s"
    sec % 60 == 0 -> "${sec / 60} 分钟"
    else -> "${sec / 60} 分 ${sec % 60} 秒"
}
