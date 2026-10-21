package dev.bookingassistant.nol

import android.content.Context
import android.content.Intent
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Saved logs of booking runs (files/logs/run-yyyyMMdd-HHmmss.txt).
 *
 * BookingActivity writes each line as it happens, so a crash loses nothing, and closes the file
 * with a result line when the screen closes. Lines starting with "# " are metadata:
 * 사이트, 주소, 오픈 at the start; 결과, 닫음 at the end.
 */
object RunLogs {
    private const val KEEP = 30
    private val nameFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(KST)
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(KST)

    /** One saved run, newest first in [list]. */
    data class Entry(
        val file: File,
        val startedAt: Instant,
        val site: String,       // "NOL", "멜론티켓", "" for logs saved before 0.2.6
        val url: String,
        val openAt: String,     // "2026-10-24 20:00:00" (KST)
        val result: String,     // "클릭 완료", "확인 필요", "중지", "진행 중 닫음", "" if never closed
        val lines: Int,         // log lines, metadata not counted
    )

    fun dir(context: Context) = File(context.filesDir, "logs").apply { mkdirs() }

    /** A new file for a run, with its metadata; drops the oldest so at most KEEP remain. */
    fun start(context: Context, url: String, openAt: Double): File {
        val dir = dir(context)
        dir.listFiles()?.sortedBy { it.name }?.dropLast(KEEP - 1)?.forEach { it.delete() }
        val file = File(dir, "run-${nameFormat.format(Instant.now())}.txt")
        runCatching {
            file.writeText(
                "# 사이트: ${siteFor(url).name}\n# 주소: $url\n" +
                    "# 오픈: ${dateFormat.format(Instant.ofEpochMilli((openAt * 1000).toLong()))}\n"
            )
        }
        return file
    }

    /** Called when the booking screen closes; [result] as in [Entry.result]. */
    fun finish(file: File, result: String) {
        synchronized(file) {
            runCatching { file.appendText("# 결과: $result\n# 닫음: ${dateFormat.format(Instant.now())}\n") }
        }
    }

    fun list(context: Context): List<Entry> =
        dir(context).listFiles { f -> f.name.startsWith("run-") && f.name.endsWith(".txt") }
            .orEmpty()
            .sortedByDescending { it.name }
            .map { read(it) }

    private fun read(file: File): Entry {
        val meta = mutableMapOf<String, String>()
        var lines = 0
        runCatching {
            file.forEachLine { line ->
                if (line.startsWith("# ") && ':' in line) {
                    meta[line.substring(2).substringBefore(':').trim()] = line.substringAfter(':').trim()
                } else if (line.isNotBlank()) {
                    lines++
                }
            }
        }
        val started = runCatching {
            Instant.from(nameFormat.parse(file.name.removePrefix("run-").removeSuffix(".txt")))
        }.getOrDefault(Instant.ofEpochMilli(file.lastModified()))
        return Entry(file, started, meta["사이트"].orEmpty(), meta["주소"].orEmpty(),
            meta["오픈"].orEmpty(), meta["결과"].orEmpty(), lines)
    }

    fun delete(entry: Entry) = entry.file.delete()

    /** Send one or more saved logs to another app (메모, 카카오톡, 메일…) as text. */
    fun share(context: Context, entries: List<Entry>) {
        if (entries.isEmpty()) return
        val text = entries.joinToString("\n\n") { e ->
            "===== ${e.file.name} =====\n" + runCatching { e.file.readText() }.getOrDefault("")
        }
        val subject = if (entries.size == 1) "NOL 예매도우미 기록 ${entries[0].file.name}"
        else "NOL 예매도우미 기록 ${entries.size}개"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "기록 공유"))
    }
}
