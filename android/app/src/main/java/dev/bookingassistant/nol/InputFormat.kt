package dev.bookingassistant.nol

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Turn loosely typed dates/times into the canonical form (port of inputfmt.py).
 *
 *     200000 -> 20:00:00     2000 -> 20:00:00     20 -> 20:00:00
 *     20:0   -> 20:00:00     20시 -> 20:00:00     20시30분 -> 20:30:00
 *     20261020 -> 2026-10-20   1020 -> <this year>-10-20   2026.10.20 -> 2026-10-20
 * Anything that cannot be understood comes back as null.
 */
val KST: ZoneId = ZoneId.of("Asia/Seoul")

private val NON_DIGITS = Regex("[^0-9]+")

private fun digitGroups(s: String) = s.split(NON_DIGITS).filter { it.isNotEmpty() }

fun normalizeTime(text: String): String? {
    val s = text.trim()
    if (s.isEmpty()) return null
    var parts = digitGroups(s)
    if (parts.size == 1) {
        val d = parts[0]
        parts = when (d.length) {
            1, 2 -> listOf(d)
            3, 4 -> listOf(d.dropLast(2), d.takeLast(2))
            5, 6 -> listOf(d.dropLast(4), d.takeLast(4).take(2), d.takeLast(2))
            else -> return null
        }
    }
    if (parts.size !in 1..3) return null
    val n = (parts.map { it.toIntOrNull() ?: return null } + listOf(0, 0)).take(3)
    val (h, m, sec) = n
    if (h !in 0..23 || m !in 0..59 || sec !in 0..59) return null
    return "%02d:%02d:%02d".format(h, m, sec)
}

fun normalizeDate(text: String, today: LocalDate = LocalDate.now(KST)): String? {
    val s = text.trim()
    if (s.isEmpty()) return null
    var parts = digitGroups(s)
    if (parts.size == 1) {
        val d = parts[0]
        parts = when (d.length) {
            8 -> listOf(d.substring(0, 4), d.substring(4, 6), d.substring(6))
            6 -> listOf("20" + d.substring(0, 2), d.substring(2, 4), d.substring(4))
            3, 4 -> listOf(d.dropLast(2), d.takeLast(2))
            else -> return null
        }
    }
    if (parts.size == 2) parts = listOf(today.year.toString()) + parts
    if (parts.size != 3) return null
    var (y, m, d) = parts.map { it.toIntOrNull() ?: return null }
    if (y < 100) y += 2000
    return try {
        LocalDate.of(y, m, d).toString()
    } catch (e: DateTimeException) {
        null
    }
}

/** Opening time as epoch seconds (Korean time), or null if either field is invalid. */
fun parseOpenAt(dateText: String, timeText: String): Double? {
    val d = normalizeDate(dateText) ?: return null
    val t = normalizeTime(timeText) ?: return null
    return LocalDateTime.of(LocalDate.parse(d), LocalTime.parse(t)).atZone(KST).toEpochSecond().toDouble()
}
