package app.qingjian.inputmethod.solve

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings as SystemSettings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.Ink
import app.qingjian.inputmethod.panel.base.PanelIcons
import app.qingjian.inputmethod.panel.base.PanelIcons.Icon
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.theme.AppColors
import app.qingjian.inputmethod.theme.Palette
import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮球：任意界面常驻一颗可拖动的青简小球，点开菜单「搜题 / 截屏搜题 / 收起球」，
 * 两条链路共用一个**独立悬浮搜题页**（输入题目或带截图，答案流式显示，可滚动可复制），
 * 搜到自动进 [SolveHistory]。与键盘搜题条互不相干——键盘没开也能搜。
 *
 * 结构（对齐稿见 输入法/悬浮球功能-交接文档.md）：
 * - [FloatingBall] 是开关入口：App 首页宫格与功能面板宫格两处调 [FloatingBall.setEnabled]，写设置 + 起停服务；
 * - [FloatingBallService] 前台服务承载三个悬浮窗：球（拖动吸边、位置记忆）、菜单（点外关闭）、搜题页（可拖动）；
 * - 截屏搜题复用 [SolveCaptureService] 挂着的投影（授权一次后不再弹框），发起前先藏球与菜单，别把自己截进图里；
 * - 隐私模式开着时搜题页挂 FLAG_SECURE（录屏里隐形），与键盘的隐私幕层一致。
 */
object FloatingBall {
    private const val KEY_BALL_X = "ball_x"
    private const val KEY_BALL_Y = "ball_y"

    /** 球的位置不是「设置」，单独存一份，免得每次拖动都触发键盘重建。 */
    private fun prefs(context: Context) =
        context.getSharedPreferences("floating_ball", Context.MODE_PRIVATE)

    fun enabled(context: Context) =
        Settings.preferences(context).getBoolean(Settings.KEY_FLOATING_BALL, false)

    /** 上次停的位置；没存过为 (Int.MIN_VALUE, Int.MIN_VALUE)。 */
    fun position(context: Context): Pair<Int, Int> = prefs(context).run {
        getInt(KEY_BALL_X, Int.MIN_VALUE) to getInt(KEY_BALL_Y, Int.MIN_VALUE)
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_BALL_X, x).putInt(KEY_BALL_Y, y).apply()
    }

    /** 开关悬浮球。开时没悬浮窗权限就先跳授权页（设置先不写，授权回来再点一次才真正打开）。 */
    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext
        if (on && !SystemSettings.canDrawOverlays(app)) {
            Toast.makeText(app, "先允许「显示在其他应用上层」，回来再点一次开启", Toast.LENGTH_LONG).show()
            app.startActivity(
                Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        Settings.preferences(app).edit().putBoolean(Settings.KEY_FLOATING_BALL, on).apply()
        syncService(app, on)
        Toast.makeText(app, if (on) "悬浮球已开启" else "悬浮球已关闭", Toast.LENGTH_SHORT).show()
    }

    /** 起停服务（设置已写好；服务自己也会听设置变化兜底）。 */
    fun syncService(context: Context, on: Boolean) {
        val app = context.applicationContext
        if (on) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(Intent(app, FloatingBallService::class.java))
            } else {
                app.startService(Intent(app, FloatingBallService::class.java))
            }
        } else {
            app.stopService(Intent(app, FloatingBallService::class.java))
        }
    }

    /**
     * 透明度变了让球立刻重画（「球透明度」滑杆拖动中高频调用）：
     * 预览值不走 prefs，广播通知服务里的球按输入法服务的实时值重画。
     */
    fun refreshOpacity(context: Context) {
        val app = context.applicationContext
        app.sendBroadcast(
            Intent(FloatingBallService.ACTION_OPACITY_CHANGED).setPackage(app.packageName),
        )
    }

    /** 「自动收起」秒数改了：让服务里的球按新值重新计时（同进程，广播一跳）。 */
    fun refreshIdle(context: Context) {
        val app = context.applicationContext
        app.sendBroadcast(
            Intent(FloatingBallService.ACTION_IDLE_CHANGED).setPackage(app.packageName),
        )
    }
}

class FloatingBallService : Service() {
    private lateinit var main: Handler
    private lateinit var windowManager: WindowManager
    private lateinit var client: SolveClient

    private var ballView: BallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var ballShown = false


    private var pageView: View? = null
    private var pageParams: WindowManager.LayoutParams? = null
    private var pageShown = false

    /** 截屏/提取前把页面临时藏起来（只改可见性，不删视图），截完放回。 */
    private var pageHiddenForCapture = false

    /** 待搜的截图（截屏搜题带来；页面关掉才清）。页面上不再显示缩略图。 */
    private var shot: Bitmap? = null

    /** 收起的搜题会话还有没有没关掉的内容（答案/截图/题目）——重开页面时据此恢复而不是清空。 */
    private var sessionActive = false

    /** 搜题页窗口宽度比例（0.5–1.0 屏宽）与不透明度（30–100%）；页内滑杆改，存独立 prefs。 */
    private var pageScale = 1f
    private var pageAlpha = 100

    /** 收起页面时记下的题目文字（按标签各记一份，重开时填回对应标签的输入框）。 */
    private var pageQuestion = ""

    /** 收起页面：把当前标签的输入文字存进它自己那一格。 */
    private fun stashCurrentInput() {
        val slot = TAB_KEYS.indexOf(pageTab).coerceIn(0, 3)
        val text = input?.text?.toString().orEmpty()
        tabInputs[slot] = text
        pageQuestion = text
    }

    /** 是否有搜题在飞（收起页面也照飞；重开会话据此显示状态）。 */
    private var asking = false

    /**
     * 页面当前在哪个标签：[TAB_ASK] 搜题（默认）/ [TAB_SHOT] 截屏搜题 / [TAB_EXTRACT] 提取文字 / [TAB_VOICE] 语音问答。
     * 点球直接开页，四个功能做成页内标签，不再走「先弹菜单再选」那一层。
     */
    private var pageTab = TAB_ASK

    /** 页面还开着时切标签：重挂一次页面（视图简单，重挂比逐控件改稳）。 */
    private var tabBarRefresh: (() -> Unit)? = null

    /** 按标签调整页内内容（输入行显隐、占位文案）；[buildPage] 里挂上。 */
    private var applyTabContent: (() -> Unit)? = null

    /**
     * 切到某个标签。
     *
     * 只切界面，**不自动触发截屏/提取**——那两个动作会让页面瞬间藏起来再回来，
     * 用户连标签都没看清就闪一下（实测）。截屏/提取交给页面里的动作按钮，
     * 用户点了才执行。语音例外：切过去直接开听是它唯一的用法。
     */
    private fun selectTab(tab: Int) {
        if (pageTab == tab) return
        // 先把当前标签的输入框文字存起来，切完再填回新标签自己的那一份
        val leaving = TAB_KEYS.indexOf(pageTab).coerceIn(0, 3)
        tabInputs[leaving] = input?.text?.toString().orEmpty()
        pageTab = tab
        // 换了答案内容，旧选区没意义了，操作条收起来
        selectionBarView?.visibility = View.GONE
        tabBarRefresh?.invoke()
        // 填新标签的输入内容（截屏页的说明不会跑到搜题页去）
        val entering = TAB_KEYS.indexOf(pageTab).coerceIn(0, 3)
        input?.setText(tabInputs[entering])
        if (tab == TAB_VOICE) startVoiceAsk()
    }

