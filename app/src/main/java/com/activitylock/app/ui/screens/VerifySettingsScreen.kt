package com.activitylock.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.activitylock.app.core.VerifyGate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifySettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(VerifyGate.isEnabled(ctx)) }
    var method by remember { mutableStateOf(VerifyGate.method(ctx)) }
    var password by remember { mutableStateOf(VerifyGate.password(ctx)) }
    var delaySec by remember { mutableStateOf(VerifyGate.delaySeconds(ctx)) }

    fun persist() {
        VerifyGate.save(ctx, enabled, method, password, delaySec)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("解锁验证", style = MaterialTheme.typography.titleMedium) },
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用解锁验证", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "解锁已锁定的界面时，需要先通过验证",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            persist()
                        }
                    )
                }
            }

            if (enabled) {
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("验证方式", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = method == VerifyGate.METHOD_DELAY,
                                onClick = { method = VerifyGate.METHOD_DELAY; persist() }
                            )
                            Text("等待指定时间")
                        }
                        if (method == VerifyGate.METHOD_DELAY) {
                            Row(
                                Modifier.padding(start = 40.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = {
                                    delaySec = (delaySec - 5).coerceAtLeast(5); persist()
                                }) { Text("−") }
                                Text(
                                    "$delaySec 秒",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                                TextButton(onClick = {
                                    delaySec = (delaySec + 5).coerceAtMost(60); persist()
                                }) { Text("＋") }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = method == VerifyGate.METHOD_PASSWORD,
                                onClick = { method = VerifyGate.METHOD_PASSWORD; persist() }
                            )
                            Text("输入指定密码")
                        }
                        if (method == VerifyGate.METHOD_PASSWORD) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 40.dp, top = 8.dp, end = 16.dp),
                                placeholder = { Text("设置解锁密码") },
                                singleLine = true
                            )
                            TextButton(onClick = { persist() }) { Text("保存密码") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "验证在收藏夹解锁和磁贴「全部解锁」时生效；锁定操作不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
