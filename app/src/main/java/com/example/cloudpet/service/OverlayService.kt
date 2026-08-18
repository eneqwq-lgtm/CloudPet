package com.example.cloudpet.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.database.ContentObserver
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import com.example.cloudpet.data.SupabaseClient
import com.example.cloudpet.data.AIClient
import java.util.Calendar

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val supabase = SupabaseClient()
    private val ai = AIClient()
    private var aiGenerating = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val CHANNEL_ID = "cloudpet_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 160
        private const val PET_HEIGHT_DP = 200
    } // ---- 无固定文案库：AI 生成式气泡（见 AIClient + noteState()）----

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        loadAppLabels()   // 枚举设备上所有已安装 App，未知 App 也能知道它叫什么
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("☁️ 小克正在待机~"))
        setupOverlay()
        startPolling()
        registerScreenshotObserver()
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
            // 初始放屏幕中间偏右，不贴边 —— 只有用户拖动到边缘才进入吸附模式
            x = (screenWidth - dpToPx(PET_SIZE_DP)) / 2
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
    private var lastOnceStartAt = 0L
    private var lastAnyPushAt = 0L
    private var lastWindowSig = ""
    private var lastWindowNoteAt = 0L
    private var lastTrack: String? = null
    private var lastNoteAt = 0L
    private var lastBubbleText: String? = null
    private var lastBubbleShownAt = 0L
    private var lastBubbleInAppAt = 0L
    private var generationId = 0L
    private val recentBubbles = ArrayDeque<String>()  // 最近说过的气泡，防 AI 复读
    // 用户动作追踪（30 分钟无动作 → 碎碎念）
    private var lastUserActionAt = System.currentTimeMillis()
    private var lastUserActionSig = ""
    private var lastMurmurAt = 0L
    private var lastScreenOn = true

    // 本地碎碎念库：AI 掉线/超时时的兜底（60 分钟才一条，什么内容都可以）
    private val murmurs = listOf(
        "呼~ 好安静呀", "小克在数云朵，1、2、3", "记得喝水哦~", "屏幕盯久了，眨眨眼吧",
        "伸个懒腰~ 舒服", "今天天气好像不错", "想听你讲个故事", "小克打了个哈欠~",
        "安安静静也挺好", "要不要休息一下?", "月亮出来了吗?", "小克在看云发呆~",
        "悄悄挪了挪位置", "咕噜噜~ 肚子叫了", "你在忙吗?我在呢", "这里的风景真好",
        "发呆也是一种享受~", "小克偷偷笑了", "晚风轻飘飘的", "数完星星数你",
        "哼着歌等主人", "云朵软软的，想躺上去", "今天也要开心呀", "小克的壳壳亮晶晶~",
        "唔……今天做什么好呢", "咕嘟咕嘟，小克吐泡泡", "世界这么安静，真好", "要是有片海就好了",
        "小克的钳子有点痒~", "猜猜我在想什么?"
    )

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
                    refreshUserAction()
                    if (kbActive) {
                        isMiniPeek = false  // 键盘打字时退出边缘模式
                        pushState("thinking", "💭 打字中...")
                    } else {
                        // 键盘关闭：恢复安静待机（只切动画，不打断正在显示的气泡）
                        if (!lastForegroundApp.contains("termux")) {
                            switchAnim(if (isMiniPeek) "peek" else "idle")
                        }
                    }
                }
                // 键盘开着时跳过其他检测
                if (kbActive) {
                    handler.postDelayed(this, 2000)
                    return
                }

                // 2. 检测充电状态（只触发一次；边缘吸附时只冒泡不打断）
                val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val plugged = intent?.getIntExtra("plugged", -1) ?: -1
                val isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                                 plugged == BatteryManager.BATTERY_PLUGGED_USB
                if (isCharging && !lastChargingState) {
                    lastChargingState = true
                    noteState("error", "刚插上电源充电")
                } else if (!isCharging) {
                    lastChargingState = false
                }

                // 2.5 屏幕亮灭变化也算用户动作
                val screenOnNow = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
                if (screenOnNow != lastScreenOn) {
                    lastScreenOn = screenOnNow
                    refreshUserAction()
                }

                // 3. 检测前台 App
                val currentApp = getForegroundApp()
                if (currentApp != null) {
                    if (currentApp != lastForegroundApp) {
                        // 离开旧 App 时清理
                        val oldApp = lastForegroundApp
                        lastForegroundApp = currentApp
                        refreshUserAction()
                        handleAppSwitch(currentApp, oldApp)
                    } else if (currentApp.contains("termux")) {
                        // 持续在 Termux 中：保持 typing 动画、静默（不刷气泡，避免打扰）
                        if (!isMiniPeek && lastOnceState.isEmpty()) {
                            pushState("typing", "")
                        }
                    }
                } else {
                    // 无法检测前台 App 时，清除记录
                    if (lastForegroundApp.isNotEmpty()) {
                        lastForegroundApp = ""
                        refreshUserAction()
                        if (!isMiniPeek) {
                            switchAnim("idle")
                        }
                    }
                }

                // 3.5 同一个 App 超过 6 分钟，再弹新气泡（不重复讲一样的状态；边缘吸附待机时不打断）
                if (currentApp != null && currentApp == lastForegroundApp && !currentApp.contains("termux") && !isMiniPeek) {
                    val now = System.currentTimeMillis()
                    val info = appInfo(currentApp)
                    if (info != null && now - lastBubbleInAppAt > 6 * 60 * 1000L && !aiGenerating) {
                        lastBubbleInAppAt = now
                        lastBubbleShownAt = 0L  // 允许 noteState 触发（绕过 10s 保护）
                        noteState(info.first, info.second)
                    }
                }

                // 3.6 实时看懂你在干嘛：App 内切窗口 / 切歌（节流，见函数内部）
                maybeReactToWindowChange()
                maybeReactToTrack()

                // 3.7 30 分钟没有任何动作 → 自己碎碎念一句
                maybeMurmur()

                // 4. 边缘保持（最低优先级）：不主动吸附，只在异常状态下纠正
                //    真正的吸附只发生在用户拖动松手时（ACTION_UP → checkEdgePosition）
                if (isMiniPeek && !inEdgeZone()) {
                    isMiniPeek = false
                    switchAnim("idle")
                }

                // 5. Supabase 状态同步 (仅在没有本地活跃状态时应用；只切动画不打扰气泡)
                supabase.fetchPetState { state ->
                    if (state != null && lastOnceState.isEmpty() && !lastForegroundApp.contains("termux")) {
                        switchAnim(if (isMiniPeek) "peek" else state)
                    }
                }

                handler.postDelayed(this, 2000)
            }
        }, 1000)
    }

    // === 截屏检测 ===
    private var screenshotObserver: ContentObserver? = null
    private var lastShotId: Long = 0

    private fun registerScreenshotObserver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                checkNewScreenshot()
            }
        }
        screenshotObserver = observer
        contentResolver.registerContentObserver(collection, true, observer)
    }

    private fun checkNewScreenshot() {
        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
            // 截图通常以 Screenshot/截屏 命名，取其所在目录或文件名的图片
            val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            contentResolver.query(collection, projection, null, null, sort)?.use { c ->
                if (!c.moveToFirst()) return
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val added = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                if (id != lastShotId) {
                    lastShotId = id
                    val nowSec = System.currentTimeMillis() / 1000
                    if (nowSec - added <= 5) {
                        // 截屏：只冒气泡，不切换螃蟹状态/动画
                        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.bubble('📸 截到我了!')", null)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // === App 检测 ===
    /** 设备上所有已安装 App 的 包名→显示名 缓存（启动时枚举一次，未知 App 也能识别） */
    private var appLabels: Map<String, String> = emptyMap()

    private fun loadAppLabels() {
        appLabels = try {
            val pm = packageManager
            pm.getInstalledApplications(0)
                .associate { it.packageName to (pm.getApplicationLabel(it)?.toString() ?: "") }
                .filterValues { it.isNotBlank() }
        } catch (_: Exception) { emptyMap() }
    }

    /** 取 App 的显示名（如「哔哩哔哩」「王者荣耀」），拿不到或等于包名则 null。 */
    private fun appLabel(pkg: String): String? {
        val label = appLabels[pkg] ?: return null
        val clean = label.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank() || clean == pkg) return null
        return if (clean.length > 12) clean.take(12) + "…" else clean
    }

    /** 是否为系统预装 App（后台组件/系统界面 → 安静待机，不弹气泡）。 */
    private fun isSystemApp(pkg: String): Boolean = try {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: Exception) { true }   // 拿不到信息的按系统处理，静默

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
        // 1. 首选: UsageStatsManager（需要"使用情况访问"权限）
        if (hasUsageStatsPermission()) {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val window = 300_000L  // 5 分钟窗口

            // queryEvents 遍历 MOVE_TO_FOREGROUND 事件,取最后一条(最准确)
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
        }

        // 2. 兜底: 无障碍服务实时拿到的窗口包名（更及时，但需要用户开启无障碍）
        val accPkg = CloudPetAccessibilityService.windowPackage
        if (accPkg != null && accPkg != packageName && accPkg.isNotBlank()) return accPkg

        return null
    }

    private fun handleAppSwitch(pkg: String, oldPkg: String) {
        // 切 App 时强制重置，保证每次进 App 都弹新气泡
        aiGenerating = false
        lastBubbleText = null
        lastNoteAt = 0L
        lastBubbleShownAt = 0L
        lastBubbleInAppAt = System.currentTimeMillis()  // 记录进 App 时间，开始计时 6 分钟
        generationId++  // 递增世代号，旧 AI 线程的回调会被忽略
        // 注意：不清 JS 气泡队列 —— 旧 App 的气泡必须播放完全，新气泡自动排队等它播完

        if (pkg.contains("termux")) {
            isMiniPeek = false  // 进入 Termux 时退出边缘模式
            quickBubble("⌨️")
            noteState("typing", "在 Termux 里敲代码", fromAppSwitch = true)
            return
        }
        val info = appInfo(pkg)
        if (info != null) {
            // 结合 App 真实显示名，让气泡更有针对性（如「微信(聊天)」「哔哩哔哩(刷视频)」）
            val label = appLabel(pkg)
            val activity = if (label != null && !label.contains(info.second.take(3))) {
                "$label（${info.second}）"
            } else info.second
            // 先秒回一条即时气泡（不等 AI），AI 的独特文案随后排队跟上
            quickBubble(quickEmojiFor(info.first))
            // 每个 App 进来自动弹 2 条气泡：第一句回应当前场景，第二句随性补充（队列顺序播放）
            noteState(info.first, activity, lines = 2, fromAppSwitch = true)
            return
        }
        if (currentAppIsLauncher(pkg)) {
            // 回到桌面：安静待机，不打岔（静默清气泡）
            if (isMiniPeek) switchAnim("peek")
            else switchAnim("idle")
        } else {
            // 未知 App（不在内置分类表里）：用 PackageManager 拿真实应用名，
            // 保证设备上每个 App 打开都有自己独特的气泡
            val label = appLabel(pkg)
            if (label != null && !isSystemApp(pkg)) {
                quickBubble("🤔")
                noteState("thinking", "用「$label」", lines = 2, fromAppSwitch = true)
            } else {
                // 系统组件/后台服务 → 安静待机，不打扰
                if (isMiniPeek) switchAnim("peek")
                else switchAnim("idle")
            }
        }
    }

    /** 进 App 时的秒回气泡：1.5 秒短反馈，不等 AI 生成。 */
    private fun quickBubble(text: String) {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.bubble(${jsStr(text)}, 1500)", null)
    }

    /** 动画名 → 秒回 emoji。 */
    private fun quickEmojiFor(animation: String): String = when (animation) {
        "typing" -> "⌨️"
        "reading" -> "📖"
        "debugger" -> "🔍"
        "bubble" -> "💬"
        "groove" -> "🎵"
        "carrying" -> "🛒"
        "happy" -> "😊"
        "juggling" -> "🎮"
        "conducting" -> "📺"
        "building" -> "🔧"
        "thinking" -> "🤔"
        "error" -> "⚡"
        else -> "☁️"
    }

    /** 把前台包名映射成 (动画, 动作描述)；未知/桌面/Termux 返回 null。 */
    private fun appInfo(pkg: String): Pair<String, String>? {
        // ===== 小米 / MIUI 系统 App =====
        if (pkg.contains("miui") || pkg.contains("xiaomi") || pkg.contains("milink")) {
            return when {
                pkg.contains("miui.home") || pkg.contains("miui.launcher") || pkg.contains("android.launcher") -> null  // 桌面
                pkg.contains("miui.gallery") || pkg.contains("gallery") || pkg.contains("miservice.gallery") -> "reading" to "看照片"
                pkg.contains("miui.camera") || pkg.contains("camera") || pkg.contains("android.camera") -> "reading" to "拍照"
                pkg.contains("miui.notes") || pkg.contains("notes") || pkg.contains("keep") -> "reading" to "记笔记"
                pkg.contains("miui.video") || pkg.contains("miui.player") -> "conducting" to "看视频"
                pkg.contains("miui.weather") -> "reading" to "看天气"
                pkg.contains("miui.calculator") -> "debugger" to "算东西"
                pkg.contains("miui.clock") -> "reading" to "看时间"
                pkg.contains("miui.compass") -> "debugger" to "看指南针"
                pkg.contains("miui.screenrecorder") -> "bubble" to "录屏"
                pkg.contains("miui.securitycenter") || pkg.contains("miui.security") || pkg.contains("securitycenter") -> "debugger" to "在安全中心"
                pkg.contains("miui.settings") || pkg.contains("settings") -> "debugger" to "在系统设置"
                pkg.contains("miui.fileexplorer") || pkg.contains("fileexplorer") || pkg.contains("filemanager") -> "debugger" to "在文件管理"
                pkg.contains("miui.cleanmaster") || pkg.contains("cleaner") -> "debugger" to "清理垃圾"
                pkg.contains("miui.bugreport") -> "debugger" to "提交反馈"
                pkg.contains("miui.screenshot") -> "bubble" to "截屏"
                pkg.contains("miui.phone") || pkg.contains("miui.incallui") -> "bubble" to "打电话"
                pkg.contains("miui.contacts") || pkg.contains("contacts") -> "bubble" to "看通讯录"
                pkg.contains("miui.sms") || pkg.contains("miui.mms") || pkg.contains("messaging") -> "bubble" to "看短信"
                pkg.contains("miui.market") || pkg.contains("market") -> "building" to "逛应用商店"
                pkg.contains("miui.theme") || pkg.contains("theme") -> "happy" to "换主题"
                pkg.contains("miui.wallpaper") -> "happy" to "选壁纸"
                pkg.contains("miui.notes") || pkg.contains("miui.notepad") -> "reading" to "记笔记"
                pkg.contains("miui.deskclock") -> "reading" to "看闹钟"
                pkg.contains("miui.analytics") || pkg.contains("analytics") -> "debugger" to "后台运行"
                pkg.contains("miui.powerkeeper") || pkg.contains("powerkeeper") -> "debugger" to "省电管理"
                pkg.contains("miui.systemui") || pkg.contains("systemui") -> "debugger" to "系统界面"
                pkg.contains("miui.packageinstaller") || pkg.contains("packageinstaller") -> "building" to "安装应用"
                pkg.contains("miui.print") -> "debugger" to "打印"
                pkg.contains("miui.backup") -> "debugger" to "备份数据"
                pkg.contains("miui.compass") -> "debugger" to "指南针"
                else -> "debugger" to "在 MIUI 系统应用"
            }
        }

        // ===== Google 全家桶 =====
        if (pkg.contains("com.google.") || pkg.contains("google")) {
            return when {
                pkg.contains("chrome") -> "debugger" to "用浏览器"
                pkg.contains("youtube") || pkg.contains("youtubekids") || pkg.contains("youtubemusic") -> "conducting" to "刷视频"
                pkg.contains("gmail") || pkg.contains("android.gm") -> "bubble" to "看邮件"
                pkg.contains("maps") || pkg.contains("navigation") -> "debugger" to "在导航/地图"
                pkg.contains("playstore") || pkg.contains("play.shop") || pkg.contains("vending") -> "building" to "逛应用商店"
                pkg.contains("google.photos") || pkg.contains("photos") -> "reading" to "看照片"
                pkg.contains("google.calendar") || pkg.contains("calendar") -> "reading" to "看日历"
                pkg.contains("google.keep") || pkg.contains("keep") -> "reading" to "记笔记"
                pkg.contains("google.docs") || pkg.contains("docs") || pkg.contains("documents") -> "reading" to "编辑文档"
                pkg.contains("google.sheets") || pkg.contains("sheets") -> "reading" to "编辑表格"
                pkg.contains("google.slides") || pkg.contains("slides") -> "reading" to "做幻灯片"
                pkg.contains("google.drive") || pkg.contains("drive") -> "carrying" to "用云盘"
                pkg.contains("google.translate") || pkg.contains("translate") -> "debugger" to "翻译"
                pkg.contains("google.assistant") || pkg.contains("assistant") || pkg.contains("velvet") -> "bubble" to "用语音助手"
                pkg.contains("google.meet") || pkg.contains("duo") -> "bubble" to "视频通话"
                pkg.contains("google.news") || pkg.contains("news") -> "reading" to "看新闻"
                pkg.contains("google.clock") -> "reading" to "看时间"
                pkg.contains("google.calculator") -> "debugger" to "算东西"
                pkg.contains("google.android.gms") || pkg.contains("gms") || pkg.contains("googleplayservices") -> null  // 后台服务不重要
                pkg.contains("google.android.gsf") || pkg.contains("gsf") -> null
                else -> "debugger" to "用 Google 应用"
            }
        }

        // ===== 聊天 / 社交 =====
        if (pkg.contains("wechat") || pkg.contains("tencent.mm") || pkg.contains("tencent.mobileqq") || pkg.contains("telegram") || pkg.contains("discord") || pkg.contains("whatsapp") || pkg.contains("signal") || pkg.contains("line") || pkg.contains("slack")) {
            return "bubble" to "聊天"
        }

        // 社交平台
        if (pkg.contains("xingin.xhs") || pkg.contains("xiaohongshu") || pkg.contains("rednote")) {
            return "reading" to "刷小红书"
        }
        if (pkg.contains("weibo") || pkg.contains("zhihu") || pkg.contains("douban") || pkg.contains("tieba") || pkg.contains("reddit") || pkg.contains("twitter") || pkg.contains("x.com") || pkg.contains("x_") || pkg.contains("jike") || pkg.contains("soul") || pkg.contains("nichi")) {
            return "reading" to "刷社交平台"
        }

        // ===== 视频 / 直播 / 短视频 =====
        if (pkg.contains("douyin") || pkg.contains("tiktok") || pkg.contains("kuaishou") || pkg.contains("bilibili") || pkg.contains("youtube") || pkg.contains("twitch") || pkg.contains("youku") || pkg.contains("tencent.video") || pkg.contains("iqiyi") || pkg.contains("mgtv") || pkg.contains("sohu.video") || pkg.contains("le.tv") || pkg.contains("acfun") || pkg.contains("huya") || pkg.contains("douyu") || pkg.contains("kugou.live") || pkg.contains("inlive")) {
            return "conducting" to "刷视频"
        }

        // ===== 音乐 / 音频 =====
        if (pkg.contains("cloudmusic") || pkg.contains("netease.music") || pkg.contains("spotify") || pkg.contains("qqmusic") || pkg.contains("kugou") || pkg.contains("kuwo") || pkg.contains("xiami") || pkg.contains("migu") || pkg.contains("pandora") || pkg.contains("soundcloud") || pkg.contains("shazam") || pkg.contains("podcast") || pkg.contains("ximalaya") || pkg.contains("qingting") || pkg.contains("liyu")) {
            return "groove" to "听音乐/播客"
        }

        // ===== 购物 / 支付 =====
        if (pkg.contains("taobao") || pkg.contains("jingdong") || pkg.contains("pinduoduo") || pkg.contains("smzdm") || pkg.contains("alipay") || pkg.contains("xianyu") || pkg.contains("vipshop") || pkg.contains("suning") || pkg.contains("amazon") || pkg.contains("ebay") || pkg.contains("1688") || pkg.contains("alibaba") || pkg.contains("dd") || pkg.contains("walmart") || pkg.contains("target") || pkg.contains("kaola") || pkg.contains("yanxuan") || pkg.contains("weipinhui")) {
            return "carrying" to "逛购物 App"
        }

        // ===== 外卖 / 生活服务 =====
        if (pkg.contains("meituan") || pkg.contains("ele.me") || pkg.contains("xingtu") || pkg.contains("dianping") || pkg.contains("didi") || pkg.contains("ctrip") || pkg.contains("qunar") || pkg.contains("fliggy") || pkg.contains("trip") || pkg.contains("airbnb") || pkg.contains("uber") || pkg.contains("lyft") || pkg.contains("12306") || pkg.contains("zhixing") || pkg.contains("mobike") || pkg.contains("hellobike") || pkg.contains("qingju")) {
            return "happy" to "点外卖/出行"
        }

        // ===== 游戏 =====
        if (pkg.contains("mihoyo") || pkg.contains("genshin") || pkg.contains("honkai") || pkg.contains("starrail") || pkg.contains("zzz") || pkg.contains("tencent.game") || pkg.contains("game") || pkg.contains("miHoYo") || pkg.contains("nintendo") || pkg.contains("epic") || pkg.contains("steam") || pkg.contains("blockman") || pkg.contains("minecraft") || pkg.contains("pubg") || pkg.contains("hearthstone") || pkg.contains("honor.of.kings") || pkg.contains("kingofglory") || pkg.contains("peacekeeper") || pkg.contains("gobilin") || pkg.contains("lolm") || pkg.contains("leagueoflegends") || pkg.contains("cf") || pkg.contains("crossfire") || pkg.contains("dnf") || pkg.contains("onmyoji") || pkg.contains("arknights") || pkg.contains("fgo") || pkg.contains("azurlane") || pkg.contains("girlsfrontline") || pkg.contains("pgr") || pkg.contains("wuthering") || pkg.contains("battlegrounds") || pkg.contains("mobilelegends") || pkg.contains("clash") || pkg.contains("cod") || pkg.contains("callofduty") || pkg.contains("fifa") || pkg.contains("nba") || pkg.contains("racing") || pkg.contains("pokemon") || pkg.contains("runeworld") || pkg.contains("netease.game") || pkg.contains("puzzle") || pkg.contains("card") || pkg.contains("chess")) {
            return "juggling" to "玩游戏"
        }

        // ===== 办公 / 笔记 =====
        if (pkg.contains("notepad") || pkg.contains("onenote") || pkg.contains("notion") || pkg.contains("wps") || pkg.contains("microsoft.word") || pkg.contains("word") || pkg.contains("microsoft.excel") || pkg.contains("excel") || pkg.contains("microsoft.powerpoint") || pkg.contains("powerpoint") || pkg.contains("microsoft.office") || pkg.contains("office") || pkg.contains("outlook") || pkg.contains("microsoft.teams") || pkg.contains("teams") || pkg.contains("evernote") || pkg.contains("youdao") || pkg.contains("xmind") || pkg.contains("pdf") || pkg.contains("adobe") || pkg.contains("foxit") || pkg.contains("trello") || pkg.contains("asana") || pkg.contains("jira") || pkg.contains("confluence") || pkg.contains("dingtalk") || pkg.contains("feishu") || pkg.contains("lark") || pkg.contains("wecom") || pkg.contains("work") || pkg.contains("qiyeweixin") || pkg.contains("tim") || pkg.contains("svn") || pkg.contains("git") || pkg.contains("codes") || pkg.contains("studio") || pkg.contains("vscode") || pkg.contains("code") || pkg.contains("intellij") || pkg.contains("androidstudio")) {
            return "reading" to "办公/写代码"
        }

        // ===== 浏览器 =====
        if (pkg.contains("browser") || pkg.contains("chrome") || pkg.contains("firefox") || pkg.contains("opera") || pkg.contains("safari") || pkg.contains("edge") || pkg.contains("emmx") || pkg.contains("baidu.browser") || pkg.contains("qqbrowser") || pkg.contains("uc") || pkg.contains("sogou.browser") || pkg.contains("via") || pkg.contains("kiwi") || pkg.contains("mint") || pkg.contains("brave") || pkg.contains("duckduckgo") || pkg.contains("yandex.browser") || pkg.contains("webview") || pkg.contains("chromium")) {
            return "debugger" to "用浏览器"
        }

        // ===== 地图 / 导航 =====
        if (pkg.contains("amap") || pkg.contains("gaode") || pkg.contains("baidu.map") || pkg.contains("map") || pkg.contains("navigation") || pkg.contains("navi") || pkg.contains("gps") || pkg.contains("here") || pkg.contains("waze") || pkg.contains("citymapper") || pkg.contains("transit") || pkg.contains("bus") || pkg.contains("metro") || pkg.contains("subway") || pkg.contains("railway") || pkg.contains("train")) {
            return "debugger" to "在导航/地图"
        }

        // ===== 银行 / 金融 =====
        if (pkg.contains("bank") || pkg.contains("icbc") || pkg.contains("ccb") || pkg.contains("abc") || pkg.contains("boc") || pkg.contains("bankcomm") || pkg.contains("cmb") || pkg.contains("citic") || pkg.contains("cib") || pkg.contains("cmsb") || pkg.contains("spdb") || pkg.contains("pingan.bank") || pkg.contains("alipay") || pkg.contains("wechat.pay") || pkg.contains("wallet") || pkg.contains("pay") || pkg.contains("finance") || pkg.contains("stock") || pkg.contains("fund") || pkg.contains("ant") || pkg.contains("mybank") || pkg.contains("chinapay") || pkg.contains("unionpay") || pkg.contains("yunshanfu") || pkg.contains("wealth") || pkg.contains("invest") || pkg.contains("trading") || pkg.contains("huobi") || pkg.contains("binance") || pkg.contains("okx") || pkg.contains("bitcoin") || pkg.contains("crypto") || pkg.contains("blockchain") || pkg.contains("quant") || pkg.contains("futu") || pkg.contains("eastmoney") || pkg.contains("tonghuashun") || pkg.contains("xueqiu") || pkg.contains("daydayfund")) {
            return "carrying" to "在理财/支付"
        }

        // ===== 健康 / 运动 =====
        if (pkg.contains("health") || pkg.contains("fitness") || pkg.contains("sport") || pkg.contains("running") || pkg.contains("walk") || pkg.contains("step") || pkg.contains("pedometer") || pkg.contains("mi.fit") || pkg.contains("zepp") || pkg.contains("huami") || pkg.contains("wear") || pkg.contains("watch") || pkg.contains("samsung.health") || pkg.contains("google.fit") || pkg.contains("strava") || pkg.contains("nike") || pkg.contains("keep") || pkg.contains("yoga") || pkg.contains("meditation") || pkg.contains("sleep") || pkg.contains("calories") || pkg.contains("diet") || pkg.contains("miband") || pkg.contains("xiaomi.wear") || pkg.contains("xiaomi.fit")) {
            return "happy" to "看健康/运动数据"
        }

        // ===== 教育 / 学习 =====
        if (pkg.contains("education") || pkg.contains("class") || pkg.contains("course") || pkg.contains("learn") || pkg.contains("study") || pkg.contains("school") || pkg.contains("university") || pkg.contains("college") || pkg.contains("exam") || pkg.contains("test") || pkg.contains("quiz") || pkg.contains("homework") || pkg.contains("dictionary") || pkg.contains("baicizhan") || pkg.contains("shanbay") || pkg.contains("hujiang") || pkg.contains("xueersi") || pkg.contains("zhihuishu") || pkg.contains("chaoxing") || pkg.contains("xuetangx") || pkg.contains("coursera") || pkg.contains("udemy") || pkg.contains("duolingo") || pkg.contains("khan") || pkg.contains("wikipedia") || pkg.contains("baike") || pkg.contains("reader") || pkg.contains("ebook") || pkg.contains("book") || pkg.contains("novel") || pkg.contains("kindle") || pkg.contains("weixin.read") || pkg.contains("ireader") || pkg.contains("zhangyue") || pkg.contains("kugou.book") || pkg.contains("fanqie") || pkg.contains("qidian") || pkg.contains("qq.read") || pkg.contains("mangabook") || pkg.contains("comic")) {
            return "reading" to "学习/看书"
        }

        // ===== 摄影 / 修图 =====
        if (pkg.contains("camera") || pkg.contains("gallery") || pkg.contains("album") || pkg.contains("photo") || pkg.contains("image") || pkg.contains("edit") || pkg.contains("filter") || pkg.contains("beauty") || pkg.contains("meitu") || pkg.contains("picsart") || pkg.contains("snapseed") || pkg.contains("lightroom") || pkg.contains("photoshop") || pkg.contains("canva") || pkg.contains("afterlight") || pkg.contains("vscocam") || pkg.contains("retouch") || pkg.contains("collage") || pkg.contains("photography") || pkg.contains("cute") || pkg.contains("pixel")) {
            return "reading" to "拍照/修图"
        }

        // ===== 工具 / 文件管理 =====
        if (pkg.contains("mt") || pkg.contains("manager") || pkg.contains("explorer") || pkg.contains("file") || pkg.contains("download") || pkg.contains("downloads") || pkg.contains("cloud") || pkg.contains("disk") || pkg.contains("drive") || pkg.contains("storage") || pkg.contains("backup") || pkg.contains("recovery") || pkg.contains("tool") || pkg.contains("utility") || pkg.contains("root") || pkg.contains("magisk") || pkg.contains("lsposed") || pkg.contains("edxposed") || pkg.contains("xposed") || pkg.contains("twrp") || pkg.contains("busybox") || pkg.contains("terminal") || pkg.contains("termux") || pkg.contains("ssh") || pkg.contains("vpn") || pkg.contains("proxy") || pkg.contains("clash") || pkg.contains("v2ray") || pkg.contains("ssr") || pkg.contains("tunnel") || pkg.contains("wireguard") || pkg.contains("wf") || pkg.contains("wifi") || pkg.contains("speedtest") || pkg.contains("net") || pkg.contains("network") || pkg.contains("dns") || pkg.contains("ping") || pkg.contains("http") || pkg.contains("server") || pkg.contains("ftp") || pkg.contains("sftp") || pkg.contains("smb") || pkg.contains("rclone") || pkg.contains("syncthing") || pkg.contains("zip") || pkg.contains("rar") || pkg.contains("compressor") || pkg.contains("convert") || pkg.contains("qr") || pkg.contains("barcode") || pkg.contains("nfc") || pkg.contains("bluetooth") || pkg.contains("recorder") || pkg.contains("remote") || pkg.contains("rdp") || pkg.contains("vnc") || pkg.contains("teamviewer") || pkg.contains("anydesk") || pkg.contains("scanner") || pkg.contains("ocr") || pkg.contains("calculator") || pkg.contains("timer") || pkg.contains("stopwatch") || pkg.contains("alarm") || pkg.contains("clock") || pkg.contains("calendar") || pkg.contains("weather") || pkg.contains("wallpaper") || pkg.contains("widget") || pkg.contains("launcher") || pkg.contains("icon") || pkg.contains("theme") || pkg.contains("font") || pkg.contains("keyboard") || pkg.contains("inputmethod") || pkg.contains("ime") || pkg.contains("sogou.input") || pkg.contains("baidu.input") || pkg.contains("qq.input") || pkg.contains("gboard") || pkg.contains("swype") || pkg.contains("touchpal")) {
            return "debugger" to "在用工具类应用"
        }

        // ===== 音乐制作 / 创作 =====
        if (pkg.contains("music") || pkg.contains("audio") || pkg.contains("recording") || pkg.contains("microphone") || pkg.contains("mix") || pkg.contains("dj") || pkg.contains("beat") || pkg.contains("instrument") || pkg.contains("piano") || pkg.contains("guitar") || pkg.contains("drum") || pkg.contains("suno") || pkg.contains("song") || pkg.contains("midi") || pkg.contains("lyrics") || pkg.contains("karaoke") || pkg.contains("sing") || pkg.contains("voice") || pkg.contains("speech") || pkg.contains("tts") || pkg.contains("asr") || pkg.contains("transcribe")) {
            return "groove" to "在创作音乐/音频"
        }

        // ===== AI / 大模型工具 =====
        if (pkg.contains("ai") || pkg.contains("llm") || pkg.contains("chatgpt") || pkg.contains("gpt") || pkg.contains("claude") || pkg.contains("deepseek") || pkg.contains("copilot") || pkg.contains("gemini") || pkg.contains("bard") || pkg.contains("openai") || pkg.contains("hugging") || pkg.contains("huggingface") || pkg.contains("stability") || pkg.contains("midjourney") || pkg.contains("dall") || pkg.contains("diffusion") || pkg.contains("stable") || pkg.contains("novel") || pkg.contains("character") || pkg.contains("perplexity") || pkg.contains("kimi") || pkg.contains("doubao") || pkg.contains("tongyi") || pkg.contains("wenxin") || pkg.contains("qianwen") || pkg.contains("baichuan") || pkg.contains("lingyi") || pkg.contains("zhipu") || pkg.contains("xinghuo") || pkg.contains("spark") || pkg.contains("minimax") || pkg.contains("metaso") || pkg.contains("yuanbao")) {
            return "debugger" to "在用 AI 工具"
        }

        // ===== AOSP 原生系统应用（用户会主动打开，给气泡；真正的后台组件见下一块）=====
        if (pkg.startsWith("android.") || pkg.startsWith("com.android.")) {
            return when {
                pkg.contains("settings") -> "debugger" to "在系统设置"
                pkg.contains("documentsui") || pkg.contains("externalstorage") -> "debugger" to "在文件管理"
                pkg.contains("dialer") || pkg.contains("incallui") || pkg.contains("com.android.phone") -> "bubble" to "打电话"
                pkg.contains("mms") || pkg.contains("messaging") -> "bubble" to "看短信"
                pkg.contains("contacts") -> "bubble" to "看通讯录"
                pkg.contains("gallery") || pkg.contains("photos") -> "reading" to "看照片"
                pkg.contains("camera") -> "reading" to "拍照"
                pkg.contains("calculator") -> "debugger" to "算东西"
                pkg.contains("deskclock") || pkg.contains("clock") -> "reading" to "看时间"
                pkg.contains("calendar") -> "reading" to "看日历"
                pkg.contains("email") || pkg.contains("exchange") -> "bubble" to "看邮件"
                pkg.contains("vending") || pkg.contains("playstore") -> "building" to "逛应用商店"
                pkg.contains("chrome") -> "debugger" to "用浏览器"
                else -> null
            }
        }

        // ===== 系统组件 / 后台服务 ===== (忽略，不弹气泡)
        if (pkg.startsWith("android.") || pkg.startsWith("com.android.") || pkg.contains("android.system") || pkg.contains("android.auto") || pkg.contains("qualcomm") || pkg.contains("qti") || pkg.contains("snapdragon") || pkg.contains("samsung") || pkg.contains("oneplus") || pkg.contains("oppo") || pkg.contains("vivo") || pkg.contains("realme") || pkg.contains("huawei") || pkg.contains("honor") || pkg.contains("pixel") || pkg.contains("motorola") || pkg.contains("sony") || pkg.contains("lg") || pkg.contains("nokia") || pkg.contains("lenovo") || pkg.contains("asus") || pkg.contains("mediatek") || pkg.contains("mtk") || pkg.contains("wlan") || pkg.contains("bluetooth") || pkg.contains("nfc") || pkg.contains("radio") || pkg.contains("telephony") || pkg.contains("phone") || pkg.contains("incall") || pkg.contains("dialer") || pkg.contains("contacts") || pkg.contains("providers") || pkg.contains("print") || pkg.contains("com.android") || pkg.contains("android.ext") || pkg.contains("android.auto") || pkg.contains("fused") || pkg.contains("location") || pkg.contains("keychain") || pkg.contains("certificate") || pkg.contains("permission") || pkg.contains("safety") || pkg.contains("secure") || pkg.contains("root") || pkg.contains("update") || pkg.contains("otg") || pkg.contains("usb") || pkg.contains("com.miui") || pkg.contains("android.") || pkg.contains("com.google.android.gms") || pkg.contains("com.google.android.gsf") || pkg.contains("com.android.systemui") || pkg.contains("com.android.settings") || pkg.contains("com.android.providers") || pkg.contains("com.android.vending") || pkg.contains("com.android.chrome") || pkg.contains("com.android.phone") || pkg.contains("com.android.dialer") || pkg.contains("com.android.mms") || pkg.contains("com.android.contacts") || pkg.contains("com.android.documentsui") || pkg.contains("com.android.externalstorage") || pkg.contains("com.android.packageinstaller") || pkg.contains("com.android.permissioncontroller") || pkg.contains("com.android.onetimeinitializer") || pkg.contains("com.android.cellbroadcast") || pkg.contains("com.android.carrierconfig") || pkg.contains("com.android.carriersettings") || pkg.contains("com.android.emergency") || pkg.contains("com.android.managedprovisioning") || pkg.contains("com.android.networkstack") || pkg.contains("com.android.nfc") || pkg.contains("com.android.se") || pkg.contains("com.android.shell") || pkg.contains("com.android.statementservice") || pkg.contains("com.android.wallpaper") || pkg.contains("com.android.wifi") || pkg.contains("com.android.framework") || pkg.contains("com.android.ons") || pkg.contains("com.android.phyh") || pkg.contains("com.android.bips") || pkg.contains("com.android.soundrecorder") || pkg.contains("com.android.stk") || pkg.contains("com.android.wallpaper") || pkg.contains("com.android.calendar") || pkg.contains("com.android.deskclock") || pkg.contains("com.android.email") || pkg.contains("com.android.exchange") || pkg.contains("com.android.gallery") || pkg.contains("com.android.camera") || pkg.contains("com.android.calculator") || pkg.contains("com.android.quicksearchbox") || pkg.contains("com.android.inputdevices") || pkg.contains("com.android.inputmethod") || pkg.contains("com.android.keyguard") || pkg.contains("com.android.launcher") || pkg.contains("com.android.packageinstaller") || pkg.contains("com.android.phasebeacon") || pkg.contains("com.android.proxyhandler") || pkg.contains("com.android.server") || pkg.contains("com.android.smspush") || pkg.contains("com.android.traceur") || pkg.contains("com.android.voicemail") || pkg.contains("com.android.vpndialogs") || pkg.contains("com.android.certinstaller") || pkg.contains("com.android.dreams") || pkg.contains("com.android.htmlviewer") || pkg.contains("com.android.sharedstoragebackup") || pkg.contains("com.android.webview") || pkg.contains("com.qualcomm") || pkg.contains("com.qti") || pkg.contains("com.mediatek") || pkg.contains("com.svox") || pkg.contains("com.estrongs") || pkg.contains("com.xiaomi") || pkg.contains(":") || pkg.endsWith(".system") || pkg.contains("systemui") || pkg.contains("system") || pkg.contains("process") || pkg.contains("service") || pkg.contains("overlay") || pkg.contains("feedback") || pkg.contains("partner") || pkg.contains("config") || pkg.contains("embryo") || pkg.contains("cnss") || pkg.contains("dpm") || pkg.contains("dpmservice") || pkg.contains("hotword") || pkg.contains("ifaa") || pkg.contains("keystore") || pkg.contains("locale") || pkg.contains("logging") || pkg.contains("mgm") || pkg.contains("mms") || pkg.contains("monitor") || pkg.contains("msa") || pkg.contains("network") || pkg.contains("npe") || pkg.contains("nubia") || pkg.contains("oem") || pkg.contains("pco") || pkg.contains("platform") || pkg.contains("pps") || pkg.contains("preset") || pkg.contains("provision") || pkg.contains("safemode") || pkg.contains("samsung") || pkg.contains("sdp") || pkg.contains("secc") || pkg.contains("sensor") || pkg.contains("sim") || pkg.contains("soter") || pkg.contains("stk") || pkg.contains("storage") || pkg.contains("switch") || pkg.contains("telecom") || pkg.contains("theme") || pkg.contains("touch") || pkg.contains("trace") || pkg.contains("update") || pkg.contains("usb") || pkg.contains("uim") || pkg.contains("wfd") || pkg.contains("wifi") || pkg.contains("wlan") || pkg.contains("xms") || pkg.contains("yg")) {
            return null
        }

        // ===== 未知 App：根据包名猜一猜 =====
        val parts = pkg.split(".")
        val guessName = parts.lastOrNull()?.replace("_", " ")?.replace("-", " ")?.take(15)
        if (guessName != null && guessName.length >= 3) {
            return "thinking" to "在用「$guessName」"
        }

        return null  // 完全未知→走 handleAppSwitch 的安静待机
    }

    /** App 内窗口变化（同 app 里 聊天↔朋友圈 这种）：看懂你在干嘛就提一句，节流 12s。 */
    private fun maybeReactToWindowChange() {
        val pkg = lastForegroundApp
        if (pkg.isBlank() || pkg.contains("termux")) return
        val info = appInfo(pkg)
        if (info == null || currentAppIsLauncher(pkg)) return
        // 窗口签名 = 包名 + 窗口标题 + 屏幕文字指纹（前 100 字变化也算变化）
        val screen = CloudPetAccessibilityService.screenText ?: ""
        val screenFingerprint = screen.take(100)
        val sig = (CloudPetAccessibilityService.windowPackage ?: "") + "|" +
                (CloudPetAccessibilityService.windowTitle ?: "") + "|" + screenFingerprint
        if (sig == lastWindowSig) return
        lastWindowSig = sig
        refreshUserAction()   // 切窗口/滚屏 = 用户有动作，重置碎碎念计时
        // 节流 12s，且当前气泡还在显示时也不打断（noteState 内部有 lastBubbleShownAt 保护）
        if (System.currentTimeMillis() - lastWindowNoteAt < 12000 || aiGenerating) return
        lastWindowNoteAt = System.currentTimeMillis()
        noteState(info.first, info.second)
    }

    /** 正在播的音乐切歌了（不管你在不在音乐 App，都能“听到”）：提一句。 */
    private fun maybeReactToTrack() {
        val t = nowPlayingTrack() ?: return
        if (t == lastTrack) return
        lastTrack = t
        refreshUserAction()   // 切歌 = 用户有动作
        if (System.currentTimeMillis() - lastWindowNoteAt < 12000 || aiGenerating) return
        lastWindowNoteAt = System.currentTimeMillis()
        noteState("groove", "正在播放音乐")
    }

    /**
     * 状态变化时的一句话点评：
     * - fromAppSwitch=true（进新 App）：退出边缘模式，立刻切对应动画（静默），AI 生成 1~2 句气泡排队显示。
     * - 边缘吸附待机中（isMiniPeek）：只冒气泡、绝不切动画打断边缘待机。
     * - 其它情况：切动画 + 冒气泡。
     * 已有 AI 请求在途时丢弃新请求，避免刷屏；生成结果与最近说过的重复时静默。
     */
    private fun noteState(animation: String, activity: String, lines: Int = 1, fromAppSwitch: Boolean = false) {
        val now = System.currentTimeMillis()
        if (aiGenerating || now - lastNoteAt < 2000) return   // 有请求在途或刚才说过，先歇一下（2s 内不连发）
        // 同 App 内气泡还在显示（3.5s内）不打断，避免读到一半跳走
        // 切 App 时 handleAppSwitch 会清空 lastBubbleShownAt，所以不受此限制
        if (now - lastBubbleShownAt < 3500 && lastBubbleShownAt > 0 && !fromAppSwitch) return
        lastNoteAt = now
        aiGenerating = true
        val currentGen = generationId  // 记录当前世代，回调时检查是否过期

        if (fromAppSwitch) {
            isMiniPeek = false
            switchAnimOnce(animation)   // 退出边缘 + 切动画（不碰气泡）
        } else if (!isMiniPeek) {
            switchAnimOnce(animation)   // 边缘待机中：不切动画，只冒气泡
        }

        ai.generate(buildContext(activity), recentBubbles.toList(), lines) { result ->
            // 旧世代的回调：忽略，不覆盖当前气泡
            if (currentGen != generationId) return@generate
            aiGenerating = false
            var list = result ?: emptyList()
            if (list.isEmpty()) {
                list = listOf(fallbackFor(activity))   // AI 失败时的兜底文案
            } else {
                // 与最近说过的重复的句子直接丢掉，保证每次都是新话
                list = list.distinct().filter { it != lastBubbleText }
                if (list.isEmpty()) list = listOf(fallbackFor(activity))
            }
            lastBubbleText = list.last()   // 最近说的是最后一条
            list.forEach {
                recentBubbles.addLast(it)
                while (recentBubbles.size > 5) recentBubbles.removeFirst()
            }
            lastBubbleShownAt = System.currentTimeMillis()
            lastBubbleInAppAt = System.currentTimeMillis()
            // 多条文案按顺序入队：第一条播完才显示第二条
            val js = list.joinToString(",") { jsStr(it) }
            overlayView?.evaluateJavascript(
                "window.petEngine && window.petEngine.aiBubbleSeq([$js])", null)
        }
    }

    /** AI 生成失败/全重复时的兜底文案。 */
    private fun fallbackFor(activity: String): String = when {
        activity.contains("听音乐") -> "🎵 听歌中~"
        activity.contains("聊天") -> "💬 在聊天呢"
        activity.contains("刷视频") -> "📺 看视频中"
        activity.contains("浏览器") || activity.contains("浏览") -> "🌐 在浏览"
        activity.contains("游戏") || activity.contains("玩") -> "🎮 在玩游戏"
        activity.contains("购物") || activity.contains("买") -> "🛒 在购物"
        activity.contains("外卖") || activity.contains("点") -> "🍜 在点外卖"
        activity.contains("笔记") || activity.contains("办公") || activity.contains("写") -> "📝 在忙正事"
        activity.contains("导航") || activity.contains("地图") -> "🗺️ 在导航"
        activity.contains("设置") || activity.contains("文件") || activity.contains("工具") -> "🔧 在折腾"
        activity.contains("照片") || activity.contains("拍照") || activity.contains("修图") -> "📸 在看照片"
        activity.contains("学习") || activity.contains("看书") -> "📚 在学习"
        activity.contains("理财") || activity.contains("支付") -> "💰 在理财"
        activity.contains("健康") || activity.contains("运动") -> "🏃 在运动"
        activity.contains("AI") || activity.contains("工具") -> "🤖 在用 AI"
        activity.contains("记事本") || activity.contains("笔记") -> "📝 在记东西"
        else -> "☁️ 小克看着呢~"
    }

    /** 用户有动作了：刷新“最后动作时间”，重置 30 分钟碎碎念计时。 */
    private fun refreshUserAction() {
        lastUserActionAt = System.currentTimeMillis()
    }

    /** 30 分钟没有任何操作 → 自己碎碎念一句（AI 优先，失败用本地库）。改成 60 分钟一次，避免太频繁。 */
    private fun maybeMurmur() {
        if (aiGenerating) return
        val now = System.currentTimeMillis()
        if (now - lastUserActionAt < 60 * 60 * 1000L) return
        if (now - lastMurmurAt < 60 * 60 * 1000L) return   // 防连发
        if (now - lastBubbleShownAt < 3500 && lastBubbleShownAt > 0) return
        lastMurmurAt = now
        aiGenerating = true
        val currentGen = generationId
        val phase = timePhase()
        ai.generate(
            "主人已经一个多小时没有操作设备了，屏幕还亮着，现在是$phase。你现在可以随意碎碎念一句，自言自语，什么内容都可以，一句话，不超过15个字，可带1个emoji。",
            recentBubbles.toList(), 1
        ) { result ->
            if (currentGen != generationId) return@generate
            aiGenerating = false
            val line = result?.firstOrNull()
            if (line.isNullOrBlank()) {
                // AI 掉线/超时：本地随机碎碎念
                val fallback = murmurs.random()
                recentBubbles.addLast(fallback)
                while (recentBubbles.size > 5) recentBubbles.removeFirst()
                overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.aiBubble(${jsStr(fallback)})", null)
                return@generate
            }
            lastBubbleText = line
            recentBubbles.addLast(line)
            while (recentBubbles.size > 5) recentBubbles.removeFirst()
            lastBubbleShownAt = System.currentTimeMillis()
            overlayView?.evaluateJavascript(
                "window.petEngine && window.petEngine.aiBubble(${jsStr(line)})", null)
        }
    }

    /** 拼一段描述当前真实状态的提示词，让 AI“看到”你在干嘛。 */
    private fun buildContext(activity: String): String {
        val phase = timePhase()
        val screenOn = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
        val sb = StringBuilder("现在的真实状态：在$activity。现在是$phase，屏幕${if (screenOn) "亮着" else "熄屏"}。")
        val track = nowPlayingTrack()
        if (!track.isNullOrBlank()) sb.append("正在播放音乐：$track。")
        val screen = CloudPetAccessibilityService.screenText
        if (!screen.isNullOrBlank()) {
            val capped = if (screen.length > 400) screen.substring(0, 400) + "…" else screen
            sb.append("屏幕上我能看到的文字：$capped")
        } else {
            // 无障碍没开/暂无内容：明确告诉 AI 看不到，防止它瞎编屏幕内容
            sb.append("（我现在看不到屏幕上的文字，不要凭空想象屏幕内容）")
        }
        // ===== 设备状态全量采集（给 AI 当背景信息，人设已约束别老提这些细节）=====
        val dev = StringBuilder()
        try {
            val it = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (it != null) {
                val plugged = it.getIntExtra("plugged", -1)
                val lvl = it.getIntExtra("level", -1)
                val scale = it.getIntExtra("scale", 100)
                if (lvl >= 0 && scale > 0) {
                    val pct = lvl * 100 / scale
                    dev.append("电量${pct}%${if (plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB) "充电中" else ""}，")
                }
            }
        } catch (_: Exception) {}
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (am != null) {
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val ring = am.getStreamVolume(android.media.AudioManager.STREAM_RING)
                dev.append("媒体音量${cur}/$max，铃声音量${if (ring > 0) "开" else "静音"}，")
            }
        } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val nf = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nf != null) {
                    val filter = nf.currentInterruptionFilter
                    dev.append(
                        when (filter) {
                            NotificationManager.INTERRUPTION_FILTER_NONE -> "勿扰全静音，"
                            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "勿扰仅闹钟，"
                            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "勿扰模式，"
                            else -> "通知正常，"
                        }
                    )
                }
            }
        } catch (_: Exception) {}
        try {
            val airplane = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
            if (airplane == 1) dev.append("飞行模式，")
        } catch (_: Exception) {}
        try {
            val bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (bt != null) dev.append(if (bt.isEnabled) "蓝牙开，" else "蓝牙关，")
        } catch (_: Exception) {}
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm.activeNetwork
                if (net != null) {
                    val caps = cm.getNetworkCapabilities(net)
                    dev.append(
                        when {
                            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "连着WiFi，"
                            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "用着流量，"
                            else -> "有网络，"
                        }
                    )
                } else dev.append("没联网，")
            }
        } catch (_: Exception) {}
        try {
            val mi = android.app.ActivityManager.MemoryInfo()
            (getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(mi)
            if (mi.totalMem > 0) {
                val freePct = mi.availMem * 100 / mi.totalMem
                dev.append("内存剩${freePct}%，")
            }
        } catch (_: Exception) {}
        try {
            val st = android.os.StatFs(filesDir.path)
            val freeGb = st.availableBytes / (1024.0 * 1024 * 1024)
            dev.append("存储剩${String.format("%.1f", freeGb)}GB，")
        } catch (_: Exception) {}
        try {
            val brightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (brightness >= 0) dev.append("亮度$brightness，")
        } catch (_: Exception) {}
        if (dev.isNotEmpty()) {
            dev.setLength(dev.length - 1)  // 去掉末尾顿号
            sb.append("设备状态：$dev。")
        }
        return sb.toString()
    }

    private fun timePhase(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..8 -> "早上"
        in 9..11 -> "上午"
        12 -> "中午"
        in 13..17 -> "下午"
        in 18..20 -> "傍晚"
        in 21..23 -> "晚上"
        else -> "深夜"
    }

    /** 从系统媒体会话拿当前正在播放的歌（曲名 - 歌手），没有则 null。 */
    private fun nowPlayingTrack(): String? {
        return try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
            val sessions = msm.getActiveSessions(null)
            for (c in sessions) {
                val ps = c.playbackState ?: continue
                if (ps.state == PlaybackState.STATE_PLAYING) {
                    val md = c.metadata ?: continue
                    val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                    if (!title.isNullOrBlank()) {
                        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        return if (artist.isNullOrBlank()) title else "$title - $artist"
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /** 把文案安全地转成 JS 字符串字面量，供 evaluateJavascript 使用。 */
    private fun jsStr(s: String): String {
        val e = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$e\""
    }

    /** 判断该包名是否为桌面启动器 */
    private fun currentAppIsLauncher(pkg: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            val resolve = packageManager.resolveActivity(intent, 0)
            resolve?.activityInfo?.packageName == pkg
        } catch (_: Exception) { false }
    }

    /** 当前窗口位置是否落在屏幕边缘吸附区（阈值 20dp，比之前 30dp 更“边缘”）。 */
    private fun inEdgeZone(): Boolean {
        val x = params?.x ?: 0
        val petW = dpToPx(PET_SIZE_DP)
        val edgeThreshold = dpToPx(20)
        return x < edgeThreshold || x > screenWidth - petW - edgeThreshold
    }

    /**
     * 检查并吸附边缘。只应该在用户拖动松手（ACTION_UP）后调用：
     * 只有用户主动把桌宠拖到屏幕边缘，才展示被吸附的 mini 动图并在边缘待机。
     */
    private fun checkEdgePosition() {
        if (!inEdgeZone()) {
            // 拖到了中间区域：退出边缘模式，回到全尺寸待机
            if (isMiniPeek) {
                isMiniPeek = false
                switchAnim("idle")
            }
            return
        }
        val x = params?.x ?: 0
        val petW = dpToPx(PET_SIZE_DP)
        val atLeftEdge = x < dpToPx(20)
        // 通知 JS 当前贴靠哪边（用于镜像翻转 + mini 缩放）
        val side = if (atLeftEdge) "left" else "right"
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.setEdge('$side')", null)
        if (!isMiniPeek) {
            isMiniPeek = true
            // 吸附到边缘
            params?.x = if (atLeftEdge) 0 else screenWidth - petW
            windowManager?.updateViewLayout(overlayView, params)
            switchAnim("peek")   // 边缘用吸附动图待机（不打断气泡）
        }
    }

    /** 静默切一次动画（once 态），3 秒后自动恢复待机；期间别的状态接管则不恢复。 */
    private fun switchAnimOnce(state: String) {
        if (state == lastOnceState) return
        lastOnceState = state
        val now = System.currentTimeMillis()
        lastOnceStartAt = now
        lastAnyPushAt = now
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.switchAnim('$state')", null)
        handler.postDelayed({
            if (state != lastOnceState) return@postDelayed  // 已被更新的 once 接管
            lastOnceState = ""
            // 期间有别的状态接管（如打字/切歌/新 once）：不再恢复，避免覆盖
            if (lastAnyPushAt <= lastOnceStartAt) {
                switchAnim(if (isMiniPeek) "peek" else "idle")
            }
        }, 8000)
    }

    private fun pushState(state: String, text: String) {
        lastAnyPushAt = System.currentTimeMillis()
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.setState('$state', '$text')", null
        )
    }

    /** 只切动画不碰气泡（恢复待机/边缘保持用）。 */
    private fun switchAnim(state: String) {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.switchAnim('$state')", null)
    }

    // === 手势处理 ===
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var touchInHitArea = false

    /**
     * 点击交互范围：只在螃蟹本体附近才响应点击（点窗口透明角落没反应）。
     * 拖动不受限制（拖窗口是核心操作，全窗口可拖）。
     * 全尺寸态：中心 60%×60%；边缘 mini 态：螃蟹所在那一侧 55% 宽 × 60% 高。
     */
    private fun isInHitArea(x: Float, y: Float): Boolean {
        val w = overlayView?.width ?: dpToPx(PET_SIZE_DP)
        val h = overlayView?.height ?: dpToPx(PET_HEIGHT_DP)
        if (w <= 0 || h <= 0) return true
        val left: Int
        val right: Int
        if (isMiniPeek) {
            // mini 态：螃蟹在窗口的贴边一侧（靠哪边由 JS 的 edge 决定，这里按左右对称处理）
            left = (w * 0.0).toInt()
            right = (w * 0.55).toInt()
        } else {
            left = (w * 0.20).toInt()
            right = (w * 0.80).toInt()
        }
        val top = (h * 0.20).toInt()
        val bottom = (h * 0.80).toInt()
        return x >= left && x <= right && y >= top && y <= bottom
    }

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
                    touchInHitArea = isInHitArea(event.x, event.y)
                    refreshUserAction()   // 摸到桌宠 = 用户有动作
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
                        // 点击判定只在螃蟹本体附近生效（透明角落点了没反应）
                        if (touchInHitArea) {
                            when {
                                elapsed > 600 -> onLongPress()
                                System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                                else -> {
                                    lastTapTime = System.currentTimeMillis()
                                    onTap()
                                }
                            }
                        }
                    } else {
                        // 松手后检查是否拖到了边缘：只有拖到边缘才吸附并展示吸附动图
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
            .setContentTitle("小克 Clawd")
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
        screenshotObserver?.let { contentResolver.unregisterContentObserver(it) }
        screenshotObserver = null
        handler.removeCallbacksAndMessages(null)
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}