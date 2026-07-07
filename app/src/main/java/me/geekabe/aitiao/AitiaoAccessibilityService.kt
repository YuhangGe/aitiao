package me.geekabe.aitiao

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.graphics.scale

/**
 * 爱跳跳无障碍服务
 *
 * 广告检测流程：
 * 1. View 指纹匹配 → 命中则直接用缓存坐标点击（毫秒级）
 * 2. 指纹未命中 → 截图发给大模型识别 → 识别到广告后点击 + 缓存指纹
 */
class AitiaoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AitiaoA11y"
        private const val NOTIFICATION_CHANNEL_ID = "aitiao_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP_SERVICE = "me.geekabe.aitiao.STOP_SERVICE"

        private var instanceRef: WeakReference<AitiaoAccessibilityService>? = null

        private const val DEBOUNCE_MS = 5_000L
        private const val RENDER_DELAY_MS = 1200L

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

        fun openBatteryOptimizationIntent(): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:me.geekabe.aitiao")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var skipPackages: Set<String> = emptySet()
     private var lastTriggerTime: MutableMap<String, Long> = mutableMapOf()
    private var currentForegroundPackage: String? = null
    private val stopReceiver = StopReceiver()
    private var skipCount: Int = 0

    // ─── 停止广播接收器 ──────────────────────────────────────────

    /**
     * 接收通知栏"停止"按钮的广播，停止无障碍服务并关闭通知。
     * 作为嵌套类（非 inner），编译为静态类，系统可通过无参构造函数实例化。
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

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onServiceConnected() {
        super.onServiceConnected()
        LogCollector.i(TAG, "无障碍服务已连接")
        instanceRef = WeakReference(this)
        ContextCompat.registerReceiver(
            this, stopReceiver, IntentFilter(ACTION_STOP_SERVICE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshSkipList()
        showPersistentNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isBlank()) return
        if (packageName == this.packageName) return

        // 同一应用内的窗口切换忽略，仅在新应用进入前台时处理
        if (packageName == currentForegroundPackage) return
        currentForegroundPackage = packageName

        if (packageName !in skipPackages) return

        // 确认目标应用窗口确实处于活跃状态。
        // TYPE_WINDOW_STATE_CHANGED 没有前后台区分参数，系统可能在切后台时
        // 发送残留事件（包名仍是目标应用），需通过 rootInActiveWindow 最终确认。
        if (rootInActiveWindow == null) return

        val now = System.currentTimeMillis()
        val lastTime = lastTriggerTime[packageName]
        if (lastTime != null && (now - lastTime) < DEBOUNCE_MS) return

        lastTriggerTime[packageName] = now
        LogCollector.i(TAG, "检测到目标应用启动: $packageName")

        handleAdSkip(packageName)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onInterrupt() {
        LogCollector.w(TAG, "无障碍服务被中断，尝试恢复…")
        refreshSkipList()
        showPersistentNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
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

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showPersistentNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
        }

        createNotificationChannel()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateNotificationAppCount() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildNotification(): Notification {
        // 点击通知 → 打开主程序
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
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
            this, 0, stopIntent,
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
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    // ─── 跳过列表管理 ───────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    private fun refreshSkipList() {
        val prefs = getSharedPreferences("skip_status", Context.MODE_PRIVATE)
        skipPackages = prefs.all
            .filter { (_, value) -> value == true }
            .keys
        LogCollector.i(TAG, "跳过列表已刷新: ${skipPackages.size} 个应用")
        try { updateNotificationAppCount() } catch (_: Exception) {}
    }

    // ─── 广告跳过主流程 ──────────────────────────────────────────

    private fun handleAdSkip(packageName: String) {
        if (packageName !in skipPackages) return

        serviceScope.launch {
            // 等待页面渲染完成
            delay(RENDER_DELAY_MS.milliseconds)
            if (packageName !in skipPackages) return@launch

            // Step 1: 检查 View 指纹缓存
            val root = rootInActiveWindow
            if (root == null) {
                LogCollector.w(TAG, "rootInActiveWindow not found!")
                return@launch
            }

            val rootViewIdFingerprint = ViewFingerprintCache.collectViewIdFingerprint(root)
            val cached = ViewFingerprintCache.get(packageName, rootViewIdFingerprint, this@AitiaoAccessibilityService)
            if (cached != null) {
                if (cached.isAdPage) {
                    LogCollector.i(TAG, "找到缓存，跳过按钮位置: (${cached.skipX}, ${cached.skipY})")
                    performClick(cached.skipX.toFloat(), cached.skipY.toFloat())
                } else {
                    LogCollector.i(TAG, "找到缓存，非广告页。")
                }
                return@launch
            }

            // Step 2: 无缓存 → 截图 + 大模型识别
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                LogCollector.w(TAG, "当前系统版本不支持无障碍截图（需要 Android 14+）")
                return@launch
            }

            val config = AiSettings.load(this@AitiaoAccessibilityService)
            if (config.apiKey.isBlank() || config.modelId.isBlank()) {
                LogCollector.w(TAG, "AI 配置未完成，跳过识别")
                return@launch
            }

            LogCollector.i(TAG, "无缓存指纹，回退到 AI 识别…")

            val bitmap = takeScreenshotAsync() ?: run {
                Log.e(TAG, "截图失败或返回 null")
                return@launch
            }

            processAIResult(bitmap, config, packageName, rootViewIdFingerprint)
        }
    }

    /** 将截图回调转换为 suspend 函数 */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshotAsync(): Bitmap? {
        return suspendCancellableCoroutine { continuation ->
            takeScreenshot(
                0,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshotResult.hardwareBuffer,
                            screenshotResult.colorSpace
                        )
                        screenshotResult.hardwareBuffer.close()
                        continuation.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "截图失败, errorCode=$errorCode")
                        continuation.resume(null)
                    }
                }
            )
        }
    }

    // ─── AI 识别结果处理 ─────────────────────────────────────────

    private suspend fun processAIResult(bitmap: Bitmap, config: AiConfig, packageName: String, rootViewIdFingerprint: String) {
        LogCollector.i(TAG, "开始 AI 识别…")

        // 获取屏幕参数
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        // 将截图缩放到 1dp=1px，减少上传网络开销，同时让 AI 返回相对坐标
        val origWidth = bitmap.width
        val origHeight = bitmap.height
        val scaledWidth = (origWidth / density).toInt().coerceAtLeast(1)
        val scaledHeight = (origHeight / density).toInt().coerceAtLeast(1)
        val scaledBitmap = bitmap.scale(scaledWidth, scaledHeight)
        bitmap.recycle()

        LogCollector.i(TAG, "截图已缩放: ${origWidth}x${origHeight} → ${scaledWidth}x${scaledHeight} (density=$density)")

        // 网络 IO 切换到后台线程
        val result = withContext(Dispatchers.IO) {
            AdDetector.detectAd(scaledBitmap, screenWidth, screenHeight, config)
        }
        scaledBitmap.recycle()

        if (result.error != null) {
            Log.e(TAG, "AI 识别错误: ${result.error}")
            return
        }

        if (result.isAd) {
            LogCollector.i(TAG, "AI 检测到开屏广告，跳过按钮: (${result.skipX}, ${result.skipY})")
            val fingerprint = ViewFingerprintCache.fromAd(rootViewIdFingerprint, result.skipX, result.skipY)
            ViewFingerprintCache.put(packageName, rootViewIdFingerprint, fingerprint, this)
            performClick(result.skipX.toFloat(), result.skipY.toFloat())
        } else {
            LogCollector.i(TAG, "AI 判断非广告页面")
            val fingerprint = ViewFingerprintCache.fromNoAd(rootViewIdFingerprint)
            ViewFingerprintCache.put(packageName, rootViewIdFingerprint, fingerprint, this)
        }
    }

    // ─── 点击手势 ────────────────────────────────────────────────

    private fun performClick(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }

        val stroke = GestureDescription.StrokeDescription(path, 0, 100)

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val dispatched = dispatchGesture(gesture, null, null)
        if (dispatched) {
            LogCollector.i(TAG, "已在 ($x, $y) 执行点击")
            Toast.makeText(this, "已跳过广告", Toast.LENGTH_SHORT).show()
            skipCount++
            try { updateNotificationAppCount() } catch (_: Exception) {}
        } else {
            Log.e(TAG, "点击手势派发失败")
        }
    }
}
