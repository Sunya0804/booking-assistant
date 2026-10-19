package dev.bookingassistant.nol

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.WindowInsets
import android.widget.TextView

/** Small helpers for the code-built screens. */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.isNight(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** Same colors as the PC version's PALETTE. */
class Palette(night: Boolean) {
    val bg = if (night) 0xFF121918.toInt() else 0xFFF4F6F6.toInt()
    val surface = if (night) 0xFF1C2725.toInt() else 0xFFFFFFFF.toInt()
    val muted = if (night) 0xFFA3B7B2.toInt() else 0xFF586B68.toInt()
    val accent = if (night) 0xFF79D5C5.toInt() else 0xFF0F766E.toInt()
    val danger = if (night) 0xFFFFB1BB.toInt() else 0xFFB42335.toInt()
    val logBg = surface
    val logFg = if (night) 0xFFE5EFEC.toInt() else 0xFF182625.toInt()
    val status = mapOf(
        "idle" to if (night) (0xFF2D403B to 0xFFCFDFD9) else (0xFFE4EEEB to 0xFF354F49),
        "busy" to if (night) (0xFF173F39 to 0xFF79D5C5) else (0xFFDDF2EE to 0xFF0F766E),
        "done" to if (night) (0xFF1D4230 to 0xFF8DE0AD) else (0xFFD9F4E4 to 0xFF17633C),
        "fail" to if (night) (0xFF4B2930 to 0xFFFFB1BB) else (0xFFFCE5E8 to 0xFFB42335),
    ).mapValues { (_, v) -> v.first.toInt() to v.second.toInt() }
}

fun Context.surface(color: Int, radius: Int = 16) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
}

fun Context.action(text: String, palette: Palette, kind: String = "filled") =
    com.google.android.material.button.MaterialButton(this).apply {
        this.text = text
        isAllCaps = false
        cornerRadius = dp(12)
        minimumHeight = dp(48)
        val backgroundColor = when (kind) {
            "text", "outlined" -> android.graphics.Color.TRANSPARENT
            "danger" -> palette.status.getValue("fail").first
            else -> palette.accent
        }
        val foregroundColor = when (kind) {
            "text", "outlined" -> palette.accent
            "danger" -> palette.danger
            else -> palette.bg
        }
        backgroundTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(palette.status.getValue("idle").first, backgroundColor))
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(palette.muted, foregroundColor)))
        strokeWidth = if (kind == "outlined") dp(1) else 0
        strokeColor = android.content.res.ColorStateList.valueOf(palette.muted)
        elevation = 0f
    }

val STATUS_TEXT = mapOf("idle" to "대기", "busy" to "진행 중", "done" to "클릭 완료", "fail" to "확인 필요")

fun TextView.chip(palette: Palette, status: String) {
    val (bg, fg) = palette.status.getValue(status)
    text = (mapOf("idle" to "◷", "busy" to "●", "done" to "✓", "fail" to "!").getValue(status)) + "  " + STATUS_TEXT.getValue(status)
    setTextColor(fg)
    typeface = Typeface.DEFAULT_BOLD
    background = GradientDrawable().apply {
        setColor(bg)
        cornerRadius = context.dp(12).toFloat()
    }
    setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
    contentDescription = STATUS_TEXT.getValue(status)
    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
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
