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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.activitylock.app.R
import com.activitylock.app.core.LockAllTileService
import com.activitylock.app.core.ShizukuManager
import com.activitylock.app.core.TempUnlockTileService
import com.activitylock.app.core.UnlockAllTileService
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
    onOpenAmnesty: () -> Unit,
    onOpenAutoRelock: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    /** ★ v1.29.0：控制中心磁贴的重新添加入口。 */
    var showTiles by remember { mutableStateOf(false) }
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
            title = "计划与豁免",
            onClick = onOpenStrength
        )
        // ★ v1.29.0：特赦的唯一入口就放在这里（用户明确要求「藏在设置里」）。
        //   它不是一个新开关，而是「日常验证可以设得很难」的前提：
        //   真需要时有这一条有配额的破例路径。
        SettingsEntry(
            icon = Icons.Default.LockOpen,
            title = "特赦通行证",
            onClick = onOpenAmnesty
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
            title = "APP 监督",
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
        // ★ v1.29.0：磁贴"消失"的真相与修复入口。
        //   真机排查：三个 TileService 在 manifest 里一直注册着（query-services 可见），
        //   但 `settings get secure sysui_qs_tiles` 里一条都不在 ——
        //   应用更新后系统会清掉控制中心里的 custom tile 条目。
        //   用户感知是「磁贴被去除了」，其实是控制中心里的条目丢了，代码和声明都没动过。
        SettingsEntry(
            icon = Icons.Default.Apps,
            title = "控制中心磁贴",
            subtitle = "添加上锁 / 解锁 / 特赦",
            onClick = { showTiles = true }
        )
        // ★ v1.34.1：数据同步。全部心血都在本地配置里，没有这条入口，换一台手机
        //   就等于从零再配一遍——而「重新配一遍」的那一刻最容易被放弃。
        SettingsEntry(
            icon = Icons.Default.Save,
            title = "数据同步",
            subtitle = "WebDAV 云端 / 换机迁移",
            onClick = onOpenSync
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

    // ★ v1.29.0：磁贴重新添加。用系统标准的 requestAddTileService（API 33+），
    //   它会弹出系统自己的「添加到控制中心」确认，不需要我们自己模拟拖拽。
    if (showTiles) {
        AlertDialog(
            onDismissRequest = { showTiles = false },
            title = { Text("控制中心磁贴") },
            text = {
                Column {
                    Text(
                        "三个磁贴一直都在，但应用更新后系统会把它们从控制中心里移除。" +
                            "点「添加」放回去。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    TileRow("特赦", "剩几次 + 本次放行时长；点按直达特赦页") {
                        requestAddTile(ctx, TempUnlockTileService::class.java, "特赦", R.drawable.ic_tile_key)
                    }
                    TileRow("全部上锁", "把所有已配置界面立即锁上") {
                        requestAddTile(ctx, LockAllTileService::class.java, "全部上锁", R.drawable.ic_tile_lock)
                    }
                    TileRow("全部解锁", "把全部界面置为解锁") {
                        requestAddTile(ctx, UnlockAllTileService::class.java, "全部解锁", R.drawable.ic_tile_unlock)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTiles = false }) { Text("关闭") }
            }
        )
    }
}

/** 磁贴列表的一行：名称 + 一句说明 + 「添加」。 */
@Composable
private fun TileRow(title: String, desc: String, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onAdd) { Text("添加") }
    }
}

/**
 * 请求把某个磁贴加回控制中心。
 *
 * ★ v1.29.0 实测结论：`TileService.requestAddTileService` 虽然是 API 33 的 public API，
 *   但本机 `android-36/android.jar` 的公开 stubs 里**没有它**
 *   （javap 只列出 requestListeningState）→ 直接调用编译不过。
 *   所以这里走反射：方法存在就弹系统的「添加到控制中心」确认，不存在就降级提示手动添加。
 *
 * 不要自己弹「已添加」的假提示 —— 系统不认这个组件时磁贴照样不会出现，
 * 那就成了又一个"看着成功、实际没生效"。
 */
private fun requestAddTile(
    ctx: android.content.Context,
    cls: Class<*>,
    label: String,
    iconRes: Int,
) {
    val hint = "请长按控制中心空白处 → 编辑，把「$label」拖进去"
    if (android.os.Build.VERSION.SDK_INT < 33) {
        android.widget.Toast.makeText(ctx, hint, android.widget.Toast.LENGTH_LONG).show()
        return
    }
    val ok = runCatching {
        val cbCls = Class.forName("android.service.quicksettings.TileService\$OutcomeCallback")
        val m = android.service.quicksettings.TileService::class.java.getMethod(
            "requestAddTileService",
            android.content.ComponentName::class.java,
            CharSequence::class.java,
            android.graphics.drawable.Icon::class.java,
            java.util.concurrent.Executor::class.java,
            cbCls,
        )
        // 回调是 @NonNull 参数，传 null 可能炸 → 用动态代理给一个空实现。
        val cb = java.lang.reflect.Proxy.newProxyInstance(
            cbCls.classLoader, arrayOf(cbCls)
        ) { _, _, _ -> null }
        m.invoke(
            null,
            android.content.ComponentName(ctx, cls),
            label,
            android.graphics.drawable.Icon.createWithResource(ctx, iconRes),
            ctx.mainExecutor,
            cb,
        )
        true
    }.getOrDefault(false)
    if (!ok) {
        android.widget.Toast.makeText(ctx, "无法自动添加，$hint", android.widget.Toast.LENGTH_LONG).show()
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
