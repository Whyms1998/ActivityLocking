package com.activitylock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.SuperviseBlock
import com.activitylock.app.core.SuperviseEngine
import com.activitylock.app.ui.theme.ActivityLockTheme

/**
 * 监督拦截说明页（exported）：由 Watcher 以 shell 身份在拦下受监督对象时拉起。
 *
 * ★ v1.32.0：不再依赖 intent 参数，直接读 [SuperviseEngine] 当前快照——
 *   谁被拦、因为哪条规则、用掉多少，都由判定引擎给出，避免 watcher 传参改协议。
 */
class QuotaGateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.ensureInit(applicationContext)
        val blocks = SuperviseEngine.blocks(this)
        setContent {
            ActivityLockTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xF2121013)) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("⏳", style = MaterialTheme.typography.displayLarge)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (blocks.any { it.reason == "forbid" }) "现在不能使用"
                            else "今日额度已用完",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        if (blocks.isEmpty()) {
                            Text(
                                "该应用已受监督限制，稍后再试。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFB8B3AD)
                            )
                        } else {
                            for (b in blocks) {
                                Text(
                                    "${nameOf(b)}：${detailOf(b)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFB8B3AD),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                        TextButton(onClick = { finish() }) { Text("知道了") }
                    }
                }
            }
        }
    }

    /** 整包取 App 名；单界面取「App › 界面名」（与监督页同一套命名规则）。 */
    private fun nameOf(b: SuperviseBlock): String =
        com.activitylock.app.core.superviseTargetLabel(b.pkg, b.cls)

    private fun detailOf(b: SuperviseBlock): String {
        val task = if (b.taskName.isBlank()) "" else "（任务「${b.taskName}」）"
        return when (b.reason) {
            "forbid" -> "当前时段禁止使用$task"
            "window_sec" -> "本时段时长已用完 ${min(b.usedSec)}/${min(b.limitSec)} 分钟"
            "window_cnt" -> "本时段打开次数已用完 ${b.usedCnt}/${b.limitCnt} 次"
            "day_sec" -> "今日时长已用完 ${min(b.usedSec)}/${min(b.limitSec)} 分钟，明天 0 点恢复"
            "day_cnt" -> "今日打开次数已用完 ${b.usedCnt}/${b.limitCnt} 次，明天 0 点恢复"
            else -> "已达额度，明天 0 点恢复"
        }
    }

    private fun min(sec: Long): Long = if (sec <= 0) 0 else maxOf(1L, sec / 60)
}
