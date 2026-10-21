package dev.bookingassistant.nol

import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.text.TextUtils
import android.widget.ImageButton
import com.google.android.material.button.MaterialButton
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The event page in our own WebView, with a status bar on top.
 *
 * Without EXTRA_OPEN_AT it is just a browser (to log in ahead of time).
 */
class BookingActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_OPEN_AT = "open_at"
        const val EXTRA_LABELS = "labels"
        const val EXTRA_KEYWORDS = "keywords"
        const val EXTRA_RELOAD_AFTER = "reload_after"
        const val EXTRA_EARLY = "early"
    }

    private lateinit var palette: Palette
    private lateinit var web: WebView
    private lateinit var webFrame: FrameLayout
    private lateinit var clock: TextView
    private lateinit var clockSub: TextView
    private lateinit var chip: TextView
    private lateinit var lastLine: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var actionBtn: MaterialButton
    private lateinit var bar: LinearLayout
    private lateinit var banner: TextView
    private lateinit var disclosure: ImageButton
    private var statusKey = "idle"
    private var stopped = false
    private var bannerShown = false
    private val popups = mutableListOf<WebView>()   // windows the page opened (window.open)
    private var booker: Booker? = null
    private var job: Job? = null
    private var offset: Offset? = null
    private var openAt: Double? = null
    // This run's log on disk (files/logs), so a run can be looked at afterwards.
    private var logFile: File? = null
    private val stampFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(KST)

    override fun onCreate(savedInstanceState: Bundle?) {
        palette = applyTheme()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        actionBar?.hide()
        setContentView(build())
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }

        val url = intent.getStringExtra(EXTRA_URL) ?: "https://tickets.interpark.com/"
        web.loadUrl(url)

        if (intent.hasExtra(EXTRA_OPEN_AT)) {
            val config = Booker.Config(
                url = url,
                openAt = intent.getDoubleExtra(EXTRA_OPEN_AT, 0.0),
                labels = intent.getStringArrayExtra(EXTRA_LABELS)?.toList() ?: listOf("예매하기", "예매 하기"),
                keywords = intent.getStringArrayExtra(EXTRA_KEYWORDS)?.toList().orEmpty(),
                reloadAfter = intent.getDoubleExtra(EXTRA_RELOAD_AFTER, 2.0),
                early = intent.getBooleanExtra(EXTRA_EARLY, false),
            )
            openAt = config.openAt
            logFile = RunLogs.start(this, url, config.openAt)
            booker = Booker(web, config, ::log, { popups.size }, ::trace, ::cookieNames, ::warn)
            job = launch { book(config) }
        } else {
            showStatus("idle")
            actionBtn.text = "이 주소로 설정"
            actionBtn.setOnClickListener { pickCurrentUrl() }
            log("공연 페이지로 가서 '이 주소로 설정'을 누르면 주소 칸에 들어갑니다. 로그인도 여기서 해 두면 유지됩니다.")
            launch { offset = runCatching { withContext(Dispatchers.IO) { measureServers(timeUrls(url)) } }.getOrNull() }
        }
        launch { tickClock() }
    }

    private suspend fun book(config: Booker.Config) {
        val booker = booker ?: return
        showStatus("busy")
        try {
            val urls = timeUrls(config.url)
            log("서버 시간 측정 중: " + urls.joinToString(", ") { Offset(0.0, 0.0, 0.0, it).host })
            val off = withContext(Dispatchers.IO) { measureServers(urls) }
            offset = off
            log("  기준 서버 %s · 오프셋 %+.0f ms, 오차 ±%.0f ms, 왕복 %.0f ms (%s · %s)".format(
                off.host, off.seconds * 1000, off.error * 1000, off.rtt * 1000, networkName(), off.speed()))
            if (config.openAt <= off.serverNow()) {
                showStatus("fail")
                log("오픈 시각이 이미 지났습니다.")
                return
            }
            log("브라우저가 열렸습니다. 로그인이 안 되어 있다면 지금 로그인하고, 날짜/회차를 미리 골라두세요.")
            val outcome = booker.run(off)
            showStatus(if (outcome == Booker.Outcome.CLICKED) "done" else "fail")
            alert()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showStatus("fail")
            log("오류: ${e.message}")
            alert()
        } finally {
            booker.disarm()
            actionBtn.isEnabled = false
        }
    }

    private suspend fun tickClock() {
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(KST)
        while (isActive) {
            val off = offset
            if (off != null) {
                val now = off.serverNow()
                val left = openAt?.let { it - now }
                clock.text = fmt.format(Instant.ofEpochMilli((now * 1000).toLong()))
                clockSub.text = if (left != null && left > 0) "서버 시각 · 오픈까지 %d:%02d".format(left.toInt() / 60, left.toInt() % 60)
                else "서버 시각 · " + off.host
            }
            delay(50)
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        logFile?.let { RunLogs.finish(it, runResult()) }
        cancel()
        popups.forEach { it.destroy() }
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Used below Android 13")
    override fun onBackPressed() {
        goBack()
    }

    private fun goBack() {
        val top = popups.lastOrNull()
        when {
            top != null && top.canGoBack() -> top.goBack()
            top != null -> closePopup(top)
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    // ---- UI ----

    private fun build(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(6))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val clocks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = text("--:--:--.---", palette, 18f, palette.text, 700).apply { fontFeatureSettings = "tnum" }
        clocks.add(clock)
        clockSub = text("서버 시각", palette, 12f, palette.muted).apply { fontFeatureSettings = "tnum" }
        clocks.add(clockSub, top = 4)
        top.addView(clocks, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        chip = TextView(this)
        top.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        actionBtn = action("중지", palette, "danger").apply {
            textSize = 14f
            minWidth = dp(64)
            minimumWidth = dp(64)
            cornerRadius = dp(12)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { stop() }
        }
        top.addView(actionBtn, LinearLayout.LayoutParams(WRAP_CONTENT, dp(42)))
        bar.addView(top)

        val logRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(36)
            isClickable = true
            isFocusable = true
            contentDescription = "진행 기록 펼치기"
            setOnClickListener { toggleLog() }
        }
        lastLine = text("", palette, 12.5f, palette.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        logRow.addView(lastLine, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        disclosure = ImageButton(this).apply {
            setImageResource(R.drawable.ic_expand)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = "진행 기록 펼치기"
            setOnClickListener { toggleLog() }
        }
        logRow.addView(disclosure, LinearLayout.LayoutParams(dp(44), dp(36)))
        bar.addView(logRow)
        logView = text("", palette, 13f, palette.text).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(logView)
        }
        bar.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, minOf(dp(200), (resources.displayMetrics.heightPixels * 0.25).toInt())).apply {
            bottomMargin = dp(6)
        })
        root.addView(bar)
        banner = text("", palette, 14f, palette.text, 600).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        root.addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        webFrame = FrameLayout(this)
        web = WebView(this)
        configure(web)
        webFrame.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(webFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.padForSystemBars()
        paint(root)
        return root
    }

    /** Theme colors for the bar; again when the phone switches light/dark (theme not chosen). */
    private fun paint(root: View = bar.parent as View) {
        root.setBackgroundColor(palette.surface)
        bar.setBackgroundColor(palette.surface)
        clock.setTextColor(palette.text)
        clockSub.setTextColor(palette.muted)
        lastLine.setTextColor(palette.muted)
        logView.setTextColor(palette.text)
        logScroll.background = rounded(palette.tile, 12)
        disclosure.imageTintList = ColorStateList.valueOf(palette.muted)
        chip.chip(palette, statusKey)
        val (bg, fg) = if (intent.hasExtra(EXTRA_OPEN_AT)) palette.dangerBg to palette.danger else palette.accentSoft to palette.accent
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        actionBtn.backgroundTintList = ColorStateList(states, intArrayOf(palette.chip, bg))
        actionBtn.setTextColor(ColorStateList(states, intArrayOf(palette.faint, fg)))
        if (statusKey == "done" || statusKey == "fail") {
            val (bannerBg, bannerFg) = palette.status(statusKey)
            banner.setBackgroundColor(bannerBg)
            banner.setTextColor(bannerFg)
        }
        paintSystemBars(palette)
    }

    private fun showStatus(key: String) {
        statusKey = key
        chip.chip(palette, key)
        if ((key == "done" || key == "fail") && !bannerShown) {
            bannerShown = true
            val (bg, fg) = palette.status(key)
            banner.setBackgroundColor(bg)
            banner.setTextColor(fg)
            banner.text = if (key == "done") "✓  클릭 완료 · 대기열 / 좌석 / 결제는 직접 진행하세요" else "!  확인 필요 · 기록과 예매 화면을 확인하세요"
            banner.visibility = View.VISIBLE
            banner.postDelayed({ banner.visibility = View.GONE }, 8000)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val name = themeName()
        if (name != palette.name) {
            palette = Palette(name)
            paint()
        }
    }

    private fun toggleLog() {
        val expanded = logScroll.visibility != View.VISIBLE
        logScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        disclosure.rotation = if (expanded) 180f else 0f
        disclosure.contentDescription = if (expanded) "진행 기록 접기" else "진행 기록 펼치기"
    }

    /** How the run ended, for the saved log (see RunLogs). */
    private fun runResult() = when {
        statusKey == "done" -> "클릭 완료"
        statusKey == "fail" -> "확인 필요"
        stopped -> "중지"
        else -> "진행 중 닫음"
    }

    /** A line for the log file only; called from WebView threads too. */
    private fun trace(message: String) {
        val file = logFile ?: return
        val server = offset?.let { " (서버 " + stampFormat.format(Instant.ofEpochMilli((it.serverNow() * 1000).toLong())) + ")" } ?: ""
        synchronized(file) { runCatching { file.appendText("${stampFormat.format(Instant.now())}$server$message\n") } }
    }

    private fun cookieNames(): List<String> {
        val cm = CookieManager.getInstance()
        return (siteFor(intent.getStringExtra(EXTRA_URL).orEmpty()).cookieUrls + listOfNotNull(web.url))
            .flatMap { url -> cm.getCookie(url).orEmpty().split(';') }
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
    }

    /** Two short buzzes and beeps: something needs a look before the opening. */
    private fun warn() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            web.postDelayed({ tone.release() }, 600)
        }
        runCatching {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), -1))
        }
    }

    private fun log(message: String) {
        logFile?.let { file ->
            val now = Instant.now()
            val server = offset?.let { " (서버 " + stampFormat.format(Instant.ofEpochMilli((it.serverNow() * 1000).toLong())) + ")" } ?: ""
            synchronized(file) { runCatching { file.appendText("${stampFormat.format(now)}$server  $message\n") } }
        }
        lastLine.text = message.trim()
        logView.append(if (logView.text.isEmpty()) message else "\n$message")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun stop() {
        stopped = true
        job?.cancel()
        booker?.disarm()
        showStatus("idle")
        actionBtn.isEnabled = false
        log("중지했습니다. 화면은 그대로 쓸 수 있습니다.")
    }

    /** Hand the address of the page on screen back to the setup screen. */
    private fun pickCurrentUrl() {
        val url = (popups.lastOrNull() ?: web).url.orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            log("공연 페이지가 열린 뒤에 눌러 주세요.")
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, url))
        finish()
    }

    private fun alert() {
        runCatching {
            // 알림 볼륨을 따르고(무음·진동 모드면 울리지 않음) 소리도 줄였다. 알람 볼륨은 너무 크다.
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 400)
            web.postDelayed({ tone.release() }, 800)
        }
        runCatching {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300), -1))
        }
    }

    // ---- WebView ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            // Look like mobile Chrome: some sites refuse or redirect in-app WebViews ("; wv").
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = client
        view.webChromeClient = chrome
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith("http://") || url.startsWith("https://")) return false
            // intent://, payment and card apps, tel: … hand off to the app that handles them.
            try {
                val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                else Intent(Intent.ACTION_VIEW, request.url)
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    val pkg = intent.`package`
                    when {
                        fallback != null -> view.loadUrl(fallback)
                        pkg != null -> startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("market://details?id=$pkg")))
                    }
                }
            } catch (e: Exception) {
                log("열 수 없는 주소: ${url.take(60)}")
            }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Runs off the main thread; only notes that the page talked to a server.
            booker?.onRequest(request.url.toString(), request.requestHeaders["Accept"], request.method)
            return null
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (view === web) booker?.onPageFinished()
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
            answer(message, result)

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
            answer(message, result)

        /** While watching, accept page dialogs ourselves (like the PC version) and note what they said. */
        private fun answer(message: String, result: JsResult): Boolean {
            if (booker?.onDialog(message) != true) return false
            result.confirm()
            return true
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val child = WebView(this@BookingActivity)
            configure(child)
            webFrame.addView(child, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            popups.add(child)
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            closePopup(window)
        }
    }

    private fun closePopup(view: WebView) {
        webFrame.removeView(view)
        popups.remove(view)
        view.destroy()
    }
}
