package me.geekabe.aitiao

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * 爱跳跳无障碍服务
 *
 * 广告检测流程：
 * 1. 启动窗口内先找无障碍文本（跳过/skip/关闭），命中直接点击
 * 2. 文本未命中 → 查页面指纹缓存（带版本/分辨率/TTL 校验）
 * 3. 缓存未命中 → 截图发给大模型识别 → 点击 + 缓存
 *
 * 所有点击前都会二次校验前台包、页面指纹和截图时效，避免用户切走后误点。
 */
class AitiaoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AitiaoA11y"
        private const val NOTIFICATION_CHANNEL_ID = "aitiao_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP_SERVICE = "me.geekabe.aitiao.STOP_SERVICE"

        /** 前台切换的 debounce，避免同一应用快速重启时重复识别 */
        private const val FOREGROUND_DEBOUNCE_MS = 2_000L

        /** 同一应用内 Activity 切换的 debounce，允许 logo → 广告页的二次触发 */
        private const val ACTIVITY_DEBOUNCE_MS = 300L

        /** 应用进入前台后，只在这个窗口内做广告检测 */
        private const val LAUNCH_WINDOW_MS = 8_000L

        private const val MAX_DETECT_ATTEMPTS = 8
        private const val RETRY_DELAY_MS = 400L
        private const val POST_CLICK_CHECK_MS = 500L
        private const val MAX_SCREENSHOT_AGE_MS = 2_500L
        private const val MIN_CLICK_INTERVAL_MS = 800L

        // 文本优先只匹配强广告信号；"关闭"可能出现在内容页弹窗上，交给 AI 兜底
        private val SKIP_TEXTS = listOf(
            "跳过", "跳过广告", "skip", "Skip", "SKIP"
        )

        private var instanceRef: WeakReference<AitiaoAccessibilityService>? = null

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val serviceName = "${context.packageName}/${AitiaoAccessibilityService::class.java.name}"
            return enabledServices.contains(serviceName) ||
                enabledServices.contains(AitiaoAccessibilityService::class.java.name)
        }

        fun openSettingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        fun isBatteryOptimizationDisabled(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        fun openBatteryOptimizationIntent(context: Context): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var skipPackages: Set<String> = emptySet()
    private val lastTriggerTime: MutableMap<String, Long> = mutableMapOf()
    private var currentForegroundPackage: String? = null
    private var currentActivityClass: String? = null
    private var foregroundSince: Long = 0L
    private var windowGeneration: Long = 0L
    private var detectionJob: Job? = null
    private var lastClickAt: Long = 0L
    private val confirmationInFlight: MutableSet<String> = mutableSetOf()
    private val stopReceiver = StopReceiver()
    private var skipCount: Int = 0

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshSkipList()
        try {
            updateNotificationAppCount()
        } catch (_: Exception) {
        }
    }

    // ─── 停止广播接收器 ──────────────────────────────────────────

    /**
     * 接收通知栏"停止"按钮的广播，停止无障碍服务并关闭通知。
     * 作为嵌套类（非 inner），编译为静态类。
     */
    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STOP_SERVICE) return
            LogCollector.i(TAG, "用户通过通知停止服务")
            val service = instanceRef?.get()
            service?.cancelNotification()
            service?.disableSelf()
        }
    }

    // ─── 生命周期 ────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogCollector.i(TAG, "无障碍服务已连接")
        instanceRef = WeakReference(this)
        // 重连时重置前台状态，避免第一次事件被当成"同包"而漏检
        currentForegroundPackage = null
        currentActivityClass = null
        foregroundSince = 0L
        windowGeneration++
        detectionJob?.cancel()
        detectionJob = null
        confirmationInFlight.clear()
        try {
            unregisterReceiver(stopReceiver)
        } catch (_: Exception) {
        }
        ContextCompat.registerReceiver(
            this,
            stopReceiver,
            IntentFilter(ACTION_STOP_SERVICE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshSkipList()
        getSharedPreferences("skip_status", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsChangeListener)
        showPersistentNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        try {
            val packageName = event.packageName?.toString()
                ?.takeIf { it.isNotBlank() } ?: return
            val activityClass = event.className?.toString().orEmpty()
            val now = SystemClock.elapsedRealtime()

            val foregroundChanged = packageName != currentForegroundPackage
            val activityChanged = !foregroundChanged &&
                activityClass.isNotBlank() &&
                activityClass != currentActivityClass

            currentForegroundPackage = packageName
            currentActivityClass = activityClass

            if (foregroundChanged) {
                foregroundSince = now
                windowGeneration++
                detectionJob?.cancel()
                detectionJob = null
            }

            // 自己的界面只更新前台状态，避免 A → 爱跳跳 → A 时状态卡在 A
            if (packageName == this.packageName) return
            if (packageName !in skipPackages) return
            if (!foregroundChanged && !activityChanged) return
            if (now - foregroundSince > LAUNCH_WINDOW_MS) return

            val last = lastTriggerTime[packageName] ?: 0L
            val debounce = if (foregroundChanged) {
                FOREGROUND_DEBOUNCE_MS
            } else {
                ACTIVITY_DEBOUNCE_MS
            }
            if (now - last < debounce) return
            lastTriggerTime[packageName] = now

            val gen = windowGeneration
            detectionJob?.cancel()
            detectionJob = serviceScope.launch {
                try {
                    handleAdSkip(packageName, activityClass, gen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogCollector.e(TAG, "广告检测异常: ${e.message}")
                }
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "处理窗口事件异常: ${e.message}")
        }
    }

    override fun onInterrupt() {
        LogCollector.w(TAG, "无障碍服务被中断，尝试恢复…")
        refreshSkipList()
        showPersistentNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        detectionJob?.cancel()
        try {
            unregisterReceiver(stopReceiver)
        } catch (_: Exception) {
        }
        getSharedPreferences("skip_status", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
        instanceRef = null
        cancelNotification()
        serviceScope.cancel()
        LogCollector.i(TAG, "无障碍服务已销毁")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LogCollector.w(TAG, "无障碍服务 onUnbind，系统将在需要时重新绑定")
        return true
    }

    // ─── 常驻通知 ────────────────────────────────────────────────

    private fun showPersistentNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        createNotificationChannel()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updateNotificationAppCount() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        // 点击通知 → 打开主程序
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 停止按钮 → 发送广播给 StopReceiver
        val stopIntent = Intent(ACTION_STOP_SERVICE).apply {
            setPackage(packageName)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("爱跳跳正在运行")
            .setContentText("监控广告跳过中（${skipPackages.size}个应用，已跳过${skipCount}次）")
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        android.R.drawable.ic_media_pause
                    ),
                    "停止",
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    private fun cancelNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "爱跳跳服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示爱跳跳无障碍服务的运行状态"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    // ─── 跳过列表管理 ───────────────────────────────────────────

    private fun refreshSkipList() {
        val prefs = getSharedPreferences("skip_status", Context.MODE_PRIVATE)
        skipPackages = prefs.all
            .filter { (_, value) -> value == true }
            .keys
        LogCollector.i(TAG, "跳过列表已刷新: ${skipPackages.size} 个应用")
        try {
            updateNotificationAppCount()
        } catch (_: Exception) {
        }
    }

    // ─── 广告跳过主流程 ──────────────────────────────────────────

    private suspend fun handleAdSkip(
        packageName: String,
        activityClass: String,
        gen: Long
    ) {
        if (packageName !in skipPackages) return

        val config = AiSettings.load(this)
        var aiAttemptedFor: String? = null

        for (attempt in 0 until MAX_DETECT_ATTEMPTS) {
            // 首次等待页面渲染，后续快速重试
            delay(if (attempt == 0) config.renderDelayMs.toLong() else RETRY_DELAY_MS)

            if (gen != windowGeneration) return
            if (currentForegroundPackage != packageName) return
            if (packageName !in skipPackages) return
            if (SystemClock.elapsedRealtime() - foregroundSince > LAUNCH_WINDOW_MS) return

            val root = rootInActiveWindow ?: continue
            if (root.packageName?.toString() != packageName) continue

            val fingerprint = ViewFingerprintCache.collectFingerprint(root, activityClass)
            val aiKey = fingerprint.ifBlank { "__blank__" }
            val cached = if (fingerprint.isNotBlank()) {
                ViewFingerprintCache.get(packageName, fingerprint, this)
            } else {
                null
            }

            // Step 1: 负缓存优先。AI 已经判过非广告的页面，不再做文本点击，
            // 否则“AI 结果为准”不生效。
            if (cached != null && !cached.isAdPage) {
                LogCollector.i(TAG, "命中非广告缓存，跳过文本判断")
                return
            }

            // Step 2: 正缓存优先。缓存是 AI 确认过的，避免文本误点后 AI 修正了坐标、
            // 下次又被文本点回错误位置。
            if (cached != null && cached.isAdPage) {
                val dm = resources.displayMetrics
                LogCollector.i(
                    TAG,
                    "命中广告缓存: (${cached.skipXRatio}, ${cached.skipYRatio})"
                )
                if (clickRatio(
                        cached.skipXRatio,
                        cached.skipYRatio,
                        dm.widthPixels,
                        dm.heightPixels,
                        packageName,
                        activityClass,
                        fingerprint
                    )
                ) {
                    return
                }
                LogCollector.w(TAG, "缓存点击未成功，继续走实时识别")
            } else {
                // Step 3: 没有缓存时才文本优先；点击后后台 AI 确认并写缓存。
                val node = findSkipNode(root)
                if (node != null) {
                    LogCollector.i(TAG, "文本命中跳过按钮")
                    if (handleTextClick(
                            node,
                            packageName,
                            activityClass,
                            fingerprint,
                            gen,
                            config
                        )
                    ) {
                        return
                    }
                }
            }

            // Step 4: AI 首点（无文本、无缓存，或缓存点击失败）
            if (aiAttemptedFor == aiKey) continue
            if (config.apiKey.isBlank() || config.modelId.isBlank()) {
                LogCollector.w(TAG, "AI 配置未完成，跳过识别")
                return
            }

            LogCollector.i(TAG, "无文本/缓存，回退到 AI 识别…")
            val screenshot = takeScreenshotAsync() ?: continue
            aiAttemptedFor = aiKey

            val scaled = try {
                scaleForUpload(screenshot.bitmap)
            } catch (e: Exception) {
                screenshot.bitmap.recycle()
                LogCollector.e(TAG, "截图缩放失败: ${e.message}")
                continue
            }

            val result = try {
                withContext(Dispatchers.IO) {
                    AdDetector.detectAd(scaled, config)
                }
            } finally {
                if (scaled !== screenshot.bitmap) scaled.recycle()
                screenshot.bitmap.recycle()
            }

            if (result.error != null) {
                LogCollector.w(TAG, "AI 识别失败: ${result.error}")
                continue
            }
            if (!result.isAd) {
                if (fingerprint.isNotBlank()) {
                    ViewFingerprintCache.put(
                        packageName, fingerprint, false, 0f, 0f, this,
                        confirmedByAi = true
                    )
                    LogCollector.i(TAG, "AI 判断非广告，写入负缓存")
                }
                continue
            }
            if (!result.hasSkip) {
                if (fingerprint.isNotBlank()) {
                    ViewFingerprintCache.put(
                        packageName, fingerprint, false, 0f, 0f, this,
                        confirmedByAi = true
                    )
                }
                LogCollector.i(TAG, "AI 判断是广告但未找到跳过按钮，写入负缓存")
                continue
            }
            if (result.skipXRatio in 0.3f..0.7f && result.skipYRatio in 0.3f..0.7f) {
                LogCollector.w(TAG, "AI 返回的坐标在屏幕中央，拒绝点击")
                continue
            }

            // 点击前最后确认：前台包、页面指纹、截图时效都没变
            if (!isStillSameScreen(
                    packageName, activityClass, fingerprint, gen, screenshot
                )
            ) {
                LogCollector.w(TAG, "AI 返回时页面已变化，放弃本次结果并重试")
                continue
            }

            val x = result.skipXRatio * screenshot.width
            val y = result.skipYRatio * screenshot.height
            if (performClick(x, y)) {
                onSkipSuccess()
                if (fingerprint.isNotBlank()) {
                    val dm = resources.displayMetrics
                    ViewFingerprintCache.put(
                        packageName,
                        fingerprint,
                        true,
                        (x / dm.widthPixels).coerceIn(0f, 1f),
                        (y / dm.heightPixels).coerceIn(0f, 1f),
                        this,
                        confirmedByAi = true
                    )
                }
                return
            }
            LogCollector.w(TAG, "点击手势派发失败")
            return
        }

        LogCollector.w(TAG, "本次未识别到可跳过的广告")
    }

    // ─── 文本节点查找 ────────────────────────────────────────────

    /**
     * 在顶部/底部区域找"跳过/skip"节点，避免内容页正文里的同名文字误命中。
     */
    private fun findSkipNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val topLimit = dm.heightPixels * 0.30f
        val bottomLimit = dm.heightPixels * 0.70f

        for (text in SKIP_TEXTS) {
            val nodes = try {
                root.findAccessibilityNodeInfosByText(text)
            } catch (e: Exception) {
                LogCollector.w(TAG, "查找 '$text' 异常: ${e.message}")
                null
            } ?: continue

            for (node in nodes) {
                if (!node.isVisibleToUser) continue
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) continue

                val centerY = rect.centerY()
                if (centerY in topLimit.toInt()..bottomLimit.toInt()) continue
                return node
            }
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (depth < 6) {
            val candidate = current ?: break
            if (candidate.isClickable) return candidate
            current = candidate.parent
            depth++
        }
        return null
    }

    // ─── 截图 ────────────────────────────────────────────────────

    private data class Screenshot(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val timestamp: Long
    )

    /** 将截图回调转换为 suspend 函数 */
    private suspend fun takeScreenshotAsync(): Screenshot? {
        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    0,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val buffer = screenshotResult.hardwareBuffer
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(
                                    buffer,
                                    screenshotResult.colorSpace
                                )
                            } catch (e: Exception) {
                                LogCollector.e(TAG, "截图包装失败: ${e.message}")
                                null
                            } finally {
                                buffer.close()
                            }

                            if (bitmap == null) {
                                if (continuation.isActive) continuation.resume(null)
                                return
                            }
                            if (!continuation.isActive) {
                                bitmap.recycle()
                                return
                            }

                            val timestamp = if (screenshotResult.timestamp > 0L) {
                                screenshotResult.timestamp
                            } else {
                                SystemClock.uptimeMillis()
                            }
                            continuation.resume(
                                Screenshot(
                                    bitmap = bitmap,
                                    width = bitmap.width,
                                    height = bitmap.height,
                                    timestamp = timestamp
                                )
                            )
                        }

                        override fun onFailure(errorCode: Int) {
                            LogCollector.w(
                                TAG,
                                "截图失败: ${screenshotErrorMessage(errorCode)}"
                            )
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                LogCollector.e(TAG, "截图请求异常: ${e.message}")
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /** 截图 errorCode 对照，方便在应用内日志里定位问题 */
    private fun screenshotErrorMessage(code: Int): String = when (code) {
        1 -> "INTERNAL_ERROR"
        2 -> "NO_ACCESSIBILITY_ACCESS"
        3 -> "INTERVAL_TIME_SHORT（截图间隔需 >333ms）"
        4 -> "INVALID_DISPLAY"
        5 -> "INVALID_WINDOW"
        6 -> "SECURE_WINDOW（FLAG_SECURE）"
        else -> "UNKNOWN($code)"
    }

    /** 缩放到 1dp=1px，减少上传体积 */
    private fun scaleForUpload(bitmap: Bitmap): Bitmap {
        val density = resources.displayMetrics.density
        val width = (bitmap.width / density).toInt().coerceAtLeast(1)
        val height = (bitmap.height / density).toInt().coerceAtLeast(1)
        return if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            bitmap.scale(width, height)
        }
    }

    // ─── 结果校验与点击 ──────────────────────────────────────────

    /**
     * AI 返回后、点击前再次确认页面没变。
     */
    private fun isStillSameScreen(
        packageName: String,
        activityClass: String,
        fingerprint: String,
        gen: Long,
        screenshot: Screenshot
    ): Boolean {
        if (gen != windowGeneration) return false
        if (currentForegroundPackage != packageName) return false
        if (SystemClock.uptimeMillis() - screenshot.timestamp > MAX_SCREENSHOT_AGE_MS) {
            return false
        }

        val dm = resources.displayMetrics
        val rotated = (screenshot.width > screenshot.height) !=
            (dm.widthPixels > dm.heightPixels)
        if (rotated) return false

        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != packageName) return false

        if (fingerprint.isNotBlank()) {
            val nowFingerprint = ViewFingerprintCache.collectFingerprint(root, activityClass)
            if (nowFingerprint != fingerprint) return false
        }
        return true
    }

    /**
     * 文本命中后的点击路径。
     *
     * 如果 AI 可用且页面还没有缓存：点击前截图，点击后把带红圈的截图交给后台 AI 确认，
     * AI 结果作为写缓存的依据。截图失败则直接点击，但不在没有确认的情况下写缓存。
     */
    private suspend fun handleTextClick(
        node: AccessibilityNodeInfo,
        packageName: String,
        activityClass: String,
        fingerprint: String,
        gen: Long,
        config: AiConfig
    ): Boolean {
        val clickedText = node.text?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.trim().orEmpty()

        val aiEnabled = config.apiKey.isNotBlank() && config.modelId.isNotBlank()
        if (!aiEnabled || fingerprint.isBlank()) {
            return clickTextNode(node, packageName, activityClass, fingerprint, gen)
        }

        // 点击前截图，保证 AI 看到的是被点击的那一页
        val screenshot = takeScreenshotAsync()
        if (screenshot == null) {
            LogCollector.w(TAG, "确认截图失败，直接点击（不写缓存）")
            return clickTextNode(node, packageName, activityClass, fingerprint, gen)
        }

        // 截图有延迟，点击前重新确认节点和页面都还在
        if (!node.refresh() || !node.isVisibleToUser) {
            LogCollector.w(TAG, "截图后节点已失效，放弃点击")
            screenshot.bitmap.recycle()
            return false
        }
        if (!isStillSameScreen(packageName, activityClass, fingerprint, gen, screenshot)) {
            LogCollector.w(TAG, "截图后页面已变化，放弃点击")
            screenshot.bitmap.recycle()
            return false
        }

        val target = resolveClickTarget(node) ?: run {
            screenshot.bitmap.recycle()
            return false
        }

        if (!clickTarget(target)) {
            screenshot.bitmap.recycle()
            return false
        }
        onSkipSuccess()

        val display = resources.displayMetrics
        startConfirmation(
            packageName = packageName,
            fingerprint = fingerprint,
            screenshot = screenshot,
            ringRatioX = (target.x / screenshot.width).coerceIn(0f, 1f),
            ringRatioY = (target.y / screenshot.height).coerceIn(0f, 1f),
            cacheRatioX = (target.x / display.widthPixels).coerceIn(0f, 1f),
            cacheRatioY = (target.y / display.heightPixels).coerceIn(0f, 1f),
            clickedText = clickedText
        )
        return true
    }

    /** 不做 AI 确认的文本点击：没有 AI 配置、没有指纹或截图失败时使用。 */
    private fun clickTextNode(
        node: AccessibilityNodeInfo,
        packageName: String,
        activityClass: String,
        fingerprint: String,
        gen: Long
    ): Boolean {
        if (gen != windowGeneration || currentForegroundPackage != packageName) return false
        if (!node.refresh() || !node.isVisibleToUser) return false

        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != packageName) return false
        if (fingerprint.isNotBlank()) {
            val nowFingerprint = ViewFingerprintCache.collectFingerprint(root, activityClass)
            if (nowFingerprint != fingerprint) return false
        }

        val target = resolveClickTarget(node) ?: return false
        if (!clickTarget(target)) return false

        onSkipSuccess()
        return true
    }

    private data class ClickTarget(
        val x: Float,
        val y: Float,
        val clickable: AccessibilityNodeInfo?
    )

    private fun resolveClickTarget(node: AccessibilityNodeInfo): ClickTarget? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return null
        return ClickTarget(
            x = rect.centerX().toFloat(),
            y = rect.centerY().toFloat(),
            clickable = clickableAncestor(node)
        )
    }

    private fun clickTarget(target: ClickTarget): Boolean {
        if (performClick(target.x, target.y)) return true

        if (SystemClock.elapsedRealtime() - lastClickAt < MIN_CLICK_INTERVAL_MS) {
            return false
        }
        return try {
            target.clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 后台 AI 确认：把带红圈的点击前截图发给模型，用模型结果写缓存。
     * 独立协程，不跟随 detectionJob 取消。
     */
    private fun startConfirmation(
        packageName: String,
        fingerprint: String,
        screenshot: Screenshot,
        ringRatioX: Float,
        ringRatioY: Float,
        cacheRatioX: Float,
        cacheRatioY: Float,
        clickedText: String
    ) {
        if (!confirmationInFlight.add(fingerprint)) {
            screenshot.bitmap.recycle()
            return
        }

        serviceScope.launch {
            var upload: Bitmap? = null
            try {
                val config = AiSettings.load(this@AitiaoAccessibilityService)
                upload = withContext(Dispatchers.Default) {
                    prepareConfirmationBitmap(screenshot, ringRatioX, ringRatioY)
                }
                if (upload == null) {
                    LogCollector.w(TAG, "确认截图准备失败")
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    AdDetector.confirmAd(upload, config, clickedText)
                }
                applyConfirmResult(
                    packageName = packageName,
                    fingerprint = fingerprint,
                    clickRatioX = cacheRatioX,
                    clickRatioY = cacheRatioY,
                    screenshotWidth = screenshot.width,
                    screenshotHeight = screenshot.height,
                    result = result
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogCollector.e(TAG, "AI 确认异常: ${e.message}")
            } finally {
                upload?.recycle()
                screenshot.bitmap.recycle()
                confirmationInFlight.remove(fingerprint)
            }
        }
    }

    /** 把点击前截图缩放到 1dp=1px，并在点击位置画红圈。 */
    private fun prepareConfirmationBitmap(
        screenshot: Screenshot,
        ringRatioX: Float,
        ringRatioY: Float
    ): Bitmap? {
        // HardwareBitmap 不支持 getPixels，也不能直接画到软件 Canvas；
        // 用 JPEG 编解码转换成软件位图，再缩放、画红圈。
        val source = if (screenshot.bitmap.config == Bitmap.Config.HARDWARE) {
            try {
                val bytes = ByteArrayOutputStream()
                if (!screenshot.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes)) {
                    LogCollector.w(TAG, "确认截图压缩失败")
                    return null
                }
                val data = bytes.toByteArray()
                BitmapFactory.decodeByteArray(data, 0, data.size)
            } catch (e: Exception) {
                LogCollector.e(TAG, "确认截图转换软件位图失败: ${e.message}")
                null
            }
        } else {
            screenshot.bitmap
        } ?: return null

        return try {
            val density = resources.displayMetrics.density
            val width = (screenshot.width / density).toInt().coerceAtLeast(1)
            val height = (screenshot.height / density).toInt().coerceAtLeast(1)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val bitmapPaint = Paint().apply {
                isFilterBitmap = true
                isDither = true
            }
            canvas.drawBitmap(
                source,
                null,
                Rect(0, 0, width, height),
                bitmapPaint
            )

            val cx = ringRatioX * width
            val cy = ringRatioY * height
            val radius = 36f.coerceAtMost(minOf(width, height) / 4f)

            val whiteRing = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 8f
                isAntiAlias = true
            }
            val redRing = Paint().apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 4f
                isAntiAlias = true
            }
            canvas.drawCircle(cx, cy, radius, whiteRing)
            canvas.drawCircle(cx, cy, radius, redRing)
            output
        } catch (e: Exception) {
            LogCollector.e(TAG, "绘制确认红圈失败: ${e.message}")
            null
        } finally {
            if (source !== screenshot.bitmap) source.recycle()
        }
    }

    /** 用 AI 确认结果写缓存；AI 没确认或失败时不写。 */
    private fun applyConfirmResult(
        packageName: String,
        fingerprint: String,
        clickRatioX: Float,
        clickRatioY: Float,
        screenshotWidth: Int,
        screenshotHeight: Int,
        result: AdConfirmResult
    ) {
        if (result.error != null) {
            LogCollector.w(TAG, "AI 确认失败: ${result.error}")
            return
        }

        val display = resources.displayMetrics
        val rotated = (screenshotWidth > screenshotHeight) !=
            (display.widthPixels > display.heightPixels)
        if (rotated) {
            LogCollector.w(TAG, "AI 确认期间屏幕旋转，放弃写缓存")
            return
        }

        if (!result.isAd) {
            ViewFingerprintCache.put(
                packageName, fingerprint, false, 0f, 0f, this,
                confirmedByAi = true
            )
            LogCollector.i(TAG, "AI 确认非广告，写入负缓存")
            return
        }

        if (result.hasSkip) {
            val absX = result.skipXRatio * screenshotWidth
            val absY = result.skipYRatio * screenshotHeight
            val ratioX = (absX / display.widthPixels).coerceIn(0f, 1f)
            val ratioY = (absY / display.heightPixels).coerceIn(0f, 1f)
            if (!result.clickedIsSkip) {
                LogCollector.w(TAG, "AI 确认红圈不是跳过按钮，改用 AI 坐标写缓存")
            }
            ViewFingerprintCache.put(
                packageName, fingerprint, true, ratioX, ratioY, this,
                confirmedByAi = true
            )
            LogCollector.i(TAG, "AI 确认是广告，写入正缓存")
            return
        }

        if (result.clickedIsSkip) {
            ViewFingerprintCache.put(
                packageName, fingerprint, true, clickRatioX, clickRatioY, this,
                confirmedByAi = true
            )
            LogCollector.i(TAG, "AI 确认红圈是跳过按钮，用点击点写入正缓存")
            return
        }

        ViewFingerprintCache.put(
            packageName, fingerprint, false, 0f, 0f, this,
            confirmedByAi = true
        )
        LogCollector.i(TAG, "AI 确认没有可用的跳过按钮，写入负缓存")
    }

    private fun clickRatio(
        xRatio: Float,
        yRatio: Float,
        width: Int,
        height: Int,
        packageName: String,
        activityClass: String,
        fingerprint: String
    ): Boolean {
        if (xRatio <= 0f && yRatio <= 0f) return false
        val x = xRatio * width
        val y = yRatio * height
        if (!performClick(x, y)) return false

        onSkipSuccess()
        schedulePostClickCheck(packageName, activityClass, fingerprint)
        return true
    }

    private fun performClick(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false

        val dm = resources.displayMetrics
        if (x < 0f || y < 0f || x >= dm.widthPixels || y >= dm.heightPixels) {
            LogCollector.w(TAG, "点击坐标超出屏幕: ($x, $y)")
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastClickAt < MIN_CLICK_INTERVAL_MS) {
            LogCollector.w(TAG, "距离上次点击过近，忽略本次点击")
            return false
        }

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return try {
            val dispatched = dispatchGesture(gesture, null, null)
            if (dispatched) {
                lastClickAt = now
            } else {
                LogCollector.e(TAG, "点击手势派发失败")
            }
            dispatched
        } catch (e: Exception) {
            LogCollector.e(TAG, "点击手势异常: ${e.message}")
            false
        }
    }

    private fun onSkipSuccess() {
        LogCollector.i(TAG, "已执行跳过点击")
        Toast.makeText(this, "已跳过广告", Toast.LENGTH_SHORT).show()
        skipCount++
        try {
            updateNotificationAppCount()
        } catch (_: Exception) {
        }
    }

    /**
     * 点击后 500ms 回查：如果页面指纹没变，说明没跳掉，删掉这条缓存，避免下次继续误点。
     */
    private fun schedulePostClickCheck(
        packageName: String,
        activityClass: String,
        fingerprint: String
    ) {
        if (fingerprint.isBlank()) return

        serviceScope.launch {
            delay(POST_CLICK_CHECK_MS)
            if (currentForegroundPackage != packageName) return@launch
            if (currentActivityClass != activityClass) return@launch

            val root = rootInActiveWindow ?: return@launch
            if (root.packageName?.toString() != packageName) return@launch

            val nowFingerprint = ViewFingerprintCache.collectFingerprint(root, activityClass)
            if (nowFingerprint == fingerprint) {
                LogCollector.w(TAG, "点击后页面未变化，移除缓存")
                ViewFingerprintCache.removeFingerprint(packageName, fingerprint, this@AitiaoAccessibilityService)
            }
        }
    }
}
