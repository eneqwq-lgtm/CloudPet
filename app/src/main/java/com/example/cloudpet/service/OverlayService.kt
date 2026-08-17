package com.example.cloudpet.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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
        startForeground(NOTIFICATION_ID, buildNotification("云宝正在看着你~"))
        setupOverlay()
        startPolling()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

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
            x = 50
            y = 300
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

    private var lastKbState = false
    private var lastBatteryState = ""

    private fun startPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                // 检测键盘状态
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val kbActive = imm.isAcceptingText
                if (kbActive != lastKbState) {
                    lastKbState = kbActive
                    val state = if (kbActive) "typing" else "idle"
                    overlayView?.evaluateJavascript(
                        "window.petEngine && window.petEngine.setState('$state')", null
                    )
                }

                // 检测充电状态
                val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val plugged = intent?.getIntExtra("plugged", -1) ?: -1
                val isCharging = plugged == android.os.BatteryManager.BATTERY_PLUGGED_AC ||
                                 plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB
                val battState = if (isCharging) "charging" else ""
                if (battState != lastBatteryState) {
                    lastBatteryState = battState
                    if (isCharging) {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.setState('charging')", null
                        )
                    }
                }

                // 从 Supabase 获取状态
                supabase.fetchPetState { state ->
                    if (!kbActive && !isCharging) {
                        val mood = state ?: "idle"
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.setState('$mood')", null
                        )
                    }
                }
                handler.postDelayed(this, 3000)
            }
        }, 1000)
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
                        params?.x = initialX + dx
                        params?.y = initialY + dy
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
            .setContentTitle("☁️ 云宝")
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
                CHANNEL_ID, "云宝",
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
