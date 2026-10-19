package dev.bookingassistant.nol

import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.ZoneOffset
import java.time.LocalTime
import android.content.Intent
import android.content.res.ColorStateList
import androidx.core.widget.doAfterTextChanged
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
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
    private lateinit var drift: TextView
    private lateinit var serverLabel: TextView
    private lateinit var siteChip: TextView
    private lateinit var speedDot: TextView
    private lateinit var rtt: TextView
    private lateinit var host: TextView
    private lateinit var networkTip: TextView
    private lateinit var keywordHelp: TextView
    private lateinit var url: EditText
    private lateinit var date: EditText
    private lateinit var time: EditText
    private lateinit var keywords: EditText
    private lateinit var labels: EditText
    private lateinit var reloadAfter: EditText
    private lateinit var early: MaterialSwitch
    private lateinit var alarmOn: MaterialSwitch
    private lateinit var alarmMinutes: EditText
    private lateinit var alarmVolume: Slider
    private lateinit var alarmStatus: TextView
    private var preview: AlarmSound? = null
    private lateinit var previewBtn: com.google.android.material.button.MaterialButton
    private lateinit var updateStatus: TextView
    private lateinit var updater: Updater
    private var offset: Offset? = null
    private var measuring: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        palette = applyTheme()
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(build())
        paintSystemBars(palette)
        updater = Updater(this, this) { updateStatus.text = it }
        updateStatus.text = updater.status
        showSite()
        measure()
        launch {
            val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(KST)
            val targetFmt = DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm:ss 오픈", java.util.Locale.KOREAN).withZone(KST)
            while (isActive) {
                val now = offset?.serverNow()
                if (now != null) clock.text = fmt.format(Instant.ofEpochMilli((now * 1000).toLong()))
                val opening = parseOpenAt(date.text.toString(), time.text.toString())
                val left = if (now != null && opening != null) opening - now else null
                if (left != null && left > 0) showCountdown(left)
                else showQuiet(when {
                    opening == null -> "날짜와 시각을 확인하세요"
                    now == null -> "서버 시각 측정 중…"
                    else -> "오픈 시각이 지났습니다"
                })
                target.text = if (opening != null) targetFmt.format(Instant.ofEpochMilli((opening * 1000).toLong()))
                else "직접 입력하거나 달력과 시계를 눌러 주세요"
                delay(50)
            }
        }
    }

    /** 1:02:03 or 02:03.4 with the tenths faint; orange in the last minute. */
    private fun showCountdown(left: Double) {
        val s = left.toInt()
        val main = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
        val tenths = if (s >= 3600) "" else ".${((left - s) * 10).toInt()}"
        countdown.text = android.text.SpannableString(main + tenths).apply {
            if (tenths.isNotEmpty()) setSpan(android.text.style.ForegroundColorSpan(palette.faint), main.length, length, 0)
        }
        countdown.textSize = 52f
        countdown.typeface = font(700)
        countdown.setTextColor(if (left < 60) palette.warn else palette.text)
    }

    private fun showQuiet(message: String) {
        if (countdown.text.toString() == message) return
        countdown.text = message
        countdown.textSize = 24f
        countdown.typeface = font(700)
        countdown.setTextColor(palette.muted)
    }

    /** NOL or 멜론티켓 from the address: the top chip, the clock label and the keyword example. */
    private fun showSite() {
        val site = siteFor(pageUrl() ?: "")
        siteChip.text = if (site === MELON_SITE) "멜론" else "NOL"
        serverLabel.text = if (site === MELON_SITE) "멜론 서버 시각" else "NOL 서버 시각"
        keywordHelp.text = if (site === MELON_SITE) "공연 날짜와 시각 (예: 10월 24일 18시)" else "버튼이 여러 개일 때 카드의 글자 (예: 3차전)"
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
        refreshAlarm()
    }

    override fun onPause() {
        super.onPause()
        stopPreview()
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
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        col.add(topBar())
        col.add(heroCard(), top = 8)
        col.add(formCard(), top = 12)
        col.add(alarmCard(), top = 12)
        col.add(action("시작", palette).apply {
            textSize = 16f
            setOnClickListener { start() }
        }, top = 16, height = dp(56))
        col.add(action("브라우저 열기 · 로그인 / 주소 가져오기", palette, "secondary").apply {
            backgroundTintList = ColorStateList.valueOf(palette.chipOnBg)
            setOnClickListener { openBrowser(null) }
        }, top = 8, height = dp(52))
        col.add(text("오픈 5분 전에 시작하고, 로그인과 날짜/회차 선택을 마쳐 주세요.\n화면을 켜 두세요. 대기열·좌석·결제는 직접 진행합니다.", palette, 12.5f, palette.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }, top = 14)
        val foot = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        updateStatus = text("", palette, 12f, palette.muted)
        foot.addView(updateStatus, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        foot.addView(action("업데이트 확인", palette, "text").apply {
            textSize = 12.5f
            setTextColor(palette.label)
            setOnClickListener { updater.check() }
        })
        col.add(foot, top = 12)
        return ScrollView(this).apply {
            setBackgroundColor(palette.bg)
            isFillViewport = true
            addView(col)
            padForSystemBars()
        }
    }

    /** Mark, app name, site chip and the theme button. */
    private fun topBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_mark)
            imageTintList = ColorStateList.valueOf(palette.onAccent)
            background = rounded(palette.accent, 10)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        bar.addView(text("예매 도우미", palette, 19f, palette.text, 700),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        siteChip = text("NOL", palette, 12.5f, palette.label, 600).apply {
            background = rounded(palette.chipOnBg, 99, palette.border)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        bar.addView(siteChip)
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_palette)
            imageTintList = ColorStateList.valueOf(palette.label)
            background = rounded(android.graphics.Color.TRANSPARENT, 12)
            contentDescription = "테마 고르기"
            setOnClickListener { pickTheme() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        return bar
    }

    /** Countdown to the opening, the server clock and the connection. */
    private fun heroCard(): LinearLayout {
        val hero = card(palette, 20)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("오픈까지", palette, 14f, palette.label, 600), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply { chip(palette, "idle") })
        hero.add(head)
        countdown = text("서버 시각 측정 중…", palette, 24f, palette.muted, 700).apply {
            fontFeatureSettings = "tnum"
            letterSpacing = -0.02f
        }
        hero.add(countdown, top = 14)
        target = text("", palette, 14f, palette.muted)
        hero.add(target, top = 8)

        val tiles = LinearLayout(this)
        val server = tile(palette)
        val serverHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        serverLabel = text("NOL 서버 시각", palette, 12.5f, palette.label, 600)
        serverHead.addView(serverLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        serverHead.addView(text("다시 측정", palette, 12.5f, palette.accent, 600).apply {
            setPadding(dp(6), dp(4), 0, dp(4))
            setOnClickListener { measure() }
        })
        server.add(serverHead)
        clock = text("--:--:--.---", palette, 17f, palette.text, 700).apply { fontFeatureSettings = "tnum" }
        server.add(clock, top = 6)
        drift = text("측정 전", palette, 12f, palette.muted)
        server.add(drift, top = 6)
        tiles.addView(server, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(5) })

        val link = tile(palette)
        link.add(text("연결", palette, 12.5f, palette.label, 600).apply { setPadding(0, dp(4), 0, dp(4)) })
        val rttRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        speedDot = text("●", palette, 11f, palette.faint)
        rttRow.addView(speedDot)
        rtt = text("—", palette, 17f, palette.text, 700).apply { fontFeatureSettings = "tnum" }
        rttRow.addView(rtt, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
        link.add(rttRow, top = 2)
        host = text("—", palette, 12f, palette.muted).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        link.add(host, top = 6)
        tiles.addView(link, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(5) })
        hero.add(tiles, top = 18)
        networkTip = text("", palette, 12f, palette.faint).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = View.GONE
        }
        hero.add(networkTip, top = 10)
        return hero
    }

    private fun formCard(): LinearLayout {
        val form = card(palette)
        url = field(form, "공연 페이지 주소", settings.get("url", ""), "https://tickets.interpark.com/goods/…", null,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, top = 0)
        url.doAfterTextChanged { if (::keywordHelp.isInitialized) showSite() }
        val (left, right) = twoColumns(form)
        date = field(left, "오픈 날짜", settings.get("date", LocalDate.now(KST).toString()), "20261020", null,
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE, icon = R.drawable.ic_calendar, iconLabel = "날짜 선택") { pickDate() }
        time = field(right, "오픈 시각", settings.get("time", "20:00:00"), "200000", null,
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME, icon = R.drawable.ic_clock, iconLabel = "시각 선택") { pickTime() }
        date.normalizeOnBlur { normalizeDate(it) }
        time.normalizeOnBlur { normalizeTime(it) }
        keywords = field(form, "경기 / 회차 키워드", settings.get("keywords", ""), "", "", InputType.TYPE_CLASS_TEXT)
        keywordHelp = helpOf(keywords)
        val (earlyRow, earlySwitch) = switchRow(palette, "미리 누르기",
            "요청이 서버에 닿는 시간만큼 일찍 눌러요 (최대 150 ms)", settings.get("early_press", "false") == "true")
        early = earlySwitch
        form.add(earlyRow, top = 20)

        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val expand = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            isClickable = true
            isFocusable = true
        }
        expand.addView(text("고급 설정", palette, 14f, palette.label, 600))
        val arrow = ImageView(this).apply {
            setImageResource(R.drawable.ic_expand)
            imageTintList = ColorStateList.valueOf(palette.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        expand.addView(arrow, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
        expand.setOnClickListener {
            val open = advanced.visibility == View.GONE
            advanced.visibility = if (open) View.VISIBLE else View.GONE
            arrow.rotation = if (open) 180f else 0f
        }
        form.add(expand, top = 8, width = WRAP_CONTENT)
        val (advLeft, advRight) = twoColumns(advanced)
        labels = field(advLeft, "예매 버튼 문구", settings.get("labels", "예매하기, 예매 하기"), "", "쉼표로 구분", InputType.TYPE_CLASS_TEXT, top = 4)
        reloadAfter = field(advRight, "새로고침 간격 (초)", settings.get("reload_after", "2.0"), "", "버튼이 안 열릴 때",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, top = 4)
        form.add(advanced)
        return form
    }

    private fun twoColumns(parent: LinearLayout): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(this)
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        row.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
        parent.add(row)
        return left to right
    }

    private fun pickTheme() {
        val keys = THEMES.keys.toList()
        MaterialAlertDialogBuilder(this)
            .setTitle("테마")
            .setSingleChoiceItems(THEMES.values.toTypedArray(), keys.indexOf(palette.name)) { dialog, which ->
                dialog.dismiss()
                if (keys[which] != palette.name) {
                    save()
                    settings.save(mapOf("theme" to keys[which]))
                    recreate()
                }
            }
            .show()
    }

    /** 오픈 전 알람: 켜기, 몇 분 전, 크기, 미리 듣기, 시계 앱에 추가. */
    private fun alarmCard(): LinearLayout {
        val card = card(palette)
        val (row, switch) = switchRow(palette, "오픈 전 알람",
            "앱을 꺼 두어도 오픈 전에 알람과 진동이 울려요", settings.get(OpenAlarm.KEY_ON, "false") == "true")
        alarmOn = switch.apply {
            setOnCheckedChangeListener { _, on ->
                if (on) askAlarmPermissions()
                save()
            }
        }
        card.add(row)
        val grid = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        val minutesCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(minutesCol, LinearLayout.LayoutParams(dp(96), WRAP_CONTENT).apply { marginEnd = dp(14) })
        alarmMinutes = field(minutesCol, "몇 분 전", settings.get(OpenAlarm.KEY_MINUTES, OpenAlarm.DEFAULT_MINUTES),
            "1~120", null, InputType.TYPE_CLASS_NUMBER)
        val volumeCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(volumeCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val volumeLabel = text("", palette, 13f, palette.label, 600)
        alarmVolume = Slider(this).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 5f
            value = (OpenAlarm.volume(settings) / 5 * 5).toFloat()
            setLabelFormatter { "${it.toInt()}%" }
            trackActiveTintList = ColorStateList.valueOf(palette.accent)
            trackInactiveTintList = ColorStateList.valueOf(palette.chip)
            thumbTintList = ColorStateList.valueOf(palette.accent)
            isTickVisible = false
            addOnChangeListener { _, v, _ -> volumeLabel.text = "알람 크기 ${v.toInt()}%" }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    save()
                    if (preview != null) startPreview() // 듣는 중이면 바꾼 크기로 다시 들려준다
                }
            })
        }
        volumeLabel.text = "알람 크기 ${alarmVolume.value.toInt()}%"
        volumeCol.add(volumeLabel, top = 16)
        volumeCol.add(alarmVolume, top = 2)
        card.add(grid)
        val buttons = LinearLayout(this)
        previewBtn = action("미리 듣기", palette, "secondary").apply {
            textSize = 14f
            setOnClickListener { if (preview == null) startPreview() else stopPreview() }
        }
        buttons.addView(previewBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        buttons.addView(action("시계 앱에도 추가", palette, "secondary").apply {
            textSize = 14f
            setOnClickListener { addToClockApp() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        card.add(buttons, top = 12)
        alarmStatus = text("", palette, 12.5f, palette.accent).apply { setLineSpacing(dp(2).toFloat(), 1f) }
        card.add(alarmStatus, top = 10)
        return card
    }

    private fun startPreview() {
        val sound = preview ?: AlarmSound(this).also { preview = it }
        sound.start(alarmVolume.value.toInt())
        previewBtn.text = "멈추기"
        // 미리 듣기는 4초만
        alarmStatus.removeCallbacks(previewTimeout)
        alarmStatus.postDelayed(previewTimeout, 4000)
    }

    private val previewTimeout = Runnable { stopPreview() }

    private fun stopPreview() {
        preview?.stop()
        preview = null
        alarmStatus.removeCallbacks(previewTimeout)
        previewBtn.text = "미리 듣기"
    }

    /** Notifications (Android 13+) are needed to show the alarm and its stop button. */
    private fun askAlarmPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    private fun refreshAlarm() {
        var text = OpenAlarm.schedule(this)
        if (alarmOn.isChecked && Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            text += "\n잠금 화면에 알람 화면을 띄우려면 설정에서 '전체 화면 알림'을 허용하세요."
        }
        alarmStatus.text = text
    }

    /**
     * The phone's clock app as a backup. It takes only hour and minute, so it rings at the
     * next such time; warn when the opening is further away than that.
     */
    private fun addToClockApp() {
        save()
        val openAt = parseOpenAt(date.text.toString(), time.text.toString())
            ?: return fail("오픈 날짜/시각을 확인하세요.")
        val minutes = OpenAlarm.minutes(settings)
        val atMs = (openAt * 1000).toLong() - minutes * 60_000L
        if (atMs <= System.currentTimeMillis()) return fail("알람 시각(오픈 ${minutes}분 전)이 이미 지났습니다.")
        // The clock app reads hour/minute in the phone's own time zone.
        val at = Instant.ofEpochMilli(atMs).atZone(java.time.ZoneId.systemDefault())
        val add = {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, at.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, at.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "NOL 예매 ${minutes}분 전")
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                fail("알람을 받을 시계 앱을 찾지 못했습니다.")
            }
        }
        val hoursAway = (at.toEpochSecond() - Instant.now().epochSecond) / 3600.0
        if (hoursAway > 24) {
            MaterialAlertDialogBuilder(this)
                .setMessage("시계 앱 알람은 날짜를 정할 수 없어 다음 %02d:%02d에 울립니다. 오픈이 하루 넘게 남았으니, 오픈 전날 이후에 추가하는 것이 좋습니다.\n\n그래도 지금 추가할까요?".format(at.hour, at.minute))
                .setPositiveButton("추가") { _, _ -> add() }
                .setNegativeButton("취소", null)
                .show()
        } else {
            add()
        }
    }

    /** Label, input box (with an optional picker icon inside) and help text; help null hides it. */
    private fun field(
        parent: LinearLayout, title: String, value: String, placeholder: String, help: String?, type: Int,
        top: Int = 16, icon: Int = 0, iconLabel: String = "", onIcon: () -> Unit = {},
    ): EditText {
        parent.add(text(title, palette, 13f, palette.label, 600), top = top)
        val edit = input(palette, value, placeholder, type)
        if (icon == 0) {
            parent.add(edit, top = 8)
        } else {
            val box = FrameLayout(this)
            edit.setPaddingRelative(dp(14), dp(12), dp(44), dp(12))
            box.addView(edit, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            box.addView(ImageButton(this).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(palette.muted)
                background = null
                contentDescription = iconLabel
                setOnClickListener { onIcon() }
            }, FrameLayout.LayoutParams(dp(44), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
            parent.add(box, top = 8)
        }
        val helpView = text(help.orEmpty(), palette, 12f, palette.muted).apply {
            visibility = if (help == null) View.GONE else View.VISIBLE
        }
        parent.add(helpView, top = 6)
        edit.tag = helpView
        return edit
    }

    private fun helpOf(edit: EditText) = edit.tag as TextView

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
        drift.text = "서버 시각 측정 중…"
        drift.setTextColor(palette.muted)
        measuring = launch {
            try {
                val off = withContext(Dispatchers.IO) { measureServers(timeUrls(url.text.toString())) }
                offset = off
                val ms = off.seconds * 1000
                drift.text = when {
                    kotlin.math.abs(ms) < 50 -> "내 폰과 거의 같음"
                    ms > 0 -> "내 폰보다 %.2f초 빠름".format(off.seconds)
                    else -> "내 폰보다 %.2f초 느림".format(-off.seconds)
                }
                rtt.text = "왕복 %.0f ms".format(off.rtt * 1000)
                speedDot.setTextColor(palette.speed(off.rtt * 1000))
                host.text = "오차 ±%.0f ms · %s".format(off.error * 1000, off.host)
                networkTip.text = "%s로 연결됨 (%s). 와이파이와 모바일 데이터를 바꿔 다시 측정해 보고, 왕복이 짧은 쪽으로 예매하세요."
                    .format(networkName(), off.speed())
                networkTip.visibility = View.VISIBLE
            } catch (e: Exception) {
                drift.text = "측정 실패: ${e.message} — 인터넷 연결을 확인하고 다시 측정하세요."
                drift.setTextColor(palette.danger)
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
            OpenAlarm.KEY_ON to alarmOn.isChecked.toString(),
            OpenAlarm.KEY_MINUTES to (alarmMinutes.text.toString().toIntOrNull()?.coerceIn(1, 120)?.toString() ?: OpenAlarm.DEFAULT_MINUTES),
            OpenAlarm.KEY_VOLUME to alarmVolume.value.toInt().toString(),
        ))
        refreshAlarm()
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
