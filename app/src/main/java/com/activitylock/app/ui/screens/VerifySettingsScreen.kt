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
import com.activitylock.app.ui.components.UnlockVerifyDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifySettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(VerifyGate.isEnabled(ctx)) }
    var method by remember { mutableStateOf(VerifyGate.method(ctx)) }
    var password by remember { mutableStateOf(VerifyGate.password(ctx)) }
    var delaySec by remember { mutableStateOf(VerifyGate.delaySeconds(ctx)) }

    // 验证开启期间，削弱性修改（关闭/换方式/改密码）需先通过当前验证
    var verifiedThisSession by remember { mutableStateOf(false) }
    val editLocked = enabled && !verifiedThisSession
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun requireVerify(action: () -> Unit) {
        if (editLocked) pendingAction = action else action()
    }

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
            if (editLocked) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "验证已开启：修改设置前需先通过当前验证。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

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
                        onCheckedChange = { want ->
                            if (want) {
                                enabled = true
                                persist()
                            } else {
                                requireVerify {
                                    enabled = false
                                    verifiedThisSession = false
                                    persist()
                                }
                            }
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
                                onClick = {
                                    requireVerify { method = VerifyGate.METHOD_DELAY; persist() }
                                }
                            )
                            Text("等待指定时间")
                        }
                        if (method == VerifyGate.METHOD_DELAY) {
                            Row(
                                Modifier.padding(start = 40.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    enabled = !editLocked,
                                    onClick = { requireVerify { delaySec = (delaySec - 5).coerceAtLeast(5); persist() } }
                                ) { Text("−") }
                                Text(
                                    "$delaySec 秒",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                                TextButton(
                                    enabled = !editLocked,
                                    onClick = { requireVerify { delaySec = (delaySec + 5).coerceAtMost(60); persist() } }
                                ) { Text("＋") }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = method == VerifyGate.METHOD_PASSWORD,
                                onClick = {
                                    requireVerify { method = VerifyGate.METHOD_PASSWORD; persist() }
                                }
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
                                enabled = !editLocked,
                                placeholder = { Text("设置解锁密码") },
                                singleLine = true
                            )
                            TextButton(
                                enabled = !editLocked && password.isNotBlank(),
                                onClick = { requireVerify { persist() } }
                            ) { Text("保存密码") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "验证在收藏夹解锁和磁贴「全部解锁」时生效；锁定操作不受影响。\n" +
                    "验证开启时，关闭验证或修改验证方式也需要先通过当前验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    pendingAction?.let { action ->
        UnlockVerifyDialog(
            onConfirmed = {
                verifiedThisSession = true
                pendingAction = null
                action()
            },
            onDismiss = { pendingAction = null }
        )
    }
}
