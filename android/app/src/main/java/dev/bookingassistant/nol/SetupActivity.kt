package dev.bookingassistant.nol

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Input screen: server clock, event page, opening time, keywords. */
class SetupActivity : Activity(), CoroutineScope by MainScope() {

    private lateinit var settings: Settings
    private lateinit var palette: Palette
    private lateinit var clock: TextView
    private lateinit var clockSub: TextView
    private lateinit var url: EditText
    private lateinit var date: EditText
    private lateinit var time: EditText
    private lateinit var keywords: EditText
    private lateinit var labels: EditText
    private lateinit var reloadAfter: EditText
    private lateinit var updateStatus: TextView
    private lateinit var updater: Updater
    private var offset: Offset? = null
    private var measuring: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        palette = Palette(isNight())
        setContentView(build())
        updater = Updater(this, this) { updateStatus.text = it }
        updateStatus.text = updater.status
        measure()
        launch {
            val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(KST)
            while (isActive) {
                offset?.let { clock.text = fmt.format(Instant.ofEpochMilli((it.serverNow() * 1000).toLong())) }
                delay(50)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
    }

    override fun onPause() {
        super.onPause()
        updater.onPause()
        save()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Updater.ACTION_INSTALL_STATUS) updater.onInstallStatus(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }

    private fun build(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(small("오픈 시각에 맞춰 예매 버튼을 정확히 눌러 줍니다. 대기열·좌석·결제는 직접 진행합니다."))

        // Server clock
        col.addView(label("NOL 서버 시각", top = 20))
        val clockRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        clock = TextView(this).apply {
            text = "--:--:--.---"
            textSize = 30f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.accent)
        }
        clockRow.addView(clock, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        clockRow.addView(Button(this).apply {
            text = "다시 측정"
            setOnClickListener { measure() }
        })
        col.addView(clockRow)
        clockSub = small("측정 전")
        col.addView(clockSub)

        url = field(col, "공연 페이지 주소", settings.get("url", ""), "https://tickets.interpark.com/goods/…",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        val row = LinearLayout(this)
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        col.addView(row)
        date = field(left, "오픈 날짜", settings.get("date", LocalDate.now(KST).toString()), "20261020",
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE)
        time = field(right, "오픈 시각", settings.get("time", "20:00:00"), "200000",
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        date.normalizeOnBlur { normalizeDate(it) }
        time.normalizeOnBlur { normalizeTime(it) }

        keywords = field(col, "경기/회차 키워드 (예매 버튼이 여러 개일 때)", settings.get("keywords", ""), "예: 3차전  또는  10월 21일",
            InputType.TYPE_CLASS_TEXT)
        col.addView(small("여러 단어는 모두 포함된 카드를 고릅니다."))

        labels = field(col, "예매 버튼 문구 (쉼표 구분)", settings.get("labels", "예매하기, 예매 하기"), "",
            InputType.TYPE_CLASS_TEXT)
        reloadAfter = field(col, "안 열리면 새로고침 (초)", settings.get("reload_after", "2.0"), "2.0",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)

        col.addView(Button(this).apply {
            text = "시작  ·  브라우저 열기"
            textSize = 16f
            setOnClickListener { start() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(24) })
        col.addView(Button(this).apply {
            text = "브라우저 열기  ·  로그인, 주소 가져오기"
            setOnClickListener { openBrowser(null) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        col.addView(small(
            "1. 오픈 5분 전에 시작을 누릅니다.\n" +
                "2. 열린 화면에서 로그인하고 날짜/회차를 골라 둡니다.\n" +
                "3. 버튼이 열리는 순간 자동으로 누르고 소리와 진동으로 알려 줍니다.\n" +
                "   화면이 켜진 채로 두세요."
        ).apply { setPadding(0, dp(12), 0, 0) })

        val updateRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, 0)
        }
        updateStatus = small("")
        updateRow.addView(updateStatus, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        updateRow.addView(Button(this).apply {
            text = "업데이트 확인"
            textSize = 12f
            setOnClickListener { updater.check() }
        })
        col.addView(updateRow)

        return ScrollView(this).apply {
            addView(col)
            padForSystemBars()
        }
    }

    private fun label(text: String, top: Int = 14) = TextView(this).apply {
        this.text = text
        typeface = Typeface.DEFAULT_BOLD
        textSize = 13f
        setPadding(0, dp(top), 0, dp(2))
    }

    private fun small(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(palette.muted)
    }

    private fun field(parent: LinearLayout, title: String, value: String, hint: String, type: Int): EditText {
        parent.addView(label(title))
        return EditText(this).apply {
            setText(value)
            this.hint = hint
            inputType = type
            isSingleLine = true
            parent.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    /** `20261020` becomes `2026-10-20` once the field loses focus, like the PC version. */
    private fun EditText.normalizeOnBlur(normalize: (String) -> String?) {
        setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) normalize(text.toString())?.let { if (it != text.toString()) setText(it) }
        }
    }

    private fun measure() {
        if (measuring?.isActive == true) return
        clockSub.text = "서버 시간 측정 중…"
        clockSub.setTextColor(palette.muted)
        measuring = launch {
            try {
                val off = withContext(Dispatchers.IO) { measureBest() }
                offset = off
                clockSub.text = "오프셋 %+.0f ms (서버 - 폰), 오차 ±%.0f ms, RTT %.0f ms".format(
                    off.seconds * 1000, off.error * 1000, off.rtt * 1000)
            } catch (e: Exception) {
                clockSub.text = "측정 실패: ${e.message} — 인터넷 연결을 확인하고 다시 측정하세요."
                clockSub.setTextColor(palette.danger)
            }
        }
    }

    private fun save() {
        settings.save(mapOf(
            "url" to url.text.toString().trim(),
            "date" to (normalizeDate(date.text.toString()) ?: date.text.toString()),
            "time" to (normalizeTime(time.text.toString()) ?: time.text.toString()),
            "keywords" to keywords.text.toString().trim(),
            "labels" to labels.text.toString(),
            "reload_after" to reloadAfter.text.toString(),
        ))
    }

    private fun pageUrl(): String? {
        val raw = url.text.toString().trim()
        if (raw.isEmpty()) return null
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    private fun fail(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("확인", null).show()
    }

    private fun openBrowser(config: Booker.Config?) {
        val intent = Intent(this, BookingActivity::class.java)
            .putExtra(BookingActivity.EXTRA_URL, config?.url ?: pageUrl() ?: "https://tickets.interpark.com/")
        if (config != null) {
            intent.putExtra(BookingActivity.EXTRA_OPEN_AT, config.openAt)
                .putExtra(BookingActivity.EXTRA_LABELS, config.labels.toTypedArray())
                .putExtra(BookingActivity.EXTRA_KEYWORDS, config.keywords.toTypedArray())
                .putExtra(BookingActivity.EXTRA_RELOAD_AFTER, config.reloadAfter)
        }
        save()
        if (config == null) startActivityForResult(intent, PICK_URL) else startActivity(intent)
    }

    /** The browser's "이 주소로 설정" sends back the page it was showing. */
    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val picked = data?.getStringExtra(BookingActivity.EXTRA_URL)
        if (requestCode == PICK_URL && resultCode == RESULT_OK && picked != null) {
            url.setText(picked)
            save()
        }
    }

    companion object {
        private const val PICK_URL = 1
    }

    private fun start() {
        currentFocus?.clearFocus()
        val page = pageUrl() ?: return fail("공연 페이지 주소를 입력하세요.")
        val openAt = parseOpenAt(date.text.toString(), time.text.toString())
            ?: return fail("오픈 날짜/시각을 확인하세요. 예: 20261020, 200000")
        val now = offset?.serverNow() ?: localNow()
        if (openAt <= now) return fail("오픈 시각이 이미 지났습니다.")
        val labelList = labels.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("예매하기", "예매 하기") }
        val keywordList = keywords.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val reload = reloadAfter.text.toString().toDoubleOrNull()?.takeIf { it > 0 }
            ?: return fail("새로고침 간격은 0보다 큰 숫자로 입력하세요.")
        openBrowser(Booker.Config(page, openAt, labelList, keywordList, reloadAfter = reload))
    }
}
