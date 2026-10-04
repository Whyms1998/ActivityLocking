package com.activitylock.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.activitylock.app.core.LockStrength

/**
 * 锁定强度：临时解锁的时长与次数上限、计划窗口内的豁免禁用——
 * 每一项都在堵一条"合法绕过"的路径（借鉴不做手机控的分级强制）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockStrengthScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tempMin by remember { mutableStateOf(LockStrength.tempUnlockMin(ctx)) }
    var dailyMax by remember { mutableStateOf(LockStrength.tempUnlockDailyMax(ctx)) }
    var noExemptInSchedule by remember { mutableStateOf(LockStrength.noExemptInSchedule(ctx)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("锁定强度", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "每一项都在堵一条「合法绕过」的路径。强度越高，意志力需要越少。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("临时解锁时长", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "通过验证后的放行时长——短 = 冲动窗口过后即拦回",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 10, 15, 30).forEach { m ->
                                FilterChip(
                                    selected = tempMin == m,
                                    onClick = {
                                        tempMin = m
                                        LockStrength.setTempUnlockMin(ctx, m)
                                    },
                                    label = { Text("$m 分") }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("每日临时解锁次数上限", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "堵住「反复临时解锁」——每次仍需通过解锁验证",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(-1 to "不限", 3 to "3 次", 1 to "1 次", 0 to "禁用").forEach { (v, label) ->
                                FilterChip(
                                    selected = dailyMax == v,
                                    onClick = {
                                        dailyMax = v
                                        LockStrength.setTempUnlockDailyMax(ctx, v)
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("计划窗口内禁用临时解锁", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "睡眠模式等计划生效期间，临时解锁失效——半夜不给开口子",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = noExemptInSchedule,
                            onCheckedChange = {
                                noExemptInSchedule = it
                                LockStrength.setNoExemptInSchedule(ctx, it)
                                com.activitylock.app.core.AmsController.writeRules()
                            }
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "强度建议：起步用「15 分钟 / 不限」；适应后收紧到「10 分钟 / 1 次」；" +
                        "开启定时计划后建议打开「计划窗口内禁用」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}
