package com.example.cloudpet.service

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
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

    private fun startPolling() {
        screenWidth = resources.displayMetrics.widthPixels

        handler.postDelayed(object : Runnable {
            override fun run() {
                val displayMetrics = resources.displayMetrics
                screenWidth = displayMetrics.widthPixels

                // 1. 检测键盘状态
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val kbActive = imm.isAcceptingText
                if (kbActive != lastKbState) {
                    lastKbState = kbActive
                    if (kbActive) {
                        pushState("thinking", "💭 打字中...")
                    } else {
                        pushState("idle", "🦀")
                    }
                }

                // 如果键盘开着，优先显示打字状态，不执行其他检测
                if (kbActive) {
                    handler.postDelayed(this, 2000)
                    return
                }

                // 2. 检测充电状态（只触发一次）
                val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val plugged = intent?.getIntExtra("plugged", -1) ?: -1
                val isCharging = plugged == android.os.BatteryManager.BATTERY_PLUGGED_AC ||
                                 plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB
                if (isCharging && !lastChargingState) {
                    lastChargingState = true
                    pushState("error", "🔋 充电中...")
                    // 2秒后恢复待机
                    handler.postDelayed({
                        pushState("idle", "🦀")
                    }, 2000)
                } else if (!isCharging) {
                    lastChargingState = false
                }

                // 3. 检测前台 App
                val currentApp = getForegroundApp()
                if (currentApp != null && currentApp != lastForegroundApp) {
                    lastForegroundApp = currentApp
                    handleAppSwitch(currentApp)
                }

                // 4. 边缘检测
                checkEdgePosition()

                // 5. Supabase 状态同步
                supabase.fetchPetState { state ->
                    if (state != null) {
                        pushState(state, "")
                    }
                }

                handler.postDelayed(this, 2000)
            }
        }, 1000)
    }

    private fun getForegroundApp(): String? {
        // 方法1: 使用 UsageStatsManager (需要权限)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val time = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 2000, time)
                if (stats != null && stats.isNotEmpty()) {
                    val sorted = stats.sortedByDescending { it.lastTimeUsed }
                    return sorted[0].packageName
                }
            } catch (_: Exception) {}
        }
        // 方法2: 使用 RunningAppProcessInfo (不需要权限，Android 11+ 受限)
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
                val ranks = am.runningAppProcesses
                if (ranks != null && ranks.isNotEmpty()) {
                    for (info in ranks) {
                        if (info.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            return info.processName.split(":").first()
                        }
                    }
                }
            } else {
                val processes = am.runningAppProcesses
                if (processes != null && processes.isNotEmpty()) {
                    for (p in processes) {
                        if (p.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            return p.pkgList.firstOrNull()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun handleAppSwitch(pkg: String) {
        when {
            pkg.contains("termux") -> {
                pushState("typing", "⌨️ Termux 中...")
                // 离开 Termux 时恢复，通过轮询检测
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
                // 非特殊 App，恢复待机
                if (!isMiniPeek) {
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

    private var lastOnceState = ""
    private fun pushStateOnce(state: String, text: String) {
        if (state == lastOnceState) return
        lastOnceState = state
        pushState(state, text)
        // 3秒后自动恢复
        handler.postDelayed({
            lastOnceState = ""
            if (!isMiniPeek) {
                pushState("idle", "🦀")
            } else {
                pushState("peek", "🦀 偷偷看")
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
                        // 计算新位置并限制在屏幕内
                        var newX = initialX + dx
                        var newY = initialY + dy
                        val petW = dpToPx(PET_SIZE_DP)
                        val petH = dpToPx(PET_HEIGHT_DP)
                        // 限制左右边界
                        newX = newX.coerceIn(-dpToPx(30), screenWidth - petW + dpToPx(30))
                        // 限制上下边界
                        newY = newY.coerceIn(-dpToPx(30), resources.displayMetrics.heightPixels - petH + dpToPx(30))
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