    /**
     * 每个标签各存一份答案：搜题 / 截屏 / 提取 / 语音互不串。
     *
     * 原来只有一个 [answerBuf]，切到「提取」还顶着上一次搜题的 AI 回复（实测踩过）。
     * 下标与 [TAB_KEYS] 对齐；[currentAnswer] 指向当前标签那一份。
     */
    private val answerBufs = Array(4) { StringBuilder() }

    /** 选中文字后那条操作条（复制选中 / 全选）；没有选中时 GONE。 */
    private var selectionBarView: View? = null
    private var selectionCountView: TextView? = null

    /** 把操作条挪到选区旁边的定位器；页面建好后才有。 */
    private var toolbarPositioner: SelectionToolbarPositioner? = null

    /** 当前标签的答案缓冲（读写都走它）。 */
    /**
     * 选区变了：有选中就把操作条挪到选区旁边露出来，没有就收起。
     *
     * 这个回调在拖动手柄时会被高频触发，所以定位一律 `post` 到下一帧去做
     * （那时测量已完成、坐标才是最新的），且定位器只改 `translation`，不动布局。
     */
    private fun onAnswerSelectionChanged(start: Int, end: Int) {
        val bar = selectionBarView ?: return
        val count = end - start
        if (count <= 0) {
            if (bar.visibility != View.GONE) bar.visibility = View.GONE
            return
        }
        selectionCountView?.text = "$count 字"
        bar.post { toolbarPositioner?.updatePosition() }
    }

