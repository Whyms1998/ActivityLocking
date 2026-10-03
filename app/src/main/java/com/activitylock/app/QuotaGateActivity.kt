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
import com.activitylock.app.core.QuotaState
import com.activitylock.app.ui.theme.ActivityLockTheme

/**
 * 额度用完提示页（exported）：由 Watcher 以 shell 身份在拦截超额 App 时拉起。
 * 展示当日已超额的 App 列表；明天 0 点自动恢复。
 */
class QuotaGateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.ensureInit(applicationContext)
        val over = QuotaState.overPackages(this).map { pkg ->
            AppStore.findApp(pkg)?.label ?: pkg.substringAfterLast('.')
        }
        setContent {
            ActivityLockTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xF2121013)) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("🪨", style = MaterialTheme.typography.displayLarge)
                        Spacer(Modifier.height(16.dp))
                        Text("今日额度已用完", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (over.isEmpty()) "超出今日计划的使用额度，明天 0 点恢复。"
                            else "以下 App 已超出今日额度，明天 0 点恢复：\n\n${over.joinToString("、")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB8B3AD)
                        )
                        Spacer(Modifier.height(32.dp))
                        TextButton(onClick = { finish() }) { Text("知道了") }
                    }
                }
            }
        }
    }
}
