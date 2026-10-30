package com.activitylock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.SuperviseBlock
import com.activitylock.app.core.SuperviseEngine
import com.activitylock.app.core.cappedUsed
import com.activitylock.app.core.superviseTargetLabel
import com.activitylock.app.ui.components.AppIcon
import com.activitylock.app.ui.theme.ActivityLockTheme

/**
 * 监督拦截说明页（exported）：由 Watcher 以 shell 身份在拦下受监督对象时拉起。
 *
 * ★ v1.32.0：不再依赖 intent 参数，直接读 [SuperviseEngine] 当前快照。
 * ★ v1.32.2：
 *   1. **配色修正** —— 旧版 `Surface(color = 硬编码深色)` 没有配套 contentColor，
 *      标题继承了默认黑色，在深色背景上几乎不可见（真机截图确认）；
 *   2. 改用**真实应用图标**，不再用 emoji 沙漏（与全 App 图标体系一致）；
 *   3. 只讲**刚被拦的那一个对象**，其余超额对象收成一行小字，不再罗列刷屏；
 *   4. 读数封顶（超额后不再显示 98/3 这种一路累加的数字）。
 */
class QuotaGateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★ v1.33.2：窗口是 translucent+transparent，不做 edge-to-edge 时内容从状态栏下方
        //   才开始，透明状态栏透出被 dim 压暗的壁纸 = 浅色模式下顶部一条黑带（真机截图确认）。
        //   开启后背景 Surface 铺到状态栏底下，图标颜色自动跟随系统昼夜。
        enableEdgeToEdge()
        AppStore.ensureInit(applicationContext)
        val blocks = SuperviseEngine.blocks(this)
        // watcher 新版会把命中的包名放进 extra（见 WatcherMain.buildTargetIntent）；
        // 旧版没有时退回快照里的第一个，保证永远有内容可显示。
        val hitPkg = intent?.getStringExtra(EXTRA_PKG)
        val primary = blocks.firstOrNull { it.pkg == hitPkg } ?: blocks.firstOrNull()
        val others = blocks.filter { it !== primary }
        setContent {
            ActivityLockTheme {
                GateScreen(primary, others.size) { finish() }
            }
        }
    }

    companion object {
        const val EXTRA_PKG = "pkg"
    }
}

@Composable
private fun GateScreen(block: SuperviseBlock?, others: Int, onDismiss: () -> Unit) {
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (block == null) {
                // 快照为空（任务被关掉/刚跨天）：说明情况即可，不装作拦了谁
                Text(
                    "没有正在生效的限制",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "这个应用现在可以正常打开。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("知道了")
                }
                return@Column
            }

            AppIcon(block.pkg, size = 76.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                superviseTargetLabel(block.pkg, block.cls),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                reasonTitle(block.reason),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            readoutText(block)?.let {
                Spacer(Modifier.height(18.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                recoveryText(block.reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (others > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "另有 $others 个对象也已达上限",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            ) {
                Text("知道了")
            }
        }
    }
}

private fun reasonTitle(reason: String): String = when (reason) {
    "forbid" -> "当前时段禁止使用"
    "window_sec" -> "本时段可用时长已用完"
    "window_cnt" -> "本时段打开次数已用完"
    "day_sec" -> "今日可用时长已用完"
    "day_cnt" -> "今日打开次数已用完"
    else -> "已达使用上限"
}

/** 「3 / 3 次」「30 / 30 分钟」；没有读数维度时返回 null。 */
private fun readoutText(b: SuperviseBlock): String? = when {
    b.limitSec > 0 -> {
        val used = cappedUsed(b.usedSec / 60, b.limitSec / 60)
        "$used / ${b.limitSec / 60} 分钟"
    }
    b.limitCnt > 0 -> {
        val used = cappedUsed(b.usedCnt, b.limitCnt)
        "$used / ${b.limitCnt} 次"
    }
    else -> null
}

private fun recoveryText(reason: String): String = when {
    reason.startsWith("day") -> "明天 0:00 恢复"
    reason.startsWith("window") -> "本时段结束后恢复"
    reason == "forbid" -> "该时段结束后恢复"
    else -> "稍后再试"
}
