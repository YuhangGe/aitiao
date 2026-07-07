package me.geekabe.aitiao.ui

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import me.geekabe.aitiao.AiSettings
import me.geekabe.aitiao.AitiaoAccessibilityService
import me.geekabe.aitiao.AppInfo
import me.geekabe.aitiao.SkipStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLog: () -> Unit = {},
    onNavigateToConfig: () -> Unit = {},
    onAppClick: (AppInfo) -> Unit = {}
) {
    val context = LocalContext.current
    val allApps = remember { mutableStateListOf<AppInfo>() }
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var loading by remember { mutableStateOf(true) }

    // 每次进入此页面都重新加载、刷新各开关状态
    var a11yEnabled by remember { mutableStateOf(false) }
    var batteryOptimized by remember { mutableStateOf(false) }
    var notificationEnabled by remember { mutableStateOf(false) }

    // 刷新各权限/服务状态
    fun refreshStatus() {
        a11yEnabled = AitiaoAccessibilityService.isEnabled(context)
        batteryOptimized = AitiaoAccessibilityService.isBatteryOptimizationDisabled(context)
        notificationEnabled = areNotificationsEnabled(context)
    }

    LaunchedEffect(Unit) {
        val loaded = loadInstalledApps(context, prefs)
        allApps.clear()
        allApps.addAll(loaded)
        refreshStatus()
        loading = false
    }

    // 从系统设置页返回后立刻刷新 banner 状态
    // 同时监听停止服务的广播（通知栏停止按钮也会发送此广播）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val stopReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AitiaoAccessibilityService.ACTION_STOP_SERVICE) {
                    a11yEnabled = false
                }
            }
        }
        val filter = IntentFilter(AitiaoAccessibilityService.ACTION_STOP_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(stopReceiver, filter)
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try { context.unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        }
    }

    val skipApps = allApps.filter { it.skipStatus == SkipStatus.SKIP }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("爱跳跳") },
                actions = {
                    IconButton(onClick = onNavigateToConfig) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "添加应用",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    var showOverflowMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "更多操作",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("日志") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Description,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToLog()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    onNavigateToSettings()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                // ── Banner 1: 通知权限 ──────────────────────────
                if (!notificationEnabled) {
                    item(key = "banner_notification") {
                        StatusBanner(
                            message = "通知权限未开启，应用可能被系统杀死",
                            actionLabel = "开启",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                )
                            }
                        )
                    }
                }

                // ── Banner 2: 电池优化 ──────────────────────────
                if (!batteryOptimized) {
                    item(key = "banner_battery") {
                        StatusBanner(
                            message = "电池优化未开启，后台运行可能受限",
                            actionLabel = "开启",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            onClick = {
                                context.startActivity(
                                    AitiaoAccessibilityService.openBatteryOptimizationIntent()
                                )
                            }
                        )
                    }
                }

                // ── Banner 3: 无障碍服务 ────────────────────────
                item(key = "banner_a11y") {
                    StatusBanner(
                        message = if (a11yEnabled) "无障碍服务运行中" else "需要开启无障碍服务才能自动跳过广告",
                        actionLabel = if (a11yEnabled) "停止" else "开启",
                        containerColor = if (a11yEnabled)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer,
                        onClick = {
                            if (a11yEnabled) {
                                val stopIntent = Intent(AitiaoAccessibilityService.ACTION_STOP_SERVICE).apply {
                                    setPackage(context.packageName)
                                }
                                context.sendBroadcast(stopIntent)
                                a11yEnabled = false
                            } else {
                                val config = AiSettings.load(context)
                                if (config.apiKey.isBlank() || config.modelId.isBlank()) {
                                    Toast.makeText(context, "请先配置 AI 大模型参数", Toast.LENGTH_SHORT).show()
                                } else {
                                    context.startActivity(AitiaoAccessibilityService.openSettingsIntent())
                                }
                            }
                        }
                    )
                }

                // ── 应用列表或空状态 ────────────────────────────
                if (skipApps.isEmpty()) {
                    item(key = "empty_state") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "请添加需要跳过广告的应用",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                } else {
                    items(items = skipApps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            showStatusDropdown = false,
                            onClick = { onAppClick(app) }
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
    }
}

// ═══════════════════════════════════════════════════════════════════
// Banner 组件
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun StatusBanner(
    message: String,
    actionLabel: String,
    containerColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════════════════

/** 检查通知是否已开启 */
private fun areNotificationsEnabled(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.areNotificationsEnabled()
    } else {
        true
    }
}

/** 单行：应用图标 + 名称 + 可选的跳过状态下拉 */
@Composable
fun AppRow(
    app: AppInfo,
    showStatusDropdown: Boolean = true,
    onSkipStatusChanged: ((SkipStatus) -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val rowModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply { setImageDrawable(app.icon) }
            },
            modifier = Modifier.size(48.dp),
            update = { imageView -> imageView.setImageDrawable(app.icon) }
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = app.appName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (showStatusDropdown && onSkipStatusChanged != null) {
            Spacer(modifier = Modifier.width(8.dp))
            SkipStatusDropdown(
                currentStatus = app.skipStatus,
                onStatusChanged = onSkipStatusChanged
            )
        }
    }
}

@Composable
fun SkipStatusDropdown(
    currentStatus: SkipStatus,
    onStatusChanged: (SkipStatus) -> Unit
) {
    var showDropdown by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier.clickable { showDropdown = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentStatus.label,
                fontSize = 14.sp,
                fontWeight = if (currentStatus == SkipStatus.SKIP) FontWeight.Bold else FontWeight.Normal,
                color = if (currentStatus == SkipStatus.SKIP)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DropdownMenu(
            expanded = showDropdown,
            onDismissRequest = { showDropdown = false }
        ) {
            SkipStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = status.label,
                            fontWeight = if (currentStatus == status) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        showDropdown = false
                        onStatusChanged(status)
                    }
                )
            }
        }
    }
}

fun sortApps(apps: MutableList<AppInfo>) {
    apps.sortWith(
        compareBy<AppInfo> { it.skipStatus != SkipStatus.SKIP }
            .thenBy { it.appName }
    )
}

fun loadInstalledApps(
    context: Context,
    prefs: android.content.SharedPreferences
): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val activities = pm.queryIntentActivities(intent, 0)
    val seen = mutableSetOf<String>()

    return activities.mapNotNull { resolveInfo ->
        val pkg = resolveInfo.activityInfo.packageName
        if (!seen.add(pkg)) return@mapNotNull null

        val appInfo = AppInfo(
            packageName = pkg,
            appName = resolveInfo.loadLabel(pm).toString(),
            icon = resolveInfo.loadIcon(pm)
        )
        val shouldSkip = prefs.getBoolean(pkg, false)
        appInfo.skipStatus = if (shouldSkip) SkipStatus.SKIP else SkipStatus.DONT_SKIP
        appInfo
    }
}

const val PREFS_NAME = "skip_status"
