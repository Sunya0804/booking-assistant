package dev.bookingassistant.nol

import android.annotation.SuppressLint
import android.app.Activity
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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The event page in our own WebView, with a status bar on top.
 *
 * Without EXTRA_OPEN_AT it is just a browser (to log in ahead of time).
 */
class BookingActivity : Activity(), CoroutineScope by MainScope() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_OPEN_AT = "open_at"
        const val EXTRA_LABELS = "labels"
        const val EXTRA_KEYWORDS = "keywords"
        const val EXTRA_RELOAD_AFTER = "reload_after"
    }

    private lateinit var palette: Palette
    private lateinit var web: WebView
    private lateinit var webFrame: FrameLayout
    private lateinit var clock: TextView
    private lateinit var chip: TextView
    private lateinit var lastLine: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var actionBtn: Button
    private val popups = mutableListOf<WebView>()   // windows the page opened (window.open)
    private var booker: Booker? = null
    private var job: Job? = null
    private var offset: Offset? = null
    private var openAt: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = Palette(isNight())
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
            )
            openAt = config.openAt
            booker = Booker(web, config, ::log) { popups.size }
            job = launch { book(config) }
        } else {
            chip.chip(palette, "idle")
            actionBtn.text = "이 주소로 설정"
            actionBtn.setOnClickListener { pickCurrentUrl() }
            log("공연 페이지로 가서 '이 주소로 설정'을 누르면 주소 칸에 들어갑니다. 로그인도 여기서 해 두면 유지됩니다.")
            launch { offset = runCatching { withContext(Dispatchers.IO) { measureBest() } }.getOrNull() }
        }
        launch { tickClock() }
    }

    private suspend fun book(config: Booker.Config) {
        val booker = booker ?: return
        chip.chip(palette, "busy")
        try {
            log("서버 시간 측정 중: $TIME_URL")
            val off = withContext(Dispatchers.IO) { measureBest(log = { launch { log(it) } }) }
            offset = off
            log("  오프셋 %+.0f ms, 오차 ±%.0f ms".format(off.seconds * 1000, off.error * 1000))
            if (config.openAt <= off.serverNow()) {
                chip.chip(palette, "fail")
                log("오픈 시각이 이미 지났습니다.")
                return
            }
            log("브라우저가 열렸습니다. 로그인이 안 되어 있다면 지금 로그인하고, 날짜/회차를 미리 골라두세요.")
            val outcome = booker.run(off)
            chip.chip(palette, if (outcome == Booker.Outcome.CLICKED) "done" else "fail")
            alert()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            chip.chip(palette, "fail")
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
                clock.text = fmt.format(Instant.ofEpochMilli((now * 1000).toLong())) +
                    if (left != null && left > 0) "  · 오픈까지 %d:%02d".format(left.toInt() / 60, left.toInt() % 60) else ""
            }
            delay(50)
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
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

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        clock = TextView(this).apply {
            text = "--:--:--.---"
            textSize = 17f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.accent)
        }
        chip = TextView(this).apply { textSize = 12f }
        actionBtn = Button(this).apply {
            text = "중지"
            setOnClickListener { stop() }
        }
        top.addView(clock, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        top.addView(actionBtn)
        bar.addView(top)

        lastLine = TextView(this).apply {
            textSize = 12f
            maxLines = 2
            setTextColor(palette.muted)
            setOnClickListener { toggleLog() }
        }
        bar.addView(lastLine)
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.logFg)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(palette.logBg)
            visibility = View.GONE
            addView(logView)
        }
        bar.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.3).toInt()))
        root.addView(bar)

        webFrame = FrameLayout(this)
        web = WebView(this)
        configure(web)
        webFrame.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(webFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.padForSystemBars()
        return root
    }

    private fun toggleLog() {
        logScroll.visibility = if (logScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun log(message: String) {
        lastLine.text = message.trim() + "  (눌러서 기록 보기)"
        logView.append(if (logView.text.isEmpty()) message else "\n$message")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun stop() {
        job?.cancel()
        booker?.disarm()
        chip.chip(palette, "idle")
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
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
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
