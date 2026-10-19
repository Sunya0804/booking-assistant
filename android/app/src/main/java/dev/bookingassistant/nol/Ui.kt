package dev.bookingassistant.nol

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/** Small helpers for the code-built screens. */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.isNight(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** Theme keys and names, in menu order. Same five themes as the PC version (ui/app.css). */
val THEMES = linkedMapOf("light" to "라이트", "dark" to "다크", "sand" to "샌드", "midnight" to "미드나잇", "mono" to "모노")

/** The chosen theme; until one is chosen, light or dark following the phone. */
fun Context.themeName(): String {
    val saved = Settings(this).get("theme", "")
    return if (saved in THEMES) saved else if (isNight()) "dark" else "light"
}

/** Call before super.onCreate so Material widgets get the theme's colors too. */
fun Activity.applyTheme(): Palette = Palette(themeName()).also { setTheme(it.style) }

/** Dark or light status/navigation bar icons to match the theme. */
fun Activity.paintSystemBars(p: Palette) {
    val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    window.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else light, light)
}

/** Colors of one theme (the same tokens as the PC version's CSS variables). */
class Palette(val name: String) {
    private fun c(v: Long) = v.toInt()

    val dark = name == "dark" || name == "midnight"
    val style = when (name) {
        "dark" -> R.style.Theme_Booking_Dark
        "sand" -> R.style.Theme_Booking_Sand
        "midnight" -> R.style.Theme_Booking_Midnight
        "mono" -> R.style.Theme_Booking_Mono
        else -> R.style.Theme_Booking_Light
    }
    private val t: LongArray = when (name) {
        //                bg         surface    tile       field      chip       text       label      muted      faint      accent     success    successBg  warn       danger     dangerBg   border
        "dark" -> longArrayOf(0xFF0E1116, 0xFF171B22, 0xFF1F242D, 0xFF1F242D, 0xFF222833, 0xFFECEFF3, 0xFFAAB3BF, 0xFF9AA4B2, 0xFF6B7480, 0xFF4C7DFF, 0xFF3DD68C, 0xFF14301F, 0xFFFFB86B, 0xFFFF8A8A, 0xFF24181B, 0)
        "sand" -> longArrayOf(0xFFF4EFE6, 0xFFFFFCF7, 0xFFF7F2EA, 0xFFF1EBE1, 0xFFF1EBE1, 0xFF2B241D, 0xFF5E5347, 0xFF6F6254, 0xFFA39684, 0xFFC2410C, 0xFF3F7D4E, 0xFFE6F0E2, 0xFFB45309, 0xFFB42318, 0xFFFBE9E4, 0)
        "midnight" -> longArrayOf(0xFF0B1020, 0xFF141B2E, 0xFF1B2338, 0xFF1B2338, 0xFF1F2840, 0xFFE7EBF6, 0xFFB3BCD3, 0xFF9AA4BE, 0xFF6F7A96, 0xFF6F5EF0, 0xFF4ADE80, 0xFF12301F, 0xFFFFB86B, 0xFFFF8FA3, 0xFF2A1622, 0)
        "mono" -> longArrayOf(0xFFFAFAFA, 0xFFFFFFFF, 0xFFF4F4F5, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF09090B, 0xFF52525B, 0xFF5F5F67, 0xFFA1A1AA, 0xFF18181B, 0xFF15803D, 0xFFE8F5EC, 0xFFC2410C, 0xFFDC2626, 0xFFFEECEC, 0xFFE4E4E7)
        else -> longArrayOf(0xFFF2F4F6, 0xFFFFFFFF, 0xFFF7F8FA, 0xFFF2F4F6, 0xFFF2F4F6, 0xFF191F28, 0xFF4E5968, 0xFF5B6573, 0xFF8B95A1, 0xFF2B5CE6, 0xFF0A7A54, 0xFFE3F6EE, 0xFFE8590C, 0xFFD92D20, 0xFFFDECEC, 0)
    }
    val bg = c(t[0])
    val surface = c(t[1])
    val tile = c(t[2])
    val field = c(t[3])
    val chip = c(t[4])
    val text = c(t[5])
    val label = c(t[6])
    val muted = c(t[7])
    val faint = c(t[8])
    val accent = c(t[9])
    val success = c(t[10])
    val successBg = c(t[11])
    val warn = c(t[12])
    val danger = c(t[13])
    val dangerBg = c(t[14])
    /** Hairline around cards and fields; only the mono theme has one. */
    val border = c(t[15])
    val onAccent = 0xFFFFFFFF.toInt()
    /** The "진행 중" chip and tonal buttons: accent at about 13% over the surface. */
    val accentSoft = ColorUtils.blendARGB(surface, accent, if (dark) 0.16f else 0.11f)
    /** A chip sitting on the page background rather than on a card. */
    val chipOnBg = if (border != 0) surface else ColorUtils.blendARGB(bg, text, if (dark) 0.10f else 0.06f)

    /** Chip colors (background to text) for each run state. */
    fun status(key: String): Pair<Int, Int> = when (key) {
        "busy" -> accentSoft to accent
        "done" -> successBg to success
        "fail" -> dangerBg to danger
        else -> chip to label
    }

    fun speed(rttMs: Double): Int = when {
        rttMs <= 40 -> success
        rttMs <= 100 -> warn
        else -> danger
    }
}

private var pretendard: Typeface? = null

