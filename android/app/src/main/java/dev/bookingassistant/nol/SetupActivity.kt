package dev.bookingassistant.nol

import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.ZoneOffset
import java.time.LocalTime
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
class SetupActivity : AppCompatActivity(), CoroutineScope by MainScope() {

    private lateinit var settings: Settings
    private lateinit var palette: Palette
    private lateinit var clock: TextView
    private lateinit var countdown: TextView
    private lateinit var target: TextView
    private lateinit var clockSub: TextView
    private lateinit var url: EditText
    private lateinit var date: EditText
    private lateinit var time: EditText
    private lateinit var keywords: EditText
    private lateinit var labels: EditText
    private lateinit var reloadAfter: EditText
    private lateinit var early: MaterialCheckBox
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
                val now = offset?.serverNow()
                if (now != null) clock.text = fmt.format(Instant.ofEpochMilli((now * 1000).toLong()))
                val opening = parseOpenAt(date.text.toString(), time.text.toString())
                val left = if (now != null && opening != null) opening - now else null
                countdown.text = when {
                    opening == null -> "날짜와 시각을 확인하세요"
                    now == null -> "서버 시각 측정 중…"
                    left != null && left > 0 -> "%02d:%02d:%02d".format(left.toInt() / 3600, left.toInt() / 60 % 60, left.toInt() % 60)
                    else -> "오픈 시각이 지났습니다"
                }
                countdown.textSize = if (left != null && left > 0) 32f else 18f
                countdown.setTextColor(if (left != null && left > 0) palette.accent else palette.muted)
                target.text = if (opening != null) DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm:ss", java.util.Locale.KOREAN).withZone(KST)
                    .format(Instant.ofEpochMilli((opening * 1000).toLong())) else "직접 입력하거나 달력과 시계를 눌러 주세요"
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
            text = "예매 도우미"
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(palette.logFg)
        })
        col.addView(small("NOL 티켓 · 정각에 버튼까지, 그다음은 직접").apply { setPadding(0, dp(4), 0, dp(16)) })
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(palette.surface)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        hero.addView(small("오픈까지 남은 시간"))
        countdown = TextView(this).apply {
            text = "서버 시각 측정 중…"
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            setPadding(0, dp(4), 0, 0)
        }
        hero.addView(countdown)
        target = small("").apply { setPadding(0, dp(4), 0, dp(12)) }
        hero.addView(target)
        val clockRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val clockCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockCol.addView(small("NOL 서버 시각"))
        clock = TextView(this).apply {
            text = "--:--:--.---"
            textSize = 18f
            fontFeatureSettings = "tnum"
            setTextColor(palette.logFg)
        }
        clockCol.addView(clock)
        clockRow.addView(clockCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        clockRow.addView(action("다시 측정", palette, "text").apply { setOnClickListener { measure() } })
        hero.addView(clockRow)
        clockSub = small("측정 전").apply { setPadding(0, dp(8), 0, 0) }
        hero.addView(clockSub)
        col.addView(hero)

        url = field(col, "공연 페이지 주소", settings.get("url", ""), "https://tickets.interpark.com/goods/…",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val row = LinearLayout(this)
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        row.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        col.addView(row)
        date = field(left, "오픈 날짜", settings.get("date", LocalDate.now(KST).toString()), "20261020 · 1020도 가능",
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE)
        time = field(right, "오픈 시각", settings.get("time", "20:00:00"), "200000 · 2000도 가능",
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        date.normalizeOnBlur { normalizeDate(it) }
        time.normalizeOnBlur { normalizeTime(it) }
        (date.parent.parent as TextInputLayout).apply {
            endIconMode = TextInputLayout.END_ICON_CUSTOM
            setEndIconDrawable(R.drawable.ic_calendar)
            endIconContentDescription = "날짜 선택"
            setEndIconOnClickListener { pickDate() }
        }
        (time.parent.parent as TextInputLayout).apply {
            endIconMode = TextInputLayout.END_ICON_CUSTOM
            setEndIconDrawable(R.drawable.ic_clock)
            endIconContentDescription = "시각 선택"
            setEndIconOnClickListener { pickTime() }
        }
        keywords = field(col, "경기 / 회차 키워드", settings.get("keywords", ""), "예: 3차전 · 여러 단어가 모두 포함된 카드를 고릅니다.", InputType.TYPE_CLASS_TEXT)
        early = MaterialCheckBox(this).apply {
            text = "미리 누르기"
            isChecked = settings.get("early_press", "false") == "true"
            setTextColor(palette.logFg)
        }
        col.addView(early, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(small("요청이 서버에 닿는 시간만큼 일찍 눌러 정각에 도착하게 합니다 (최대 150 ms)").apply {
            setPadding(dp(4), 0, 0, 0)
        })
        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val expand = action("고급 설정  ▾", palette, "text")
        expand.setOnClickListener {
            advanced.visibility = if (advanced.visibility == View.GONE) View.VISIBLE else View.GONE
            expand.text = if (advanced.visibility == View.VISIBLE) "고급 설정  ▴" else "고급 설정  ▾"
        }
        col.addView(expand, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        labels = field(advanced, "예매 버튼 문구", settings.get("labels", "예매하기, 예매 하기"), "쉼표로 구분합니다.", InputType.TYPE_CLASS_TEXT)
        reloadAfter = field(advanced, "새로고침 간격 (초)", settings.get("reload_after", "2.0"), "버튼이 열리지 않을 때 다시 불러옵니다.", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        col.addView(advanced)
        col.addView(action("시작", palette).apply {
            textSize = 16f
            setOnClickListener { start() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(8) })
        col.addView(action("브라우저 열기 · 로그인 / 주소 가져오기", palette, "outlined").apply {
            setOnClickListener { openBrowser(null) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(4) })
        col.addView(small("오픈 5분 전에 시작하고, 로그인과 날짜/회차 선택을 마쳐 주세요.\n화면을 켜 두세요. 대기열·좌석·결제는 직접 진행합니다.").apply {
            setPadding(0, dp(12), 0, dp(8)); setLineSpacing(dp(4).toFloat(), 1f)
        })
        val updateRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        updateStatus = small("")
        updateRow.addView(updateStatus, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        updateRow.addView(action("업데이트 확인", palette, "text").apply {
            textSize = 12f
            setOnClickListener { updater.check() }
        })
        col.addView(updateRow)
        return ScrollView(this).apply {
            setBackgroundColor(palette.bg)
            isFillViewport = true
            addView(col)
            padForSystemBars()
        }
    }

    private fun small(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(palette.muted)
    }

    private fun field(parent: LinearLayout, title: String, value: String, hint: String, type: Int): EditText {
        val layout = TextInputLayout(android.view.ContextThemeWrapper(this,
            com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox)).apply {
            this.hint = title
            helperText = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
        }
        val edit = TextInputEditText(layout.context).apply {
            inputType = type
            setText(value)
            textSize = 14f
            isSingleLine = true
            fontFeatureSettings = "tnum"
            setTextColor(palette.logFg)
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        layout.addView(edit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        parent.addView(layout, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        return edit
    }

    private fun pickDate() {
        val selected = runCatching { LocalDate.parse(normalizeDate(date.text.toString())) }.getOrNull() ?: LocalDate.now(KST)
        val picker = MaterialDatePicker.Builder.datePicker().setTitleText("오픈 날짜")
            .setSelection(selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()).build()
        picker.addOnPositiveButtonClickListener { date.setText(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
        picker.show(supportFragmentManager, "open-date")
    }

    private fun pickTime() {
        val selected = runCatching { LocalTime.parse(normalizeTime(time.text.toString())) }.getOrNull() ?: LocalTime.of(20, 0)
        val picker = MaterialTimePicker.Builder().setTitleText("오픈 시각")
            .setTimeFormat(TimeFormat.CLOCK_24H).setHour(selected.hour).setMinute(selected.minute).build()
        picker.addOnPositiveButtonClickListener { time.setText("%02d:%02d:%02d".format(picker.hour, picker.minute, selected.second)) }
        picker.show(supportFragmentManager, "open-time")
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
                val off = withContext(Dispatchers.IO) { measureServers(timeUrls(url.text.toString())) }
                offset = off
                clockSub.text = ("오프셋 %+.0f ms (서버 - 폰), 오차 ±%.0f ms · %s\n" +
                    "연결: %s · 왕복 %.0f ms (%s)\n" +
                    "와이파이와 모바일 데이터를 바꿔 다시 측정해 보고, 왕복이 짧은 쪽으로 예매하세요.").format(
                    off.seconds * 1000, off.error * 1000, off.host, networkName(), off.rtt * 1000, off.speed())
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
            "early_press" to early.isChecked.toString(),
        ))
    }

    private fun pageUrl(): String? {
        val raw = url.text.toString().trim()
        if (raw.isEmpty()) return null
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    private fun fail(message: String) {
        MaterialAlertDialogBuilder(this).setMessage(message).setPositiveButton("확인", null).show()
    }

    private fun openBrowser(config: Booker.Config?) {
        val intent = Intent(this, BookingActivity::class.java)
            .putExtra(BookingActivity.EXTRA_URL, config?.url ?: pageUrl() ?: "https://tickets.interpark.com/")
        if (config != null) {
            intent.putExtra(BookingActivity.EXTRA_OPEN_AT, config.openAt)
                .putExtra(BookingActivity.EXTRA_LABELS, config.labels.toTypedArray())
                .putExtra(BookingActivity.EXTRA_KEYWORDS, config.keywords.toTypedArray())
                .putExtra(BookingActivity.EXTRA_RELOAD_AFTER, config.reloadAfter)
                .putExtra(BookingActivity.EXTRA_EARLY, config.early)
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
        openBrowser(Booker.Config(page, openAt, labelList, keywordList, reloadAfter = reload, early = early.isChecked))
    }
}
