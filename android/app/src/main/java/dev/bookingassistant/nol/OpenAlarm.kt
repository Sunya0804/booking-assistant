package dev.bookingassistant.nol

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * An alarm a few minutes before the opening that rings even when the app is closed.
 *
 * Uses AlarmManager.setAlarmClock: exact, allowed through Doze, shown in the status bar
 * like a clock app's alarm, and allowed to start AlarmService from the background.
 * Settings live with the rest of the form (alarm_on / alarm_minutes / alarm_volume).
 */
object OpenAlarm {
    const val KEY_ON = "alarm_on"
    const val KEY_MINUTES = "alarm_minutes"
    const val KEY_VOLUME = "alarm_volume"
    const val DEFAULT_MINUTES = "5"
    const val DEFAULT_VOLUME = "70"
    const val EXTRA_OPEN_AT = "open_at"
    const val EXTRA_MINUTES = "minutes"

    private val whenFormat = DateTimeFormatter.ofPattern("M월 d일 HH:mm:ss").withZone(KST)

    fun minutes(settings: Settings): Int =
        settings.get(KEY_MINUTES, DEFAULT_MINUTES).toIntOrNull()?.coerceIn(1, 120) ?: 5

    fun volume(settings: Settings): Int =
        settings.get(KEY_VOLUME, DEFAULT_VOLUME).toIntOrNull()?.coerceIn(0, 100) ?: 70

    /** Opening time in epoch seconds from the saved form, or null. */
    fun openAt(settings: Settings): Double? = parseOpenAt(settings.get("date", ""), settings.get("time", ""))

    private fun firePending(context: Context, openAt: Double = 0.0, minutes: Int = 0): PendingIntent =
        PendingIntent.getForegroundService(
            context, 0,
            Intent(context, AlarmService::class.java)
                .putExtra(EXTRA_OPEN_AT, openAt)
                .putExtra(EXTRA_MINUTES, minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Set (or clear) the alarm from the saved settings. Returns a line for the screen. */
    fun schedule(context: Context): String {
        val settings = Settings(context)
        val am = context.getSystemService(AlarmManager::class.java)
        if (settings.get(KEY_ON, "false") != "true") {
            am.cancel(firePending(context))
            return "알람 꺼짐"
        }
        val openAt = openAt(settings) ?: run {
            am.cancel(firePending(context))
            return "오픈 날짜/시각을 확인하세요"
        }
        val minutes = minutes(settings)
        val at = (openAt * 1000).toLong() - minutes * 60_000L
        if (at <= System.currentTimeMillis()) {
            am.cancel(firePending(context))
            return "알람 시각(오픈 ${minutes}분 전)이 이미 지났습니다"
        }
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            return "정확한 알람 권한이 꺼져 있어 알람을 맞추지 못했습니다"
        }
        val show = PendingIntent.getActivity(context, 1, Intent(context, SetupActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), firePending(context, openAt, minutes))
        return "${whenFormat.format(Instant.ofEpochMilli(at))}에 울립니다 (오픈 ${minutes}분 전)"
    }
}

/** Alarms are wiped on reboot and on app update (including our own auto-update); set it again. */
class AlarmRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OpenAlarm.schedule(context)
    }
}
