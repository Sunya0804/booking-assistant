package dev.bookingassistant.nol

import java.net.HttpURLConnection
import java.net.URL

/**
 * Estimate a web server's clock offset from its HTTP `Date` header.
 *
 * The `Date` header only has 1-second resolution, so we poll rapidly and watch
 * for the moment the second ticks over. The rollover lies between two requests,
 * which pins the server's clock to within roughly half a round trip.
 * (Same method as servertime.py in the PC version.)
 */
const val TIME_URL = "https://tickets.interpark.com/"
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) booking_assistant/0.1"

// Servers whose clock we can read. All of them agree to within measurement noise;
// what differs is precision: the booking servers answer in ~30 ms, while
// tickets.interpark.com is a CDN redirect at ~160 ms, so its estimate is ~4x looser.
val TIME_URLS = listOf(
    "https://poticket.interpark.com/",
    "https://ticket.interpark.com/",
    TIME_URL,
)

// Never press more than this much ahead of the opening, however slow the network looks.
private const val MAX_LEAD = 0.15

data class Offset(
    val seconds: Double, // server_time - local_time
    val error: Double,   // +/- uncertainty in seconds
    val rtt: Double,     // median round-trip time in seconds
    val url: String = "", // server the offset was measured against
) {
    fun serverNow(): Double = localNow() + seconds

    val host: String get() = runCatching { URL(url).host }.getOrDefault(url)

    /**
     * How early to press so the request reaches the server at the opening.
     * One-way trip is about half the round trip; we take the measurement error off that,
     * because arriving early costs a "not open yet" round and arriving late only the difference.
     */
    fun lead(): Double = (rtt / 2 - error).coerceIn(0.0, MAX_LEAD)
}

fun localNow(): Double = System.currentTimeMillis() / 1000.0

private class Probe(val send: Double, val recv: Double, val serverSecond: Long)

private fun probe(url: String, timeoutMs: Int): Probe {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "HEAD"
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    conn.setRequestProperty("User-Agent", USER_AGENT)
    val t0 = localNow()
    conn.responseCode
    val date = conn.getHeaderFieldDate("Date", -1L)
    val t1 = localNow()
    // No disconnect(): keep the connection alive so later probes skip the handshake.
    runCatching { conn.inputStream.close() }
    if (date < 0) throw IllegalStateException("$url returned no Date header")
    return Probe(t0, t1, date / 1000)
}

/** Poll `url` until the server's second rolls over, then derive the offset. */
fun measure(url: String, maxSeconds: Double = 4.0, intervalMs: Long = 30, timeoutMs: Int = 3000): Offset {
    val deadline = localNow() + maxSeconds
    var prev = probe(url, timeoutMs)
    val rtts = mutableListOf(prev.recv - prev.send)
    while (localNow() < deadline) {
        Thread.sleep(intervalMs)
        val cur = probe(url, timeoutMs)
        rtts.add(cur.recv - cur.send)
        if (cur.serverSecond != prev.serverSecond) {
            // Server read its clock somewhere in each request window; the tick to
            // cur.serverSecond happened after prev was stamped and before cur was stamped.
            val lo = prev.send
            val hi = cur.recv
            // Narrow using midpoints (server typically stamps near the middle).
            val loMid = (prev.send + prev.recv) / 2
            val hiMid = (cur.send + cur.recv) / 2
            val tickLocal = (loMid + hiMid) / 2
            rtts.sort()
            return Offset(
                seconds = cur.serverSecond - tickLocal,
                error = if (hi - lo > 0) maxOf((hiMid - loMid) / 2, 0.001) else 0.5,
                rtt = rtts[rtts.size / 2],
                url = url,
            )
        }
        prev = cur
    }
    throw IllegalStateException("server second did not roll over in time; try again")
}

/** Run several measurements and keep the tightest one. Blocking: call off the main thread. */
fun measureBest(url: String = TIME_URL, rounds: Int = 3, log: (String) -> Unit = {}): Offset {
    val results = mutableListOf<Offset>()
    repeat(rounds) {
        try {
            results.add(measure(url))
        } catch (e: Exception) { // network blips shouldn't kill the whole sync
            log("  [time] probe failed: ${e.message}")
        }
    }
    return results.minByOrNull { it.error } ?: throw IllegalStateException("could not measure server time")
}

/** Booking servers plus the event page's own server. */
fun timeUrls(pageUrl: String = ""): List<String> {
    val origin = runCatching {
        val u = URL(pageUrl.trim())
        if (u.protocol in listOf("http", "https") && u.host.isNotEmpty()) "${u.protocol}://${u.authority}/" else null
    }.getOrNull()
    return if (origin == null || origin in TIME_URLS) TIME_URLS else listOf(origin) + TIME_URLS
}

/** Measure every server at once and keep the most precise estimate. Blocking: call off the main thread. */
fun measureServers(urls: List<String>, rounds: Int = 2): Offset {
    val results = java.util.Collections.synchronizedList(mutableListOf<Offset>())
    urls.map { url ->
        Thread { runCatching { results.add(measureBest(url, rounds)) } }.apply { start() }
    }.forEach { it.join() }
    return results.minByOrNull { it.error } ?: throw IllegalStateException("could not measure server time")
}
