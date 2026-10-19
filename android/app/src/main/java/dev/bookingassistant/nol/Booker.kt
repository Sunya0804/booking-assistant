package dev.bookingassistant.nol

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.delay

/**
 * Press the booking button on time inside our own WebView (port of browser.run).
 *
 * Instead of reloading right before the opening, we watch the booking button and
 * press it the moment it becomes active; reloading is only a fallback when it
 * stays disabled after the opening. A button with an "오픈 예정" note counts as
 * "pending": we press when the note disappears or the opening time arrives. If a
 * press lands too early and the site answers "not open yet", we dismiss it and
 * press again.
 *
 * The press is a real touch sent to the WebView, so the page sees a trusted
 * tap, the same as a finger. Everything after the booking window opens (queue,
 * CAPTCHA, seat choice, payment) is left to the person holding the phone.
 */
class Booker(
    private val web: WebView,
    private val config: Config,
    private val log: (String) -> Unit,
    private val windowCount: () -> Int,
) {
    data class Config(
        val url: String,
        val openAt: Double,
        val labels: List<String>,
        val keywords: List<String>,
        val reloadAfter: Double = 2.0,
        val giveUpAfter: Double = 20.0,
    )

    enum class Outcome { CLICKED, BLOCKED, MISSED }

    private val page = PageScripts(web)
    private val dialogs = ArrayDeque<String>()
    private var reloadStartedAt: Double? = null
    private var reloadedAt: Double? = null
    private lateinit var offset: Offset

    /** While armed, page alerts are answered by us instead of being shown. */
    var armed = false
        private set

    /** Called from WebChromeClient for alert/confirm. Returns true if we took it. */
    fun onDialog(message: String): Boolean {
        if (!armed) return false
        dialogs.addLast(message)
        return true
    }

    /** Called when the main page finished loading. */
    fun onPageFinished() {
        val started = reloadStartedAt ?: return
        log("  새로고침 %.2fs 걸림".format(localNow() - started))
        reloadStartedAt = null
        reloadedAt = offset.serverNow()
    }

    fun disarm() {
        armed = false
    }

    /** Sleep until server time reaches [target], printing a countdown. */
    private suspend fun waitUntil(target: Double, label: String) {
        var lastShown: Int? = null
        while (true) {
            val remaining = target - offset.serverNow()
            if (remaining <= 0) return
            val shown = remaining.toInt()
            if (shown != lastShown && (shown <= 10 || shown % 30 == 0)) {
                log("  $label 까지 ${shown}s")
                lastShown = shown
            }
            if (remaining > 0.05) delay(((minOf(remaining - 0.03, 0.2)) * 1000).toLong().coerceAtLeast(1))
            else delay(1)
        }
    }

    private suspend fun touch(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        press.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(press)
        press.recycle()
        delay(15)
        val release = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        release.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(release)
        release.recycle()
    }

    /** Watch what the press did. Returns (result, detail), as in browser._after_click. */
    private suspend fun afterClick(windowsBefore: Int, urlBefore: String, popupsBefore: Int,
                                   timeout: Double = 2.0): Pair<String, String> {
        val end = localNow() + timeout
        while (localNow() < end) {
            dialogs.removeFirstOrNull()?.let { msg ->
                return (if (NOT_OPEN.containsMatchIn(msg)) "not_open" else "blocked") to msg
            }
            if (page.popupCount() > popupsBefore) return "not_open" to page.dismissLast()
            if (windowCount() > windowsBefore) return "opened" to "새 창"
            val url = web.url.orEmpty()
            if (url != urlBefore) {
                if ("login" in url.lowercase()) return "blocked" to "로그인 페이지로 이동했습니다. 먼저 로그인해 두세요."
                return "opened" to url
            }
            delay(50)
        }
        return "unknown" to ""
    }

    suspend fun run(offset: Offset): Outcome {
        this.offset = offset
        val (_, openAt, labels, keywords, reloadAfter, giveUpAfter) = config

        waitUntil(openAt - 10, "버튼 감시 시작")
        val cands = page.describe(labels, keywords)
        if (cands.size > 1 || keywords.isNotEmpty()) {
            log("  페이지의 예매 버튼 ${cands.size}개" + if (keywords.isNotEmpty()) " (키워드: ${keywords.joinToString(" ")})" else "")
            for (c in cands) log("  ${if (c.match) "▶" else " "} ${c.card.take(70)}")
            if (cands.size > 1 && keywords.isEmpty()) log("  ⚠ 경기/회차 키워드가 없어 첫 번째 버튼을 누릅니다.")
        }
        dialogs.clear()
        armed = true

        val first = page.tick(labels, keywords, scroll = true)?.takeIf { it.found }?.state
        when (first) {
            null -> log("  조건에 맞는 예매 버튼이 아직 없습니다. 오픈 시각부터 찾습니다.")
            "ready" -> {
                // 재오픈(N차) 공연 등: 버튼이 이미 열려 있으면 정각에 누른다.
                log("  버튼이 이미 활성 상태입니다. 오픈 시각 정각에 누릅니다.")
                waitUntil(openAt, "오픈")
            }
            "pending" -> log("  '오픈 예정' 표시가 있습니다. 표시가 사라지거나 정각이 되면 누릅니다.")
            else -> log("  버튼이 비활성 상태입니다. 활성화되는 즉시 누릅니다.")
        }

        var clicked = false
        var blocked = ""
        var tries = 0
        var waitedForScroll = false
        while (!clicked) {
            val now = offset.serverNow()
            if (now > openAt + giveUpAfter) break
            val t = page.tick(labels, keywords, scroll = true)
            val state = if (t != null && t.found) t.state else "disabled"
            if (t != null && (state == "ready" || (state == "pending" && now >= openAt))) {
                // tick() just scrolled the button into view; give layout one frame.
                if (!t.inView && !waitedForScroll) {
                    waitedForScroll = true
                    delay(50)
                    continue
                }
                val windowsBefore = windowCount()
                val urlBefore = web.url.orEmpty()
                if (t.inView && t.hit) touch(t.x, t.y)
                else if (!page.jsClick()) { delay(10); continue }
                tries++
                val late = offset.serverNow() - openAt
                log("  클릭 ${tries}회째 (오픈 기준 %+.0f ms)".format(late * 1000) + if (t.inView && t.hit) "" else " · 스크립트 클릭")
                val (result, detail) = afterClick(windowsBefore, urlBefore, t.popups)
                if (result == "not_open") {
                    log("  '오픈 전' 응답 → 닫고 다시 누릅니다 ($detail)")
                    delay(150)
                    continue
                }
                if (result == "blocked") {
                    blocked = detail
                    log("  알림: $detail")
                    break
                }
                clicked = true
                if (result == "opened") log("  예매 화면으로 이동: ${detail.take(80)}")
                else log("  눌렀지만 화면 변화가 감지되지 않았습니다. 화면을 확인하세요.")
                break
            }
            // 오픈 후 reloadAfter 초가 지나도 안 열리면 새로고침 (그 뒤로도 같은 간격으로)
            val last = reloadedAt ?: openAt
            val reloading = reloadStartedAt?.let { localNow() - it < 10 } ?: false
            if (now > last + reloadAfter && !reloading) {
                log("  버튼이 아직 열리지 않음 → 새로고침")
                reloadStartedAt = localNow()
                waitedForScroll = false
                web.reload()
                continue
            }
            delay(10)
        }

        armed = false
        return when {
            clicked -> Outcome.CLICKED.also { log("예매창을 열었습니다. 이제부터 직접 진행하세요 (대기열/좌석/결제).") }
            blocked.isNotEmpty() -> Outcome.BLOCKED.also { log("사이트 알림 때문에 멈췄습니다. 내용을 확인하고 직접 진행하세요.") }
            else -> Outcome.MISSED.also { log("예매 버튼을 누르지 못했습니다. 직접 눌러주세요. (버튼 문구 설정을 확인하세요)") }
        }
    }

    companion object {
        // Replies a site gives when a booking button is pressed before the opening.
        val NOT_OPEN = Regex(
            "오픈\\s*전|오픈되지|오픈\\s*예정|아직.{0,10}(오픈|예매|판매)|" +
                "(예매|판매)\\s*(가능\\s*)?(시간|기간)이\\s*아닙|준비\\s*중"
        )
    }
}