    /** 把选中的那一段复制走（不是整段）。 */
    private fun copySelection() {
        val view = answerText ?: return
        val start = view.selectionStart
        val end = view.selectionEnd
        if (start < 0 || end <= start) {
            Toast.makeText(this, "先长按选中要复制的文字", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = view.text.subSequence(start, end).toString()
        if (selected.isBlank()) {
            Toast.makeText(this, "选中的是空白", Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard(selected)
    }

    /** 全选答案区（选区由系统管理，操作条自己弹）。 */
    private fun selectAllAnswer() {
        val view = answerText ?: return
        // 保证 text 是可变 Spannable，Selection.selectAll 才能改到它
        view.setText(view.text, TextView.BufferType.SPANNABLE)
        val spannable = view.text as? android.text.Spannable ?: return
        android.text.Selection.selectAll(spannable)
        onAnswerSelectionChanged(0, spannable.length)
    }

    /** 写剪贴板 + 提示。 */
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        if (clipboard == null) {
            Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("青简", text))
        Toast.makeText(this, "已复制 ${text.length} 字", Toast.LENGTH_SHORT).show()
    }

    private val answerBuf: StringBuilder
        get() = answerBufs[TAB_KEYS.indexOf(pageTab).coerceIn(0, 3)]

    /** 正在飞的那次搜题写在哪个缓冲、属于哪个标签（流式回来时据此判断该不该往屏幕上刷）。 */
    private var askingBuf: StringBuilder? = null
    private var askingTab = TAB_ASK

    /**
     * 每个标签各存一份**输入框文字**：搜题 / 截屏 / 语音互不串。
     *
     * 只有一个 EditText，切标签时先把当前文字存进旧标签那一格、再填新标签那一格，
     * 否则在截屏页打的说明会跑到搜题页的题目框里（实测踩过）。
     */
    private val tabInputs = Array(4) { "" }
    private var input: EditText? = null
    private var answerScroll: ScrollView? = null
    private var answerText: TextView? = null

    /** 截屏授权/取帧卡住时的兜底：10 秒没动静把页面放回来（或把球请回来）。 */
    private val captureFailsafe = Runnable {
        // 页面只是被藏起来等截图：超时了也得放回来，别让它一直不可见
        if (pageHiddenForCapture) restorePageAfterCapture()
        if (FloatingBall.enabled(this) && !pageShown && !ballShown) showBall()
    }

    /** 设置变了：开关关了就撤；隐私变了重挂搜题页；透明度变了让球重画。 */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            Settings.KEY_FLOATING_BALL -> if (!prefs.getBoolean(key, false)) stopSelf()
            Settings.KEY_PRIVACY -> if (pageShown) reopenPageForPrivacy()
            Settings.KEY_BALL_OPACITY -> ballView?.invalidate()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        main = Handler(Looper.getMainLooper())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        client = SolveClient(this)
        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(CHANNEL, "悬浮球", NotificationManager.IMPORTANCE_LOW))
        Settings.preferences(this).registerOnSharedPreferenceChangeListener(prefsListener)
        // 搜题页的窗口大小与透明度：上次调好的沿用
        pageScale = prefs().getInt(KEY_PAGE_SCALE, 100).coerceIn(50, 100) / 100f
        pageAlpha = prefs().getInt(KEY_PAGE_ALPHA, 100).coerceIn(MIN_PAGE_ALPHA, 100)
        // 透明度滑杆拖动中的实时刷新（预览值不走 prefs，球重画读服务侧 effectiveBallOpacity）
        registerReceiver(opacityReceiver, android.content.IntentFilter(ACTION_OPACITY_CHANGED))
        // 「自动收起」秒数改了：球按新值重新计时
        registerReceiver(idleReceiver, android.content.IntentFilter(ACTION_IDLE_CHANGED))
    }

    /** 滑杆拖动中高频到达：球按输入法服务侧的最新值重画。 */
    private val opacityReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ballView?.invalidate()
        }
    }

    /** 「自动收起」秒数改了：球按新值重新计时（顺带从收起态展开回球，让用户看到改动生效）。 */
    private val idleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ballView?.armCollapse()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 通知上的「关闭」：写回设置让两处入口的状态一致，里面会顺带停掉本服务
            FloatingBall.setEnabled(this, false)
            return START_NOT_STICKY
        }
        // 前台通知：失败也不能挡住球（某些 ROM 上 specialUse 类型不被接受）
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.onFailure { android.util.Log.w("FloatingBall", "startForeground failed", it) }
        if (!FloatingBall.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        showBall()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(opacityReceiver) }
        runCatching { unregisterReceiver(idleReceiver) }
        Settings.preferences(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        main.removeCallbacks(captureFailsafe)
        main.removeCallbacks(answerFlushRunnable)
        runCatching { client.cancel() }
        listOfNotNull(ballView, pageView).forEach { runCatching { windowManager.removeView(it) } }
        ballView = null; pageView = null
        ballShown = false; pageShown = false
        super.onDestroy()
    }

    /** 深浅色变了：球重挂一次换新色（菜单与搜题页即用即建，天然跟新）。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ballShown) {
            hideBall()
            showBall()
        }
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingBallService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getText(R.string.app_name))
            .setContentText("悬浮球已开启，点按关闭")
            .setContentIntent(stop)
            .setOngoing(true)
            .build()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    private fun showBall() {
        android.util.Log.d(
            "FloatingBall",
            "showBall: ballShown=$ballShown canDraw=${SystemSettings.canDrawOverlays(this)} " +
                "enabled=${FloatingBall.enabled(this)}",
        )
        if (ballShown) return
        if (!SystemSettings.canDrawOverlays(this)) {
            android.util.Log.w("FloatingBall", "showBall: no overlay permission")
            return
        }
        val view = BallView()
        val params = WindowManager.LayoutParams(
            dp(38f).toInt(), dp(38f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 球只有 38dp，本来就不盖住别处；NOT_FOCUSABLE 让它不抢焦点。
            // 注意别加 NOT_TOUCH_MODAL：那会让窗口收不到自己的触摸，球就点不开了。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (savedX, savedY) = FloatingBall.position(this@FloatingBallService)
            x = if (savedX == Int.MIN_VALUE) screenW() - dp(46f).toInt() else savedX
            y = if (savedY == Int.MIN_VALUE) (screenH() * 0.34f).toInt() else savedY
            x = x.coerceIn(0, screenW() - width)
            y = y.coerceIn(0, screenH() - height)
        }
        windowManager.addView(view, params)
        ballView = view
        ballParams = params
        ballShown = true
        // 开起来就开始数闲置时间：到点缩成贴边小箭头
        view.armCollapse()
    }

    private fun hideBall() {
        // 球要没了：先把闲置计时的回调撤掉，免得对着已经移除的视图改窗口
        ballView?.stopCollapse()
        ballView?.let { runCatching { windowManager.removeView(it) } }
        ballView = null
        ballParams = null
        ballShown = false
    }

    // —— 文字提取 ——

    /**
     * 菜单「提取文字」：藏球与菜单 → 静默截一帧 → 交给 [ExtractText] 走 vision 抄字 →
     * 结果放进一个可复制的面板（复用搜题页的答案区样式）。
     */
    private fun startExtractText() {
        hideBall()
        // 页面开着的话先藏（只改可见性，不删视图——删了再建就是"闪一下"）
        if (pageShown) hidePageTemporarily() else hideBall()
        SolveShot.imeHeight = 0
        SolveShot.onResult = { bitmap ->
            main.removeCallbacks(captureFailsafe)
            showExtractPage(bitmap)
        }
        if (SolveCaptureService.ready) {
            startForegroundService(Intent(this, SolveCaptureService::class.java))
        } else {
            startActivity(
                Intent(this, SolveCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        main.postDelayed(captureFailsafe, 10_000)
    }

    /** 文字提取结果页：与搜题页同一套外观，答案区换成识别出的文字，可长按选中复制。 */
    private fun showExtractPage(bitmap: Bitmap) {
        pageTab = TAB_EXTRACT
        sessionActive = true
        answerBuf.setLength(0)
        // 页面还在（刚才只是藏起来）：放回来、切到提取标签，不重建视图
        if (pageHiddenForCapture) {
            pageShown = true
            restorePageAfterCapture()
        } else if (pageShown) {
            reopenPageForPrivacy()
        } else {
            addPage()
        }
        answerText?.text = "正在识别文字…"
        ExtractText.from(this, bitmap) { text, error ->
            when {
                error != null -> answerText?.text = "提取失败：${error.take(60)}"
                text.isNullOrBlank() -> answerText?.text = "没有识别到文字"
                else -> {
                    answerBuf.setLength(0)
                    answerBuf.append(text)
                    answerText?.text = text
                }
            }
        }
    }

    // —— 语音问答 ——

    /**
     * 菜单「语音问答」：开搜题页 → 页面里点话筒听一句 → 听到的文字填进题目框并**自动发搜**
     * （语音问答的语义就是"问"，不像打字要确认；想改再打字重问）。
     */
    private fun startVoiceAsk() {
        if (pageShown) {
            listenInPage()
        } else {
            showPage(null)
            main.postDelayed({ listenInPage() }, 350)
        }
    }

    /**
     * 在已开的搜题页里听一句：状态写进答案区，听到就发。
     * 识别方优先用青简自己的云端识别（[VoiceAsk] 内部选：引擎配好就走引擎，
     * 没配才退到系统 SpeechRecognizer——国内手机多半没有系统识别服务）。
     */
    private fun listenInPage() {
        val engine = app.qingjian.inputmethod.service.QingjianInputMethodService.current?.engineForVoice()
        val asker = VoiceAsk(this, engine)
        // 语音的中间状态与报错**不写答案区**：那会把上一轮的报错留在搜题页上（实测踩过）。
        // 答案区只放搜题结果，语音状态走浮层/Toast。
        asker.listen(
            onState = { /* 状态交给浮层显示，答案区不动 */ },
            onHeard = { heard ->
                input?.setText(heard)
                ask(heard)
            },
            onError = { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            },
            onNeedsFinish = { finishVoice ->
                voiceFinish = finishVoice
            },
        )
    }

    /** 引擎识别要显式收尾：点「搜题」时/关页面时调。 */
    private var voiceFinish: (() -> Unit)? = null

    /** 按住说话用的实例与浮层（两条入口共用）。 */
    private var holdAsker: VoiceAsk? = null
    private var holdOverlay: VoiceHoldOverlay? = null
    private var holdButtonView: HoldTalkButton? = null

    /**
     * 「按住说」按钮：自绘，自己处理 DOWN/MOVE/UP。
     * 用自绘 View 而不是 TextView + setOnTouchListener——后者在某些父容器里会被点击判定吃掉。
     */
    private inner class HoldTalkButton(context: Context, private val colors: AppColors) : View(context) {
        var onPress: () -> Unit = {}
        var onMove: (Float) -> Unit = {}
        var onRelease: (Boolean) -> Unit = {}

        private val ink = Ink(Palette.of(context), resources.displayMetrics.density)
        private val bg = android.graphics.RectF()
        private var downRawY = 0f
        private var downRawX = 0f
        private var moved = false

        /** 按下时按钮文字变「松手搜索」：由外面的 holdButtonViewLabel 控制。 */
        var label: String = "按住说"
            set(value) {
                field = value
                invalidate()
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = (ink.measure("松手搜索", 14f, bold = true) + ink.dp(28f)).toInt()
            val h = ink.dp(38f).toInt()
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            bg.set(0f, 0f, width.toFloat(), height.toFloat())
            ink.round(canvas, bg, 12f, colors.brand)
            ink.text(canvas, label, 14f, colors.onBrand, width / 2f, height / 2f, bold = true)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    downRawX = event.rawX
                    moved = false
                    label = "松手搜索"
                    invalidate()
                    onPress()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (kotlin.math.abs(event.rawY - downRawY) > 6f || kotlin.math.abs(event.rawX - downRawX) > 6f)) {
                        moved = true
                    }
                    if (moved) onMove(event.rawY - downRawY)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    label = "按住说"
                    invalidate()
                    onRelease(false)
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    label = "按住说"
                    invalidate()
                    onRelease(true)
                    return true
                }
            }
            return false
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    /** 按住说话的状态：按下位置、是否已进入取消区。 */
    private var holdStartY = 0f
    private var holdCancelling = false

    /**
     * 「按住说」按钮的触摸：按下开始听 → 手指下滑进取消区（✕ 亮）→ 松手：
     * 没取消就把识别到的文字填进题目框并发搜，取消了就丢掉。
     * 触摸处理在 [HoldTalkButton] 里（自绘 View，自己收 DOWN/MOVE/UP，不被父容器吃掉）。
     */
    private fun onHoldButtonPressed() = startHoldListening()

    private fun onHoldButtonMoved(dy: Float) {
        val shouldCancel = dy > VoiceHoldOverlay.CANCEL_DISTANCE_DP * resources.displayMetrics.density
        if (shouldCancel != holdCancelling) {
            holdCancelling = shouldCancel
            holdOverlay?.cancelling = shouldCancel
        }
    }

    private fun onHoldButtonReleased(cancelled: Boolean) = finishHoldListening(cancelled)

    /** 按下：开一个 VoiceAsk 并显示浮层（优先引擎识别，静音自动收）。 */
    private fun startHoldListening() {
        val engine = app.qingjian.inputmethod.service.QingjianInputMethodService.current?.engineForVoice()
        val asker = VoiceAsk(this, engine)
        holdAsker = asker
        pendingHeard = null
        holdError = null
        val overlay = holdOverlay ?: return
        // 先亮出来：哪怕识别方立刻报错（没配语音服务），用户也能看到"按住说话"这套交互在
        overlay.text = ""
        overlay.cancelling = false
        overlay.recognizing = false
        overlay.visibility = View.VISIBLE
        holdButtonView?.label = "松手搜索"
        if (!asker.available()) {
            // 没有识别方：浮层留着说明为什么，松手时弹提示让用户去配
            overlay.text = "没有可用的语音识别：去「我的 → 语音识别」配一个（火山引擎）"
            holdError = VOICE_UNAVAILABLE
            return
        }
        asker.listen(
            onState = { state ->
                if (holdAsker != null) {
                    overlay.text = state
                    overlay.recognizing = false
                }
            },
            onHeard = { heard ->
                // 引擎/系统都可能在这里回最终结果：直接当"松手后要搜的题"
                pendingHeard = heard
                holdOverlay?.recognizing = true
            },
            onError = { message ->
                pendingHeard = null
                holdOverlay?.text = message
                holdOverlay?.recognizing = false
                holdError = message
            },
            onNeedsFinish = { finish -> voiceFinish = finish },
        )
    }

    /** 松手：没取消就把这句话发出去；取消了就丢掉。 */
    private fun finishHoldListening(cancelled: Boolean) {
        val asker = holdAsker
        holdAsker = null
        holdOverlay?.visibility = View.GONE
        holdButtonView?.label = "按住说"
        if (cancelled) {
            asker?.cancel()
            pendingHeard = null
            holdError = null
            return
        }
        // 引擎识别要显式收尾：先让它把最后一段吐出来
        voiceFinish?.invoke()
        voiceFinish = null
        // 已经拿到最终结果就直接搜；还在等就等 onHeard 回来（下面这个延时兜底 1.2 秒）
        val heard = pendingHeard
        pendingHeard = null
        if (heard != null) {
            input?.setText(heard)
            ask(heard)
        } else {
            val error = holdError
            holdError = null
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            } else {
                main.postDelayed({ asker?.cancel() }, 1_200)
            }
        }
    }

    /** 识别回来但还没松手时暂存；松手就发。 */
    private var pendingHeard: String? = null
    private var holdError: String? = null

    // —— 答案流式刷新的节流：每 FLUSH_MS 才全文重排一次 Markdown ——
    private var lastAnswerFlushAt = 0L
    private var answerFlushScheduled = false
    private val answerFlushRunnable = Runnable {
        answerFlushScheduled = false
        flushAnswerNow()
    }

    /** 收到一段增量：够久就直接刷，否则排一次延后刷（避免每个字都全文重排）。 */
    private fun scheduleAnswerFlush() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAnswerFlushAt >= ANSWER_FLUSH_MS) {
            flushAnswerNow()
        } else if (!answerFlushScheduled) {
            answerFlushScheduled = true
            main.postDelayed(answerFlushRunnable, ANSWER_FLUSH_MS)
        }
    }

    /** 立刻把**正在飞的那次搜题**的答案重排进答案区（增量够久、收尾、重开页面时调）。 */
    private fun flushAnswerNow() {
        main.removeCallbacks(answerFlushRunnable)
        answerFlushScheduled = false
        lastAnswerFlushAt = android.os.SystemClock.uptimeMillis()
        val text = answerText ?: return          // 页面收起时不用刷，重开时整段恢复
        // 刷的是"发起这次搜题的那个标签"的缓冲区：用户可能已经切走了，
        // 那就别把答案糊到新标签的答案区上
        val sink = askingBuf ?: answerBuf
        if (sink.isEmpty()) return
        if (TAB_KEYS.indexOf(pageTab) != TAB_KEYS.indexOf(askingTab)) return
        text.text = MarkdownLite.format(sink.toString())
        // 贴着底部看就跟读，往上翻了不拽
        val scroll = answerScroll ?: return
        if (scroll.scrollY >= scroll.getChildAt(0).height - scroll.height - dp(4f)) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // —— 悬浮搜题页 ——

    /** [shot] 不为 null 是截屏搜题：截图挂到会话上，**不自动发**——页面上点「搜题」才发。 */
    private fun showPage(shot: Bitmap?) {
        hideBall()
        main.removeCallbacks(captureFailsafe)
        if (shot != null) {
            // 新的截屏搜题：覆盖会话里的旧截图；答案保留（想接着上一条问也行），输入框不动
            this.shot = shot
            sessionActive = true
            pageTab = TAB_SHOT
        }
        // 页面还在（截屏前只是藏起来）：放回来、切标签，不重建视图
        if (pageHiddenForCapture) {
            pageShown = true
            restorePageAfterCapture()
        } else if (pageShown) {
            reopenPageForPrivacy()
        } else {
            addPage()
        }
        // 截屏搜题：提示用户点搜题；恢复会话：答案与状态原样回来
        if (shot != null && answerBuf.isEmpty()) answerText?.text = "已截屏，点「搜题」开始解答"
    }

    /** 页面关着时隐私开关变了：直接重挂（FLAG_SECURE 挂在窗口上，重挂最稳）。 */
    private fun reopenPageForPrivacy() {
        pageView?.let { runCatching { windowManager.removeView(it) } }
        pageShown = false
        addPage()
    }

    private fun addPage() {
        val colors = AppColors.of(this)
        val view = buildPage(colors)
        val width = pageWidth()
        val params = WindowManager.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (if (Settings.load(this).privacy) WindowManager.LayoutParams.FLAG_SECURE else 0) or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW() - width) / 2
            y = dp(18f).toInt()
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            // 窗口整体透明度：页内滑杆调，文字与背景一起淡
            alpha = pageAlphaNow() / 100f
        }
        windowManager.addView(view, params)
        pageView = view
        pageParams = params
        pageShown = true
    }

    /** 页面宽度：按 [pageScale]（0.5–1.0）在屏宽里取，留一点边。 */
    private fun pageWidth(): Int {
        val max = min((screenW() - dp(28f)).toInt(), dp(420f).toInt())
        return (max * pageScale).toInt().coerceAtLeast(dp(200f).toInt())
    }

    private fun pageAlphaNow(): Int = pageAlpha.coerceIn(MIN_PAGE_ALPHA, 100)

    private fun pageScalePercent(): Int = (pageScale * 100).toInt().coerceIn(50, 100)

    /** 拖动「大小」滑杆：按新比例重设窗口宽并居中；超出屏幕右边就夹回来。 */
    private fun applyPageGeometry() {
        val params = pageParams ?: return
        val view = pageView ?: return
        val width = pageWidth()
        params.width = width
        params.x = ((screenW() - width) / 2).coerceAtLeast(0)
        try {
            windowManager.updateViewLayout(view, params)
            // 换宽后让整棵树按新宽度重新量：放到下一帧做，避免打断正在拖的滑杆
            view.post { runCatching { view.requestLayout() } }
        } catch (e: Exception) {
            android.util.Log.w("FloatingBall", "resize page failed", e)
        }
        prefs().edit().putInt(KEY_PAGE_SCALE, pageScalePercent()).apply()
    }

    /**
     * 一条「标签 + 滑杆 + 数值」行：滑杆是原生 SeekBar（无极拖动），数值跟手变。
     * [from]–[to] 是取值区间；[onChange] 在**松手时**回调（拖动中改窗口宽会让滑杆自己跟着挪、拖不准）。
     */
    private fun sliderRow(
        colors: AppColors,
        label: String,
        value: Int,
        from: Int,
        to: Int,
        live: Boolean = false,
        onChange: (Int) -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(colors.secondary)
            textSize = 12f
        }, LinearLayout.LayoutParams(dp(34f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        val valueText = TextView(this).apply {
            text = "$value%"
            setTextColor(colors.text)
            textSize = 12f
            gravity = Gravity.END
        }
        // 上一次已经应用过的值：避免同一个值反复触发 onChange（设 progress 时会回调一次）
        var baseline = value
        val bar = android.widget.SeekBar(this).apply {
            max = to - from
            progress = (value - from).coerceIn(0, max)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                /**
                 * 拖动中跟手改数字并即时生效（[live]）。
                 * 不判 fromUser：`adb input` 这类合成手势里它是 false，但人来拖是真 true——
                 * 两边行为一致才好验证，用户拖也一样顺。
                 */
                override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val current = progress + from
                    valueText.text = "$current%"
                    if (live && current != baseline) {
                        baseline = current
                        onChange(current)
                    }
                }

                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit

                /** 松手才落地：改窗口大小/透明度。 */
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {
                    onChange((bar?.progress ?: 0) + from)
                }
            })
        }
        row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valueText, LinearLayout.LayoutParams(dp(40f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    /** 页面几何与透明度的存档（独立 prefs，不碰 settings.xml 免得触发键盘重建）。 */
    private fun prefs() = getSharedPreferences("floating_ball", Context.MODE_PRIVATE)

    /**
     * 复制当前标签的内容（答案 / 提取出的文字）到剪贴板。
     *
     * 悬浮窗里选不中、也弹不出系统复制菜单，所以整段复制是唯一稳的路子。
     * 复制的是 [answerBuf] 而不是屏幕上那个 TextView 的文字——后者可能是
     * 「点重新截屏抓一帧」这类占位提示，复制它没意义。
     */
    private fun copyAnswer() {
        val text = answerBuf.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "还没有内容可复制", Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard(text)
    }

    private fun buildPage(colors: AppColors): View {
        fun roundBg(color: Int, radiusDp: Float) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radiusDp)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(colors.surface, 18f)
            setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(14f).toInt())
        }
        // 头部：标题 + 收起（─，会话留在后台）+ 关闭（✕，整条会话作废）；整条按住可拖动
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = "搜题"
            setTextColor(colors.text)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            // 关掉字体自带上下留白、自己给够纵向空间：否则中文加粗字的上沿会被裁掉
            includeFontPadding = false
            setPadding(0, dp(10f).toInt(), 0, dp(10f).toInt())
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 复制答案：悬浮窗（TYPE_APPLICATION_OVERLAY）里系统的文本选择工具栏不会弹出来，
        // 选中了也没有「复制」可点（实测），所以自己给一个明确的入口。
        header.addView(TextView(this).apply {
            text = "复制"
            setTextColor(colors.brand)
            textSize = 13f
            setPadding(dp(10f).toInt(), dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt())
            contentDescription = "复制这段内容"
            setOnClickListener { copyAnswer() }
        })
        header.addView(TextView(this).apply {
            text = "─"
            setTextColor(colors.secondary)
            textSize = 16f
            setPadding(dp(6f).toInt(), dp(8f).toInt(), dp(2f).toInt(), dp(8f).toInt())
            contentDescription = "收起到悬浮球"
            setOnClickListener { minimizePage() }
        })
        header.addView(TextView(this).apply {
            text = "✕"
            setTextColor(colors.secondary)
            textSize = 16f
            setPadding(dp(10f).toInt(), dp(8f).toInt(), dp(2f).toInt(), dp(8f).toInt())
            setOnClickListener { closePage() }
        })
        root.addView(header)

        // 标签栏：四个功能一屏切换（原来是点球弹菜单再选，多一层）
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f).toInt(), 0, dp(2f).toInt())
        }
        val tabButtons = mutableListOf<TextView>()
        listOf(
            "搜题" to TAB_ASK,
            "截屏" to TAB_SHOT,
            "提取" to TAB_EXTRACT,
            "语音" to TAB_VOICE,
        ).forEach { (label, tab) ->
            val button = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(7f).toInt(), 0, dp(7f).toInt())
                setOnClickListener { selectTab(tab) }
            }
            tabButtons += button
            tabs.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabs)

        /** 按当前标签刷新标签栏配色、标题、输入行与占位文案。 */
        fun refreshTabs() {
            val on = roundBg(colors.brand, 9f)
            tabButtons.forEachIndexed { i, button ->
                val selected = TAB_KEYS[i] == pageTab
                button.background = if (selected) on else null
                button.setTextColor(if (selected) colors.onBrand else colors.secondary)
            }
            titleView.text = TAB_TITLES[TAB_KEYS.indexOf(pageTab).coerceAtLeast(0)]
            applyTabContent?.invoke()
        }
        tabBarRefresh = ::refreshTabs
        // 这里先不 refreshTabs()：输入行/动作行还没建出来，等页面拼完末尾统一上一次

        // 输入行：题目输入框 + 按住说。按标签换：搜题/语音留着输入行，截屏/提取不需要它
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val field = EditText(this).apply {
            hint = "输入题目…"
            setHintTextColor(colors.faint)
            setTextColor(colors.text)
            textSize = 14f
            background = roundBg(colors.background, 12f)
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    submit(); true
                } else false
            }
        }
        row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 右边那颗按钮按标签换：搜题 →「搜题」；语音 →「按住说」
        // （原来固定是「按住说」，搜题页就没有发送按钮，只能靠键盘回车，实测不好用）
        val sendButton = TextView(this).apply {
            text = "搜题"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt())
            background = roundBg(colors.brand, 12f)
            setTextColor(colors.onBrand)
            setOnClickListener { submit() }
        }
        val holdButton = HoldTalkButton(this, colors).apply {
            onPress = { startHoldListening() }
            onMove = { dy ->
                val shouldCancel = dy > VoiceHoldOverlay.CANCEL_DISTANCE_DP * resources.displayMetrics.density
                if (shouldCancel != holdCancelling) {
                    holdCancelling = shouldCancel
                    holdOverlay?.cancelling = shouldCancel
                }
            }
            onRelease = { cancelled -> finishHoldListening(cancelled) }
        }
        holdButtonView = holdButton
        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(8f).toInt() }
        row.addView(sendButton, buttonParams)
        row.addView(holdButton, buttonParams)
        val rowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10f).toInt() }
        root.addView(row, rowParams)
        input = field
        // 填回当前标签自己的输入内容（每个标签各存一份，互不串）
        val slot = TAB_KEYS.indexOf(pageTab).coerceIn(0, 3)
        val restore = tabInputs[slot].ifEmpty { if (slot == TAB_ASK) pageQuestion else "" }
        if (restore.isNotEmpty()) field.setText(restore)

        // 动作行：截屏/提取标签用（那两个标签不需要输入框，但必须有可点的按钮）
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        fun actionButton(label: String, primary: Boolean) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14f).toInt(), dp(9f).toInt(), dp(14f).toInt(), dp(9f).toInt())
            background = roundBg(if (primary) colors.brand else colors.background, 10f)
            setTextColor(if (primary) colors.onBrand else colors.text)
        }
        val actionPrimary = actionButton("重新截屏", primary = true).apply {
            setOnClickListener {
                when (pageTab) {
                    // 截屏页这颗是「重新截屏」
                    TAB_SHOT -> startShotSearch()
                    // 提取页这颗是「重新提取」
                    TAB_EXTRACT -> startExtractText()
                }
            }
        }
        val actionSecondary = actionButton("重新截屏", primary = false).apply {
            setOnClickListener { startShotSearch() }
        }
        actionRow.addView(actionPrimary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(actionSecondary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8f).toInt()
        })
        root.addView(actionRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10f).toInt() })

        // 选中文字后的操作条：悬浮窗里系统的文本选择工具栏（ActionMode）不弹，
        // 但选区本身是好的（高亮、拖手柄都正常），所以只补一个自己的操作条。
        val selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = roundBg(colors.surface, 10f)
            setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(6f).toInt())
        }
        val selectionCount = TextView(this).apply {
            setTextColor(colors.secondary)
            textSize = 12f
        }
        fun barButton(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(colors.brand)
            textSize = 13f
            setPadding(dp(10f).toInt(), dp(6f).toInt(), dp(10f).toInt(), dp(6f).toInt())
            setOnClickListener { action() }
        }
        val copySelectionButton = barButton("复制选中") { copySelection() }
        val selectAllButton = barButton("全选") { selectAllAnswer() }
        // 操作条是 wrap_content（要浮在选区旁边），所以这里的 weight 换成右间距
        selectionBar.addView(selectionCount, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(10f).toInt() })
        selectionBar.addView(copySelectionButton)
        selectionBar.addView(selectAllButton)
        selectionBarView = selectionBar
        selectionCountView = selectionCount

        // 答案区：流式显示，可滚动，长按可选中
        val scroll = ScrollView(this).apply {
            background = roundBg(colors.background, 12f)
            clipToOutline = true
        }
        // 用自定义 TextView 是为了拿到选区变化的回调（系统的 OnSelectionChangedListener 不公开）。
        // movementMethod 不设 ScrollingMovementMethod：滚动本来由外层 ScrollView 负责，
        // 而它还会和文本选择抢手势——setTextIsSelectable 自己会设 ArrowKeyMovementMethod。
        val text = SelectableAnswerTextView(this).apply {
            setTextColor(colors.text)
            textSize = 14f
            setLineSpacing(dp(2f), 1f)
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(12f).toInt())
            setTextIsSelectable(true)
            onSelection = { start, end -> onAnswerSelectionChanged(start, end) }
        }
        scroll.addView(text)
        // 答案区只是个 FrameLayout 壳（供定位器取可见范围），里面只放滚动区。
        // 操作条不放这里——它要浮到整个页面最上层，否则会被裁在答案区里、还压着正文。
        val answerBox = android.widget.FrameLayout(this).apply {
            addView(scroll, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        root.addView(answerBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, answerHeight(),
        ).apply { topMargin = dp(10f).toInt() })
        answerScroll = scroll
        answerText = text
        // 会话恢复：答案与状态原样回来（收起期间流式还在写 answerBuf，重开就是最新样子）
        if (answerBuf.isNotEmpty()) text.text = MarkdownLite.format(answerBuf.toString())
        else if (!sessionActive) text.text = "答案会流式显示在这里"

        /**
         * 按标签调整页面内容：
         * - 搜题：输入行（题目框 + 搜题按钮）
         * - 语音：输入行（+ 按住说）
         * - 截屏：输入行（可打字补充说明）+ 下面一行「重新截屏」
         * - 提取：动作行「重新提取」（这页不用打字）
         *
         * 每个分支都**明确写**该标签该显示什么，不能"只在为空时才写"——
         * 否则上一轮留下的报错会一直贴在答案区（实测踩过）。
         */
        applyTabContent = {
            val needsInput = pageTab != TAB_EXTRACT
            row.visibility = if (needsInput) View.VISIBLE else View.GONE
            // 截屏页既要打字、也要能重截：输入行 + 重截行都露出来；提取页只留动作行
            actionRow.visibility = if (pageTab == TAB_EXTRACT || pageTab == TAB_SHOT) View.VISIBLE else View.GONE
            val voiceTab = pageTab == TAB_VOICE
            sendButton.visibility = if (voiceTab) View.GONE else View.VISIBLE
            holdButton.visibility = if (voiceTab) View.VISIBLE else View.GONE
            when (pageTab) {
                TAB_SHOT -> {
                    // 这行只放「重新截屏」：搜题走输入行右边那颗按钮，还能附一句文字说明
                    actionPrimary.text = "重新截屏"
                    actionSecondary.visibility = View.GONE
                    field.hint = "补充说明（可留空）…"
                    text.text = when {
                        answerBuf.isNotEmpty() -> MarkdownLite.format(answerBuf.toString())
                        shot != null -> "已截屏，点「搜题」开始解答；也可以在题目框里补一句说明"
                        else -> "点「重新截屏」抓一帧"
                    }
                }
                TAB_EXTRACT -> {
                    actionPrimary.text = "重新提取"
                    actionSecondary.visibility = View.GONE
                    text.text = when {
                        answerBuf.isNotEmpty() -> MarkdownLite.format(answerBuf.toString())
                        else -> "点「重新提取」抓一帧，把图上的字抄下来"
                    }
                }
                TAB_VOICE -> {
                    field.hint = "按住说，或在这里打字…"
                    text.text = when {
                        answerBuf.isNotEmpty() -> MarkdownLite.format(answerBuf.toString())
                        else -> "按住「按住说」，松手直接搜"
                    }
                }
                else -> {
                    field.hint = "输入题目…"
                    text.text = when {
                        answerBuf.isNotEmpty() -> MarkdownLite.format(answerBuf.toString())
                        else -> "答案会流式显示在这里"
                    }
                }
            }
        }
        // 页面拼完了：标题、标签高亮、输入行/动作行按当前标签上一次
        tabBarRefresh?.invoke()
        // 两条无极滑杆：窗口大小（50–100% 屏宽）、页面不透明度（30–100%）
        // 都做成 live（拖动中即时生效）：只改窗口参数、不重建视图，滑杆自己不会被重置
        root.addView(
            sliderRow(colors, "大小", pageScalePercent(), 50, 100, live = true) { value ->
                pageScale = value / 100f
                applyPageGeometry()
            },
        )
        root.addView(
            sliderRow(colors, "透明", pageAlphaNow(), MIN_PAGE_ALPHA, 100, live = true) { value ->
                pageAlpha = value
                pageParams?.let {
                    it.alpha = value / 100f
                    runCatching { windowManager.updateViewLayout(pageView, it) }
                }
                prefs().edit().putInt(KEY_PAGE_ALPHA, value).apply()
            },
        )

        // 头部拖动：整页跟着走
        var downRaw = 0f to 0f
        var start = 0 to 0
        header.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRaw = event.rawX to event.rawY
                    start = pageParams!!.x to pageParams!!.y
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    pageParams!!.apply {
                        x = start.first + (event.rawX - downRaw.first).toInt()
                        y = start.second + (event.rawY - downRaw.second).toInt()
                    }
                    windowManager.updateViewLayout(pageView, pageParams)
                    true
                }
                else -> false
            }
        }
        // 按住说话的浮层：盖在答案区上方，平时 GONE
        val overlay = VoiceHoldOverlay(this, Palette.of(this)).apply {
            visibility = View.GONE
        }
        holdOverlay = overlay
        val wrapper = android.widget.FrameLayout(this).apply {
            addView(root, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(overlay, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP
                topMargin = dp(54f).toInt()   // 压在输入行下方，不挡输入框
            })
            // 操作条放在最上层、左上角对齐：位置全靠 translationX/Y 算，
            // 不能给别的 gravity，否则 translation 会叠在 gravity 偏移上，越算越偏。
            addView(selectionBar, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.START })
        }
        // 选区一变就把操作条挪到选区旁边（不再固定压在答案区顶部）
        toolbarPositioner = SelectionToolbarPositioner(
            overlayRoot = wrapper,
            answerContainer = answerBox,
            answerView = text,
            toolbar = selectionBar,
        )
        return wrapper
    }

    /** 答案区高度：小屏别占过半。 */
    private fun answerHeight() = min(dp(300f), screenH() * 0.38f).toInt()

    /**
     * 收起（最小化）：会话留在后台——在飞的请求继续飞，答案继续攒，截图/题目都留着；
     * 球回来，点球再开菜单「搜题」就是回到这个会话。这是「在后面搜题」的那条路。
     */
    private fun minimizePage() = minimizePage(showBall = true)

    /**
     * 收起页面。[showBall] 为 false 时球也不露出来——截屏/提取文字前要用这一种，
     * 否则球会被一起截进图里。
     */
    private fun minimizePage(showBall: Boolean) {
        stashCurrentInput()
        // 收起时把待刷的那一次立刻刷掉，免得重开时还是半截
        flushAnswerNow()
        main.removeCallbacks(answerFlushRunnable)
        answerFlushScheduled = false
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(pageView?.windowToken, 0)
        pageView?.let { runCatching { windowManager.removeView(it) } }
        pageView = null
        pageParams = null
        pageShown = false
        sessionActive = true
        if (showBall && FloatingBall.enabled(this)) showBall()
    }

    /** 关闭：整条会话作废——请求作废、答案/截图/题目全清，球回来。 */
    private fun closePage() {
        client.cancel()
        main.removeCallbacks(answerFlushRunnable)
        answerFlushScheduled = false
        asking = false
        sessionActive = false
        pageQuestion = ""
        // 关掉整条会话：四个标签的输入与答案一起清空
        for (i in tabInputs.indices) tabInputs[i] = ""
        for (buf in answerBufs) buf.setLength(0)
        shot = null
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(pageView?.windowToken, 0)
        pageView?.let { runCatching { windowManager.removeView(it) } }
        pageView = null
        pageParams = null
        pageShown = false
        if (FloatingBall.enabled(this)) showBall()
    }

    /**
     * 截屏前临时藏页面：**只把窗口设为不可见，不删视图**。
     *
     * 原来走 [minimizePage] 会把页面 removeView 掉、截完再 addPage 重建，
     * 中间那几十到几百毫秒就是用户看到的"闪一下"。这里视图树一直留着，恢复时秒回。
     */
    private fun hidePageTemporarily() {
        val view = pageView ?: return
        if (!pageShown) return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        pageParams?.let {
            it.alpha = 0f
            runCatching { windowManager.updateViewLayout(view, it) }
        }
        view.visibility = View.INVISIBLE
        pageHiddenForCapture = true
    }

    /** 截完把页面放回来（配合 [hidePageTemporarily]）。 */
    private fun restorePageAfterCapture() {
        if (!pageHiddenForCapture) return
        pageHiddenForCapture = false
        val view = pageView ?: return
        view.visibility = View.VISIBLE
        pageParams?.let {
            it.alpha = pageAlphaNow() / 100f
            runCatching { windowManager.updateViewLayout(view, it) }
        }
        // 标签内容按当前状态重上一次（截图/提取结果已经写进 answerBuf）
        tabBarRefresh?.invoke()
    }

    private fun submit() {
        val question = input?.text?.toString()?.trim().orEmpty()
        ask(question)
    }

    /** 发搜题：答案流式进答案区；搜到记进 [SolveHistory]。收起页面不影响在飞的这次搜题。 */
    private fun ask(question: String) {
        if (question.isBlank() && shot == null) {
            Toast.makeText(this, "输入题目，或先截屏搜题", Toast.LENGTH_SHORT).show()
            return
        }
        client.cancel()
        asking = true
        sessionActive = true
        // 锁住这一次的缓冲区：答案流式回来时用户可能已经切了标签，
        // 不能让它写到新标签那一份里去（answerBuf 是按 pageTab 取的）
        val sink = answerBuf
        sink.setLength(0)
        askingBuf = sink
        askingTab = pageTab
        answerText?.text = "…"
        val shotForAsk = shot
        val sent = client.ask(
            question, shotForAsk,
            onDelta = { delta ->
                sink.append(delta)
                // 节流：每段增量都全文重排 Markdown 的话，答案几千字时会越刷越卡。
                // 攒 FLUSH_MS 刷一次；页面收起时 answerText 为 null，答案继续攒在 answerBuf，重开整段恢复。
                scheduleAnswerFlush()
            },
            onDone = { result, error ->
                asking = false
                flushAnswerNow()
                when {
                    error != null -> answerText?.text = "搜题失败：" + error.take(40)
                    result.isNullOrEmpty() -> answerText?.text = "没有收到答案"
                    else -> SolveHistory.record(this, question, result, shotForAsk)
                }
            },
        )
        if (!sent) {
            asking = false
            answerText?.text = "先在「键盘设置 → 云端大模型」里填好接口和密钥"
        }
    }

    // —— 截屏搜题 ——

    /**
     * 菜单「截屏搜题」：先藏球与菜单（别把自己截进图里）→ 复用挂着的投影静默截一帧
     * （没授权过才走一次授权页）→ 截图挂进搜题页（有页面就复用，没有就新开），
     * **不自动发**——用户在页面上点「搜题」才开始。
     */
    private fun startShotSearch() {
        hideBall()
        // 页面开着的话先收起（连球一起藏）：否则会把搜题页和球截进图里
        if (pageShown) minimizePage(showBall = false)
        SolveShot.imeHeight = 0
        SolveShot.onResult = { bitmap ->
            main.removeCallbacks(captureFailsafe)
            showPage(bitmap)
        }
        if (SolveCaptureService.ready) {
            startForegroundService(Intent(this, SolveCaptureService::class.java))
        } else {
            startActivity(
                Intent(this, SolveCaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        main.postDelayed(captureFailsafe, 10_000)
    }

    // —— 球 ——

    /** 品牌绿小球：拖动跟手、松手吸边记位置；点击弹菜单；**长按直接关闭**。透明度跟「球透明度」设置走。 */
    private inner class BallView : View(this@FloatingBallService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        /** 收起态画箭头用的路径（复用，别每帧新开）。 */
        private val path = android.graphics.Path()

        /** 球当前该用的透明度（1–100）：拖动中的预览值优先（[QingjianInputMethodService.current] 同进程直取），否则读设置。 */
        fun opacityNow(): Int {
            val ime = app.qingjian.inputmethod.service.QingjianInputMethodService.current
            return ime?.effectiveBallOpacity()
                ?: Settings.preferences(context).getInt(Settings.KEY_BALL_OPACITY, 100).coerceIn(1, 100)
        }

        /**
         * 收起态：闲置到点之后，球缩成贴着屏幕边的一个小箭头（半圆），少挡视线。
         * 点一下恢复成球；拖动时不收起。
         */
        private var collapsed = false

        /** 闲置计时的秒数；0 表示不自动收起。 */
        private fun idleSeconds(): Int = prefs().getInt(KEY_BALL_IDLE, DEFAULT_BALL_IDLE)

        /** 到点收起：贴哪边就往里指（左边贴 → 箭头朝右）。 */
        private val collapseRunnable = Runnable {
            if (ballShown && !collapsed) {
                collapsed = true
                applyCollapsedLayout()
                invalidate()
            }
        }

        /** 有动静就重新开始计时（按下、拖动、点开菜单都算）。 */
        fun armCollapse() {
            removeCallbacks(collapseRunnable)
            val seconds = idleSeconds()
            if (seconds > 0) postDelayed(collapseRunnable, seconds * 1000L)
            if (collapsed) {
                collapsed = false
                applyCollapsedLayout()
                invalidate()
            }
        }

        /** 撤掉闲置计时（球要被移除、或服务要停了）。 */
        fun stopCollapse() {
            removeCallbacks(collapseRunnable)
        }

        /** 收起 / 展开时换窗口宽度（球是 38dp 圆，收起是 14dp 宽的半圆箭头）。 */
        private fun applyCollapsedLayout() {
            val params = ballParams ?: return
            val side = params.x + width / 2f < screenW() / 2f
            val newWidth = dp(if (collapsed) COLLAPSED_DP else BALL_DP).toInt()
            // 收起时贴着边；展开时保持原来的边
            params.width = newWidth
            params.x = when {
                collapsed && side -> 0
                collapsed -> screenW() - newWidth
                side -> 0
                else -> screenW() - newWidth
            }
            runCatching { windowManager.updateViewLayout(this, params) }
        }

        private var downRaw = 0f to 0f
        private var start = 0 to 0
        private var moved = false
        private var longPressed = false

        /** 按住超过系统长按时长还没拖动：直接关闭悬浮球。 */
        private val longPressClose = Runnable {
            longPressed = true
            Haptics.tap(context)
            FloatingBall.setEnabled(context, false)
        }

        override fun onDraw(canvas: Canvas) {
            val colors = AppColors.of(context)
            val alpha = 255 * opacityNow() / 100
            paint.style = Paint.Style.FILL
            paint.color = colors.brand
            paint.alpha = alpha
            if (collapsed) {
                drawCollapsed(canvas, colors, alpha)
                return
            }
            val radius = width / 2f
            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
            PanelIcons.draw(canvas, Icon.BALL, width / 2f, height / 2f, dp(20f), paint.apply {
                style = Paint.Style.STROKE
                color = colors.onBrand
                this.alpha = alpha
            })
        }

        /**
         * 收起态：贴着屏幕边的一个小三角箭头（指向屏幕里）。
         * 左边贴 → 箭头朝右 `▶`；右边贴 → 朝左 `◀`。用路径画，不用字形，免得字体差异。
         */
        private fun drawCollapsed(canvas: Canvas, colors: AppColors, alpha: Int) {
            val onLeft = (ballParams?.x ?: 0) + width / 2f < screenW() / 2f
            val h = height / 2f
            val w = width.toFloat()
            val pad = w * 0.22f
            path.rewind()
            if (onLeft) {
                // 左贴：底边贴屏幕左边，尖端朝右
                path.moveTo(pad, h - h * 0.62f)
                path.lineTo(w - pad, h)
                path.lineTo(pad, h + h * 0.62f)
            } else {
                path.moveTo(w - pad, h - h * 0.62f)
                path.lineTo(pad, h)
                path.lineTo(w - pad, h + h * 0.62f)
            }
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = colors.brand
            paint.alpha = alpha
            canvas.drawPath(path, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val params = ballParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 有动静就把"闲置收起"的计时往后推
                    removeCallbacks(collapseRunnable)
                    downRaw = event.rawX to event.rawY
                    start = params.x to params.y
                    moved = false
                    longPressed = false
                    postDelayed(longPressClose, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRaw.first
                    val dy = event.rawY - downRaw.second
                    if (!moved && (abs(dx) > dp(8f) || abs(dy) > dp(8f))) {
                        moved = true
                        removeCallbacks(longPressClose)   // 拖起来就取消长按关闭
                        // 拖球时先展开（收起态是个窄条，拖起来别扭）
                        if (collapsed) {
                            collapsed = false
                            applyCollapsedLayout()
                        }
                    }
                    if (moved) {
                        params.x = (start.first + dx).toInt().coerceIn(0, screenW() - width)
                        params.y = (start.second + dy).toInt().coerceIn(0, screenH() - height)
                        runCatching { windowManager.updateViewLayout(this@BallView, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPressClose)
                    when {
                        longPressed -> Unit                        // 长按已经把球关掉了
                        event.actionMasked == MotionEvent.ACTION_CANCEL -> Unit
                        moved -> {
                            // 吸最近的边
                            val center = params.x + width / 2f
                            params.x = if (center < screenW() / 2f) 0 else screenW() - width
                            runCatching { windowManager.updateViewLayout(this@BallView, params) }
                            FloatingBall.savePosition(context, params.x, params.y)
                            // 拖完重新开始闲置计时
                            armCollapse()
                        }
                        // 收起态点一下：展开回球，不弹菜单
                        collapsed -> {
                            collapsed = false
                            applyCollapsedLayout()
                            invalidate()
                            armCollapse()
                        }
                        else -> {
                            // 点球直接开搜题页（不再弹菜单；四个功能做成了页内标签）
                            showPage(null)
                            armCollapse()
                        }
                    }
                }
            }
            return true
        }
    }

    companion object {
        private const val CHANNEL = "floating-ball"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "app.qingjian.inputmethod.solve.FLOATING_BALL_STOP"
        const val ACTION_OPACITY_CHANGED = "app.qingjian.inputmethod.solve.BALL_OPACITY_CHANGED"
        const val ACTION_IDLE_CHANGED = "app.qingjian.inputmethod.solve.BALL_IDLE_CHANGED"

        /** 搜题页不透明度下限：再淡就看不清字了。 */
        private const val MIN_PAGE_ALPHA = 30
        private const val KEY_PAGE_SCALE = "page_scale"
        private const val KEY_PAGE_ALPHA = "page_alpha"

        /** 球闲置多久缩成贴边小箭头（秒）；0 = 不收起。存独立 prefs，见 BallView.idleSeconds。 */
        const val KEY_BALL_IDLE = "ball_idle_seconds"
        const val DEFAULT_BALL_IDLE = 5
        val BALL_IDLE_CHOICES = intArrayOf(0, 3, 5, 10, 30)

        /** 球的直径与收起后小箭头的宽（dp）。 */
        private const val BALL_DP = 38f
        private const val COLLAPSED_DP = 16f

        /** 答案流式刷新的最小间隔：攒这么久才全文重排一次 Markdown（与键盘搜题条的 FLUSH_MS 一致）。 */
        private const val ANSWER_FLUSH_MS = 200L

        /** 页面标签：搜题 / 截屏搜题 / 提取文字 / 语音问答（原来在球的弹出菜单里，现在做进页面）。 */
        const val TAB_ASK = 0
        const val TAB_SHOT = 1
        const val TAB_EXTRACT = 2
        const val TAB_VOICE = 3
        val TAB_KEYS = intArrayOf(TAB_ASK, TAB_SHOT, TAB_EXTRACT, TAB_VOICE)
        val TAB_TITLES = arrayOf("搜题", "截屏搜题", "提取文字", "语音问答")

        /** 没有识别方时的提示（浮层与 toast 共用一句）。 */
        const val VOICE_UNAVAILABLE = "没有可用的语音识别：去「我的 → 语音识别」配一个（火山引擎）"
    }
}
