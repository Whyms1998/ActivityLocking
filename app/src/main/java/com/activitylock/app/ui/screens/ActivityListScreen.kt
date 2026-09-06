package com.activitylock.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityListScreen(packageName: String, onBack: () -> Unit) {
    val app = AppStore.findApp(packageName)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(app?.label ?: packageName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            packageName,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (app == null) {
            Text(
                "应用列表尚未加载完成，请返回后重试。",
                Modifier.padding(padding).padding(24.dp)
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(app.activities, key = { it.cls }) { act ->
                    ActivityToggleRow(
                        packageName = packageName,
                        cls = act.cls,
                        title = act.name,
                        subtitle = act.cls,
                        isLauncher = act.isLauncher
                    )
                }
            }
        }
    }
}
