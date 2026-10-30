package com.activitylock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.activitylock.app.ui.screens.ActivityListScreen
import com.activitylock.app.ui.screens.AppsScreen
import com.activitylock.app.ui.screens.ConnectionScreen
import com.activitylock.app.ui.screens.FavoritesScreen
import com.activitylock.app.ui.screens.HistoryScreen
import com.activitylock.app.ui.screens.InspectorScreen
import com.activitylock.app.ui.screens.PresetScreen
import com.activitylock.app.ui.screens.QuotaScreen
import com.activitylock.app.ui.screens.ScheduleScreen
import com.activitylock.app.ui.screens.SceneScreen
import com.activitylock.app.ui.screens.AdminScreen
import com.activitylock.app.ui.screens.AutoRelockScreen
import com.activitylock.app.ui.screens.SyncScreen
import com.activitylock.app.ui.screens.AmnestyScreen
import com.activitylock.app.ui.screens.LockStrengthScreen
import com.activitylock.app.ui.screens.FocusScreen
import com.activitylock.app.ui.screens.StatsScreen
import com.activitylock.app.ui.screens.SchemeLibraryScreen
import com.activitylock.app.ui.screens.SettingsScreen
import com.activitylock.app.ui.theme.ActivityLockTheme

sealed interface Screen {
    data object Favorites : Screen
    data object Apps : Screen
    data object Settings : Screen
    data object Connection : Screen
    data object SchemeLibrary : Screen
    data object Inspector : Screen
    data object History : Screen
    data object Verify : Screen
    data object Presets : Screen
    data object Schedules : Screen
    data object Quota : Screen
    data object Scenes : Screen
    data object Stats : Screen
    data object Focus : Screen
    data object Admin : Screen
    data object LockStrength : Screen
    /** v1.29.0：特赦通行证（紧急破例的唯一入口，配额 + 递增）。 */
    data object Amnesty : Screen
    data object AutoRelock : Screen
    /** v1.34.1：数据同步（WebDAV 云端 + 本地文件）。 */
    data object Sync : Screen
    data class Detail(val packageName: String) : Screen
    /** 界面详情页（文档 §14.5）：单个 Activity 的自动锁闭 + 解锁验证。 */
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ★ v1.29.0：磁贴等外部入口统一走「拉起主页 + 指定落地页」，
        //   避免再开一个平行的验证页（两套 UI 必然出现两套配额，进而被绕过）。
        val initial: Screen = when (intent?.getStringExtra(EXTRA_SCREEN)) {
            SCREEN_AMNESTY -> Screen.Amnesty
            else -> Screen.Favorites
        }
        setContent {
            ActivityLockTheme {
                AppRoot(initial)
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN = "extra_screen"
        const val SCREEN_AMNESTY = "amnesty"
    }
}

private fun isTopLevel(s: Screen) =
    s is Screen.Favorites || s is Screen.Apps || s is Screen.Stats || s is Screen.Settings

private fun switchTab(stack: MutableList<Screen>, target: Screen, current: Screen) {
    if (current == target) return
    if (isTopLevel(current)) {
        stack[stack.size - 1] = target
    } else {
        stack.add(target)
    }
}

@Composable
private fun AppRoot(initial: Screen = Screen.Favorites) {
    // 栈底永远是顶层页：从磁贴直接落到二级页时，返回键要能退回主页而不是崩掉。
    val stack = remember {
        mutableStateListOf<Screen>(Screen.Favorites).apply {
            if (initial != Screen.Favorites) add(initial)
        }
    }
    val current = stack.last()
    val topLevel = isTopLevel(current)

    BackHandler(enabled = stack.size > 1) {
        stack.removeAt(stack.size - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (topLevel) {
                NavigationBar {
                    NavigationBarItem(
                        selected = current is Screen.Favorites,
                        onClick = { switchTab(stack, Screen.Favorites, current) },
                        icon = { Icon(Icons.Default.Star, contentDescription = null) },
                        label = { Text("收藏") }
                    )
                    NavigationBarItem(
                        selected = current is Screen.Apps,
                        onClick = { switchTab(stack, Screen.Apps, current) },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        label = { Text("应用") }
                    )
                    NavigationBarItem(
                        selected = current is Screen.Stats,
                        onClick = { switchTab(stack, Screen.Stats, current) },
                        icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                        label = { Text("统计") }
                    )
                    NavigationBarItem(
                        selected = current is Screen.Settings,
                        onClick = { switchTab(stack, Screen.Settings, current) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("设置") }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val screen = current) {
                // ★ v1.20.0：工作台删掉了方案/计划/限额/统计/锁闭的快捷入口——
                //   与底部导航和「设置」页重复。低频配置统一从「设置」进。
                Screen.Favorites -> FavoritesScreen(
                    onOpenApps = { switchTab(stack, Screen.Apps, current) },
                )
                Screen.Apps -> AppsScreen(
                    onOpenDetail = { pkg -> stack.add(Screen.Detail(pkg)) }
                )
                Screen.Settings -> SettingsScreen(
                    onOpenConnection = { stack.add(Screen.Connection) },
                    onOpenInspector = { stack.add(Screen.Inspector) },
                    onOpenSchemes = { stack.add(Screen.SchemeLibrary) },
                    onOpenHistory = { stack.add(Screen.History) },
                    onOpenVerify = { stack.add(Screen.Verify) },
                    onOpenPresets = { stack.add(Screen.Presets) },
                    onOpenSchedules = { stack.add(Screen.Schedules) },
                    onOpenQuota = { stack.add(Screen.Quota) },
                    onOpenScenes = { stack.add(Screen.Scenes) },
                    onOpenFocus = { stack.add(Screen.Focus) },
                    onOpenAdmin = { stack.add(Screen.Admin) },
                    onOpenStrength = { stack.add(Screen.LockStrength) },
                    onOpenAmnesty = { stack.add(Screen.Amnesty) },
                    onOpenAutoRelock = { stack.add(Screen.AutoRelock) },
                    onOpenSync = { stack.add(Screen.Sync) }
                )
                Screen.Verify -> com.activitylock.app.ui.screens.VerifySettingsScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.History -> HistoryScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.Inspector -> InspectorScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.SchemeLibrary -> SchemeLibraryScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.Presets -> PresetScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.Schedules -> ScheduleScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.Quota -> QuotaScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.Scenes -> SceneScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.Stats -> StatsScreen(
                    onBack = { stack.removeAt(stack.size - 1) },
                    bottomTab = true
                )
                Screen.Focus -> FocusScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.Admin -> AdminScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.LockStrength -> LockStrengthScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.Amnesty -> AmnestyScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.AutoRelock -> AutoRelockScreen(
                    onBack = { stack.removeAt(stack.size - 1) },
                    onOpenVerify = { stack.add(Screen.Verify) }
                )
                Screen.Connection -> ConnectionScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                Screen.Sync -> SyncScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                is Screen.Detail -> ActivityListScreen(
                    packageName = screen.packageName,
                    onBack = { stack.removeAt(stack.size - 1) }
                )            }
        }
    }
}
