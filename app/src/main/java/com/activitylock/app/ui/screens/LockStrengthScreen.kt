package com.activitylock.app.ui.screens

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
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.LockStrength
import com.activitylock.app.core.ScheduleEngine

/**
 * 计划与豁免。
 *
 * ★ v1.29.0 重组：本页原先还有「临时解锁时长」「每日临时解锁次数上限」两项，
 *   而「临时解锁」已整体并入 **特赦通行证**（配额、准入、递增都在那边统一裁决）。
 *   同一件事在两处各有一份配置 = 两份计数 = 一定有一个是假的，所以这里全部删掉，
 *   只留下唯一不重叠的那一项：**计划生效期间不许破例**。
 *
 *   页面名也从「锁定强度」改成「计划与豁免」——名字得对得上剩下的内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockStrengthScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var noExemptInSchedule by remember { mutableStateOf(LockStrength.noExemptInSchedule(ctx)) }
    val planActive = remember { ScheduleEngine.anyActive(AppStore.schedules) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("计划与豁免", style = MaterialTheme.typography.titleMedium) },
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
                    "特赦的配额、准入条件、递增强度都在「特赦通行证」里统一设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("计划生效期间禁用特赦", style = MaterialTheme.typography.titleSmall)
                            if (planActive) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "当前有计划正在生效",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Switch(
                            checked = noExemptInSchedule,
                            onCheckedChange = {
                                noExemptInSchedule = it
                                LockStrength.setNoExemptInSchedule(ctx, it)
                                AmsController.writeRules()
                            }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "这一项和特赦是「总闸与口子」的关系：\n" +
                        "· 关着 —— 计划时段内仍然可以申请特赦（默认，适合把特赦留给真急事）；\n" +
                        "· 打开 —— 计划时段内连特赦也不给（适合「晚上必须断」的场景）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}
