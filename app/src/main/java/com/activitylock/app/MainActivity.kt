package com.activitylock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
    data class Detail(val packageName: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ActivityLockTheme {
                AppRoot()
            }
        }
    }
}

private fun isTopLevel(s: Screen) =
    s is Screen.Favorites || s is Screen.Apps || s is Screen.Settings

private fun switchTab(stack: MutableList<Screen>, target: Screen, current: Screen) {
    if (current == target) return
    if (isTopLevel(current)) {
        stack[stack.size - 1] = target
    } else {
        stack.add(target)
    }
}

@Composable
private fun AppRoot() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Favorites) }
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
                Screen.Favorites -> FavoritesScreen()
                Screen.Apps -> AppsScreen(
                    onOpenDetail = { pkg -> stack.add(Screen.Detail(pkg)) }
                )
                Screen.Settings -> SettingsScreen(
                    onOpenConnection = { stack.add(Screen.Connection) },
                    onOpenInspector = { stack.add(Screen.Inspector) },
                    onOpenSchemes = { stack.add(Screen.SchemeLibrary) },
                    onOpenHistory = { stack.add(Screen.History) },
                    onOpenVerify = { stack.add(Screen.Verify) }
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
                Screen.Connection -> ConnectionScreen(
                    onBack = { stack.removeAt(stack.size - 1) }
                )
                is Screen.Detail -> ActivityListScreen(
                    packageName = screen.packageName,
                    onBack = { stack.removeAt(stack.size - 1) }
                )
            }
        }
    }
}
