package com.activitylock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.BlockStats
import com.activitylock.app.core.SuperviseEngine
import com.activitylock.app.core.StreakStore
import com.activitylock.app.ui.components.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat

/** 拦截统计：今日拦截大数字为全屏唯一焦点，分 App 明细按超额状态着色。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit, bottomTab: Boolean = false) {
    val ctx = LocalContext.current
    var stats by remember { mutableStateOf<BlockStats.Stats?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        stats = withContext(Dispatchers.IO) { BlockStats.read() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("拦截统计", style = MaterialTheme.typography.titleMedium) },
                // ★ v1.32.2：外层 MainActivity 的 Scaffold 已经把状态栏 inset 让进来了，
                //   TopAppBar 默认又加一次 statusBars → 标题比「应用」「设置」两个 Tab
                //   低了整整一条状态栏的高度（真机对比截图确认）。这里清零。
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    // 作为底部 tab 一级页时不显示返回箭头（去掉自指的死循环）
                    if (!bottomTab) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        val s = stats
        if (s == null) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(64.dp))
                Text("暂无统计数据", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "拦截发生后自动计数。\n确认 Shizuku 已连接且至少拦截过一次。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // ★ v1.32.0：超额状态改由监督判定引擎提供（含整包与单界面两种粒度）
            val over = SuperviseEngine.blocks(ctx).map { it.pkg }.toSet()
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text(
                            "今日拦截",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "${s.today}",
                                style = MaterialTheme.typography.displayLarge
                                    .copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "  次",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        Text(
                            "累计拦截 ${s.total} 次",
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item {
                    // 连续守护：ADHD 正反馈（超额/专注破功会中断 streak）
                    val streak = remember(s) { StreakStore.streakDays(ctx) }
                    val todayBroken = StreakStore.todayBroken(ctx)
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("🔥", style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "连续守护 $streak 天",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    if (todayBroken) "今日已破功，明天重新累积"
                                    else "今日暂未破功，守住就是胜利",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "近 7 天",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        val today = java.time.LocalDate.now()
                        val maxDaily = (0..6).maxOf { d ->
                            s.daily[today.minusDays(d.toLong()).toString()] ?: 0
                        }.coerceAtLeast(1)
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            (6 downTo 0).forEach { back ->
                                val date = today.minusDays(back.toLong())
                                val count = s.daily[date.toString()] ?: 0
                                val h = ((count.toFloat() / maxDaily) * 64).dp.value
                                    .coerceAtLeast(3f).dp
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "$count",
                                        style = MaterialTheme.typography.labelSmall
                                            .copy(fontFeatureSettings = "tnum"),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    androidx.compose.foundation.layout.Box(
                                        Modifier
                                            .width(18.dp)
                                            .height(h)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                androidx.compose.foundation.shape.RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                                            )
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        listOf("一","二","三","四","五","六","日")[date.dayOfWeek.value - 1],
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                if (s.apps.isNotEmpty()) {
                    item {
                        Text(
                            "分 App",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                    val maxCount = s.apps.values.maxOrNull() ?: 1
                    items(s.apps.entries.sortedByDescending { it.value }, key = { it.key }) { (pkg, count) ->
                        val isOver = pkg in over
                        val barColor = if (isOver) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(pkg, 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        // ★ 规范 §1：原来 "应用名 · 已超额" 用中点串状态 → 括号
                                        (AppStore.findApp(pkg)?.label ?: pkg.substringAfterLast('.'))
                                            + if (isOver) " · 已超额" else "",
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isOver) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { (count.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = barColor,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "$count 次",
                                    style = MaterialTheme.typography.titleMedium
                                        .copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
