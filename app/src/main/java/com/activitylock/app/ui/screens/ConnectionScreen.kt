package com.activitylock.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.ShizukuManager
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(onBack: () -> Unit) {
    var status by remember { mutableStateOf("检查中…") }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        status = when {
            !ShizukuManager.installed() -> "未检测到 Shizuku 服务"
            !ShizukuManager.granted() -> "已连接 Shizuku，但未授权"
            else -> "Shizuku 已就绪 ✅"
        }
        delay(1500)
        tick++
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("连接", style = MaterialTheme.typography.titleMedium) },
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
                Column(Modifier.padding(16.dp)) {
                    Text("Shizuku 状态", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(status)
                    Spacer(Modifier.height(10.dp))
                    if (!ShizukuManager.granted()) {
                        Button(
                            onClick = { ShizukuManager.requestPermission(); tick++ },
                            enabled = ShizukuManager.installed()
                        ) {
                            Text(if (ShizukuManager.installed()) "申请授权" else "请先安装并启动 Shizuku")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("使用步骤", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    // ★ 规范 §1：这里的 `·` 是当项目符号用，弱且和"中点连接"混淆 → 换成正经的圆点 bullet
                    Text(
                        "1. 安装 Shizuku：应用商店搜索，或 GitHub 仓库 rikkaapps/Shizuku。\n" +
                            "2. 启动 Shizuku：\n" +
                            "   • 无线调试：开发者选项 → 无线调试 → 配对。\n" +
                            "   • 连接电脑：adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n" +
                            "3. 在本页点击「申请授权」。\n" +
                            "4. 回到「应用」页，搜索并锁定目标界面。\n\n" +
                            "拦截控制器是系统全局唯一的，与其他同类工具不能同时生效，后启动者会接管。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("工作原理", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "锁定会优先尝试组件禁用；" +
                            "在 HyperOS 等禁用组件受限制的系统上，会自动改用「系统级拦截模式」：" +
                            "注册系统的 Activity 启动控制器，被锁定的界面在启动时被系统直接拒绝，" +
                            "应用其他功能完全不受影响。\n\n" +
                            "拦截进程在手机重启后需要重新打开一次本 App 来恢复。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