/** Pretendard at a weight (400, 600, 700). */
fun Context.font(weight: Int = 400): Typeface {
    val family = pretendard ?: resources.getFont(R.font.pretendard).also { pretendard = it }
    return Typeface.create(family, weight, false)
}

fun Context.rounded(color: Int, radius: Int, border: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
    if (border != 0) setStroke(dp(1), border)
}

/** A white (or themed) rounded card. */
fun Context.card(p: Palette, vertical: Int = 18) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(p.surface, 18, p.border)
    setPadding(dp(18), dp(vertical), dp(18), dp(vertical))
}

/** The small grey boxes inside a card (server clock, connection, result numbers). */
fun Context.tile(p: Palette) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(p.tile, 14)
    setPadding(dp(14), dp(12), dp(14), dp(12))
}

fun Context.text(value: String, p: Palette, size: Float = 14f, color: Int = p.text, weight: Int = 400) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    typeface = font(weight)
    includeFontPadding = false
}

fun LinearLayout.add(view: View, top: Int = 0, width: Int = MATCH_PARENT, height: Int = WRAP_CONTENT, weight: Float = 0f): View {
    addView(view, LinearLayout.LayoutParams(width, height, weight).apply { topMargin = context.dp(top) })
    return view
}

/** A filled input box like the PC version: field color, accent outline while focused. */
fun Context.input(p: Palette, value: String, hint: String, type: Int) = EditText(this).apply {
    setText(value)
    this.hint = hint
    inputType = type
    isSingleLine = true
    textSize = 15f
    typeface = font(400)
    if (type and InputType.TYPE_CLASS_DATETIME != 0) fontFeatureSettings = "tnum"
    setTextColor(p.text)
    setHintTextColor(p.faint)
    highlightColor = ColorUtils.setAlphaComponent(p.accent, 60)
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(p.field); cornerRadius = dp(11).toFloat(); setStroke(dp(2), p.accent)
        })
        addState(intArrayOf(), rounded(p.field, 11, p.border))
    }
    minHeight = dp(48)
    setPadding(dp(14), dp(12), dp(14), dp(12))
}

/** Button kinds: filled (accent), tonal (soft accent), danger (soft red), secondary (tile), text. */
fun Context.action(text: String, p: Palette, kind: String = "filled") = MaterialButton(this).apply {
    this.text = text
    isAllCaps = false
    typeface = font(if (kind == "text") 600 else 700)
    textSize = if (kind == "text") 14f else 15f
    letterSpacing = 0f
    cornerRadius = dp(if (kind == "text") 10 else 14)
    minimumHeight = dp(44)
    insetTop = 0
    insetBottom = 0
    elevation = 0f
    stateListAnimator = null
    val (bg, fg) = when (kind) {
        "tonal" -> p.accentSoft to p.accent
        "danger" -> p.dangerBg to p.danger
        "secondary" -> p.chip to p.text
        "text" -> android.graphics.Color.TRANSPARENT to p.accent
        else -> p.accent to p.onAccent
    }
    val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
    backgroundTintList = ColorStateList(states, intArrayOf(if (kind == "text") bg else p.chip, bg))
    setTextColor(ColorStateList(states, intArrayOf(p.faint, fg)))
    rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(fg, 40))
    if (kind == "secondary" && p.border != 0) {
        strokeWidth = dp(1); strokeColor = ColorStateList.valueOf(p.border)
    }
    if (kind == "text") setPadding(dp(10), 0, dp(10), 0)
}

val STATUS_TEXT = mapOf("idle" to "대기", "busy" to "진행 중", "done" to "클릭 완료", "fail" to "확인 필요")

/** A pill with a dot: 대기 / 진행 중 / 클릭 완료 / 확인 필요 (or [label] for the same colors). */
fun TextView.chip(p: Palette, status: String, label: String = STATUS_TEXT.getValue(status)) {
    val (bg, fg) = p.status(status)
    text = "●  $label"
    textSize = 13f
    typeface = context.font(600)
    includeFontPadding = false
    setTextColor(fg)
    background = context.rounded(bg, 99, if (status == "idle") p.border else 0)
    gravity = Gravity.CENTER_VERTICAL
    setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
    contentDescription = label
    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
}

/** A row with a title, a one-line explanation and a switch on the right. */
fun Context.switchRow(p: Palette, title: String, desc: String, checked: Boolean): Pair<LinearLayout, MaterialSwitch> {
    val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.add(text(title, p, 15f, p.text, 600))
    texts.add(text(desc, p, 12.5f, p.muted).apply { setLineSpacing(dp(2).toFloat(), 1f) }, top = 4)
    row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
    val switch = MaterialSwitch(this).apply {
        isChecked = checked
        contentDescription = title
    }
    row.addView(switch)
    return row to switch
}

/** "와이파이" / "모바일 데이터" / … for the network the phone is using now. */
fun Context.networkName(): String {
    val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return "알 수 없음"
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "연결 없음"
    return when {
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "와이파이"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "모바일 데이터"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "유선"
        else -> "기타"
    }
}

/** targetSdk 35+ draws behind the system bars; keep content clear of them and the keyboard. */
fun View.padForSystemBars() {
    setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsets.CONSUMED
    }
}

/** Saved form values (same keys as the PC version's settings.json). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(key: String, default: String): String = prefs.getString(key, default) ?: default

    fun save(values: Map<String, String>) {
        prefs.edit().apply { values.forEach { (k, v) -> putString(k, v) } }.apply()
    }
}
