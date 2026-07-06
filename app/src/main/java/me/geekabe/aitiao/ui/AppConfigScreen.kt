package me.geekabe.aitiao.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.geekabe.aitiao.SkipStatus
import androidx.core.content.edit

/**
 * 应用配置页：展示所有已安装应用，可配置跳过状态，点击不跳转详情。
 * 状态变更会持久化到 SharedPreferences，返回主页面后主页面自动反映最新状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppConfigScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val apps = remember { mutableStateListOf<me.geekabe.aitiao.AppInfo>() }
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    LaunchedEffect(Unit) {
        if (apps.isEmpty()) {
            val loaded = loadInstalledApps(context, prefs)
            apps.clear()
            apps.addAll(loaded)
            sortApps(apps)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        if (apps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("正在加载应用列表…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                items(items = apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        showStatusDropdown = true,
                        onSkipStatusChanged = { newStatus ->
                            app.skipStatus = newStatus
                            prefs.edit { putBoolean(app.packageName, newStatus == SkipStatus.SKIP) }
                            sortApps(apps)
                        },
                        onClick = null  // 配置页不支持点击跳转详情
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 80.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}
