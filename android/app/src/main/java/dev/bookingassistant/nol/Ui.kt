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
    val muted = if (night) 0xFF9AA0A8.toInt() else 0xFF6B6F76.toInt()
    val accent = if (night) 0xFF57C8FF.toInt() else 0xFF005FB8.toInt()
    val danger = if (night) 0xFFFF6B6B.toInt() else 0xFFD93838.toInt()
    val logBg = if (night) 0xFF232323.toInt() else 0xFFF6F7F9.toInt()
    val logFg = if (night) 0xFFD8DBE0.toInt() else 0xFF30343B.toInt()

    /** (background, text) per status. */
    val status = mapOf(
        "idle" to if (night) (0xFF353840 to 0xFFB8BDC6) else (0xFFEEF0F3 to 0xFF5B6068),
        "busy" to if (night) (0xFF1D3A4D to 0xFF7FD4FF) else (0xFFE3EEFA to 0xFF005FB8),
        "done" to if (night) (0xFF1F3D2A to 0xFF6FDC96) else (0xFFE3F6EA to 0xFF17803D),
        "fail" to if (night) (0xFF4A2323 to 0xFFFF8A8A) else (0xFFFDE8E8 to 0xFFC42B2B),
    ).mapValues { (_, v) -> v.first.toInt() to v.second.toInt() }
}

val STATUS_TEXT = mapOf("idle" to "대기", "busy" to "진행 중", "done" to "클릭 완료", "fail" to "확인 필요")

fun TextView.chip(palette: Palette, status: String) {
    val (bg, fg) = palette.status.getValue(status)
    text = STATUS_TEXT.getValue(status)
    setTextColor(fg)
    typeface = Typeface.DEFAULT_BOLD
    background = GradientDrawable().apply {
        setColor(bg)
        cornerRadius = context.dp(12).toFloat()
    }
    setPadding(context.dp(10), context.dp(3), context.dp(10), context.dp(3))
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
