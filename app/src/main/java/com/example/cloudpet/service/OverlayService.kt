package com.example.cloudpet.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import com.example.cloudpet.data.SupabaseClient

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val supabase = SupabaseClient()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "cloudpet_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 160
        private const val PET_HEIGHT_DP = 200
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("🦀 小螃蟹正在看着你~"))
        setupOverlay()
        startPolling()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - dpToPx(PET_SIZE_DP) - 20
            y = 100
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    // === 状态追踪 ===
    private var lastKbState = false
    private var lastChargingState = false
    private var lastForegroundApp = ""
    private var isMiniPeek = false
    private var screenWidth = 0
    private var lastOnceState = ""

    private fun startPolling() {
        screenWidth = resources.displayMetrics.widthPixels

        handler.postDelayed(object : Runnable {
            override fun run() {
                val displayMetrics = resources.displayMetrics
                screenWidth = displayMetrics.widthPixels

                // 1. 检测键盘状态（最高优先级）
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val kbActive = imm.isAcceptingText
                if (kbActive != lastKbState) {
                    lastKbState = kbActive
                    if (kbActive) {
                        isMiniPeek = false  // 键盘打字时退出边缘模式
                        pushState("thinking", "💭 打字中...")
                    } else {
                        // 键盘关闭：如果不在 Termux 中则恢复 idle
                        if (!lastForegroundApp.contains("termux")) {
                            if (isMiniPeek) {
                                pushState("peek", "🦀 偷偷看")
                            } else {
                                pushState("idle", "🦀")
                            }
                        }
                    }
                }
                // 键盘开着时跳过其他检测
                if (kbActive) {
                    handler.postDelayed(this, 2000)
                    return
                }

                // 2. 检测充电状态（只触发一次，once 播放）
                val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val plugged = intent?.getIntExtra("plugged", -1) ?: -1
                val isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                                 plugged == BatteryManager.BATTERY_PLUGGED_USB
                if (isCharging && !lastChargingState) {
                    lastChargingState = true
                    pushStateOnce("error", "🔋 充电中...")
                } else if (!isCharging) {
                    lastChargingState = false
                }

                // 3. 检测前台 App
                val currentApp = getForegroundApp()
                if (currentApp != null) {
                    if (currentApp != lastForegroundApp) {
                        // 离开旧 App 时清理
                        val oldApp = lastForegroundApp
                        lastForegroundApp = currentApp
                        handleAppSwitch(currentApp, oldApp)
                    } else if (currentApp.contains("termux")) {
                        // 持续在 Termux 中：保持 typing 状态
                        // 仅当不在边缘模式且不在 once 过渡中时刷新
                        if (!isMiniPeek && lastOnceState.isEmpty()) {
                            pushState("typing", "⌨️ Termux 中...")
                        }
                    }
                } else {
                    // 无法检测前台 App 时，清除记录
                    if (lastForegroundApp.isNotEmpty()) {
                        lastForegroundApp = ""
                        if (!isMiniPeek) {
                            pushState("idle", "🦀")
                        }
                    }
                }

                // 4. 边缘检测（最低优先级）
                // 仅在非特殊状态下执行
                if (lastOnceState.isEmpty() && !lastForegroundApp.contains("termux")) {
                    checkEdgePosition()
                }

                // 5. Supabase 状态同步 (仅在没有本地活跃状态时应用)
                supabase.fetchPetState { state ->
                    if (state != null && lastOnceState.isEmpty() && !lastForegroundApp.contains("termux")) {
                        // 仅在 idle/peek 时接受远程状态,不覆盖本地活跃状态
                        if (isMiniPeek) {
                            pushState("peek", "🦀 偷偷看")
                        } else {
                            pushState(state, "")
                        }
                    }
                }

                handler.postDelayed(this, 2000)
            }
        }, 1000)
    }

    private fun hasUsageStatsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    private fun getForegroundApp(): String? {
        // 前置检查: 用户是否授予了"使用情况访问"权限
        if (!hasUsageStatsPermission()) return null

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val window = 300_000L  // 5 分钟窗口

        // 首选: queryEvents 遍历 MOVE_TO_FOREGROUND 事件,取最后一条(最准确)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val events = usm.queryEvents(now - window, now)
                var lastFg: String? = null
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        lastFg = event.packageName
                    }
                }
                if (lastFg != null && lastFg != packageName) return lastFg
            } catch (_: Exception) {}
        }

        // 兜底: queryUsageStats 按 lastTimeUsed 排序取最近
        try {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - window, now)
            if (stats != null && stats.isNotEmpty()) {
                val top = stats.sortedByDescending { it.lastTimeUsed }
                    .firstOrNull { it.packageName != packageName }
                    ?.packageName
                if (top != null) return top
            }
        } catch (_: Exception) {}
        return null
    }

    private fun handleAppSwitch(pkg: String, oldPkg: String) {
        when {
            pkg.contains("termux") -> {
                isMiniPeek = false  // 进入 Termux 时退出边缘模式
                pushState("typing", "⌨️ Termux 中...")
                // 离开 Termux 时恢复由轮询中的 app 切换处理
            }
            pkg.contains("microsoft.emmx") || pkg.contains("edge") -> {
                pushStateOnce("debugger", "🔍 Edge 浏览器")
            }
            pkg.contains("cloudmusic") || pkg.contains("netease") -> {
                pushStateOnce("groove", "🎵 听歌中")
            }
            pkg.contains("notes") || pkg.contains("notepad") || pkg.contains("keep") -> {
                pushStateOnce("reading", "📖 看笔记")
            }
            else -> {
                // 非特殊 App，恢复待机或边缘模式
                if (isMiniPeek) {
                    pushState("peek", "🦀 偷偷看")
                } else {
                    pushState("idle", "🦀")
                }
            }
        }
    }

    private fun checkEdgePosition() {
        val x = params?.x ?: 0
        val petW = dpToPx(PET_SIZE_DP)
        val edgeThreshold = dpToPx(30)
        val atLeftEdge = x < edgeThreshold
        val atRightEdge = x > screenWidth - petW - edgeThreshold
        val shouldPeek = atLeftEdge || atRightEdge

        if (shouldPeek && !isMiniPeek) {
            isMiniPeek = true
            // 吸附到边缘
            params?.x = if (atLeftEdge) 0 else screenWidth - petW
            windowManager?.updateViewLayout(overlayView, params)
            pushState("peek", "🦀 偷偷看")
        } else if (!shouldPeek && isMiniPeek) {
            isMiniPeek = false
            pushState("idle", "🦀")
        }
    }

    private fun pushStateOnce(state: String, text: String) {
        if (state == lastOnceState) return
        lastOnceState = state
        isMiniPeek = false  // 播放 once 动画时退出边缘模式
        pushState(state, text)
        // 3秒后自动恢复
        handler.postDelayed({
            lastOnceState = ""
            if (isMiniPeek) {
                pushState("peek", "🦀 偷偷看")
            } else {
                pushState("idle", "🦀")
            }
        }, 3000)
    }

    private fun pushState(state: String, text: String) {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.setState('$state', '$text')", null
        )
    }

    // === 手势处理 ===
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        var newX = initialX + dx
                        var newY = initialY + dy
                        val petW = dpToPx(PET_SIZE_DP)
                        val petH = dpToPx(PET_HEIGHT_DP)
                        newX = newX.coerceIn(0, screenWidth - petW)
                        newY = newY.coerceIn(0, resources.displayMetrics.heightPixels - petH)
                        params?.x = newX
                        params?.y = newY
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    } else {
                        // 松手后立即吸附边缘
                        checkEdgePosition()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onTap()", null)
        supabase.logGesture("tap")
    }

    private fun onDoubleTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDoubleTap()", null)
        supabase.logGesture("double_tap")
    }

    private fun onLongPress() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLongPress()", null)
        supabase.logGesture("long_press")
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🦀 Clawd")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Clawd",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}