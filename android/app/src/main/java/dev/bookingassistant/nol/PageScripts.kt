package dev.bookingassistant.nol

import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * JavaScript helpers that run inside the event page.
 *
 * The button search, state check and card text are the same rules as
 * booking_assistant/browser.py (_STATE_JS, _CARD_TEXT_JS, PENDING_PATTERN,
 * NOT_OPEN_RE), so both versions decide alike. The library is sent with every
 * call and installs itself only if the page does not have it yet, which keeps
 * it working after a reload or navigation.
 */
private const val LIBRARY = """
(function () {
  if (window.__nolBA) return;
  // Notes NOL puts next to a button that is not open yet.
  const PENDING = /오픈\s*예정|공지된\s*일정에\s*맞춰|오픈\s*전|판매\s*예정/i;
  // Replies a site gives when a booking button is pressed before the opening.
  const NOT_OPEN = /오픈\s*전|오픈되지|오픈\s*예정|아직.{0,10}(오픈|예매|판매)|(예매|판매)\s*(가능\s*)?(시간|기간)이\s*아닙|준비\s*중/;
  const POPUPS = '[role=dialog], [role=alertdialog], [class*=modal], [class*=Modal], ' +
                 '[class*=toast], [class*=Toast], [class*=alert], [class*=popup], [class*=Popup], ' +
                 '[class*=alarm_pop]';  // Melon: div.layer_alarm_pop
  const CLICKABLE = 'a, button, [role=button], [role=link], input[type=button], input[type=submit]';

  function visible(el) {
    const r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return false;
    const s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none';
  }
  function name(el) {
    return (el.getAttribute('aria-label') || el.innerText || el.value || '').replace(/\s+/g, ' ').trim();
  }
  // Text of the smallest ancestor that describes the button's game/session.
  function cardText(el) {
    let card = el;
    for (let i = 0; i < 8 && card.parentElement; i++) {
      card = card.parentElement;
      if (card.innerText.trim().length > el.innerText.trim().length + 15) break;
    }
    return card.innerText.replace(/\s+/g, ' ').trim();
  }
  // All booking buttons on the page with the text of the card around each.
  function candidates(labels) {
    const all = Array.from(document.querySelectorAll(CLICKABLE)).filter(visible);
    for (const label of labels) {
      let found = all.filter(el => name(el).includes(label));
      // <a><button>예매하기</button></a> matches twice; keep the inner one.
      found = found.filter(el => !found.some(o => o !== el && el.contains(o)));
      if (found.length) return found.map(el => [el, cardText(el)]);
    }
    return [];
  }
  function matches(card, keywords) { return keywords.every(k => card.includes(k)); }
  function state(el) {
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') return 'disabled';
    const cls = (el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className) || '';
    if (/disable|inactive|soldout|is-off|dim/i.test(cls)) return 'disabled';
    let node = el;
    for (let i = 0; i < 6 && node.parentElement; i++) {
      node = node.parentElement;
      const text = node.innerText || '';
      if (text.length > 400) break;   // left the button's own card
      if (PENDING.test(text)) return 'pending';
    }
    return 'ready';
  }
  function notOpenPopups() {
    return Array.from(document.querySelectorAll(POPUPS))
      .filter(p => visible(p) && NOT_OPEN.test(p.innerText || ''))
      .slice(0, 10);
  }

  window.__nolBA = {
    btn: null,
    describe(labels, keywords) {
      return candidates(labels).map(([, card]) => ({ match: keywords.length > 0 && matches(card, keywords), card }));
    },
    // Find (or reuse) the button and report where to touch it, in view pixels.
    tick(labels, keywords, scroll) {
      let btn = this.btn;
      if (!btn || !btn.isConnected) {
        const hit = candidates(labels).find(([, card]) => matches(card, keywords));
        btn = this.btn = hit ? hit[0] : null;
      }
      const out = { found: !!btn, state: 'disabled', popups: notOpenPopups().length, url: location.href };
      if (!btn) return out;
      out.state = state(btn);
      const r = btn.getBoundingClientRect();
      const vv = window.visualViewport || { offsetLeft: 0, offsetTop: 0, width: innerWidth, height: innerHeight, scale: 1 };
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      out.inView = cx > vv.offsetLeft && cx < vv.offsetLeft + vv.width && cy > vv.offsetTop && cy < vv.offsetTop + vv.height;
      if (!out.inView && scroll) btn.scrollIntoView({ block: 'center', inline: 'center' });
      const top = out.inView ? document.elementFromPoint(cx, cy) : null;
      // Something (sticky bar, overlay) covering the button would get the touch instead.
      out.hit = !!top && (top === btn || btn.contains(top));
      const k = vv.scale * (window.devicePixelRatio || 1);
      out.x = (cx - vv.offsetLeft) * k;
      out.y = (cy - vv.offsetTop) * k;
      return out;
    },
    jsClick() { if (this.btn) this.btn.click(); return !!this.btn; },
    popupCount() { return notOpenPopups().length; },
    loginPopup() {
      const LOGIN = /로그인이\s*필요|로그인\s*후\s*(이용|예매)|로그인\s*하시겠|로그인\s*페이지로/;
      const p = Array.from(document.querySelectorAll(POPUPS)).find(p => visible(p) && LOGIN.test(p.innerText || ''));
      return p ? (p.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 60) : '';
    },
    // Close the newest "not open yet" popup; returns its text.
    dismissLast() {
      const list = notOpenPopups();
      const p = list[list.length - 1];
      if (!p) return '';
      const text = (p.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 60);
      const close = Array.from(p.querySelectorAll('button, a, [role=button]'))
        .find(b => /확인|닫기|OK|close/i.test(name(b)));
      if (close) close.click();
      else document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      return text;
    },
  };
})();
"""

data class Tick(
    val found: Boolean,
    val state: String,      // "disabled" | "pending" | "ready"
    val popups: Int,
    val url: String,
    val inView: Boolean,
    val hit: Boolean,
    val x: Float,
    val y: Float,
)

data class Candidate(val match: Boolean, val card: String)

/** Talks to the page loaded in [webView]. Call from the main thread. */
class PageScripts(private val webView: WebView) {

    /** Evaluate `__nolBA.<call>` and return its JSON result ("null" when the page is busy). */
    private suspend fun call(call: String): String = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript("$LIBRARY\n(window.__nolBA ? window.__nolBA.$call : null);") {
            if (cont.isActive) cont.resume(it ?: "null")
        }
    }

    private fun args(vararg list: Any): String = list.joinToString(",") {
        when (it) {
            is List<*> -> JSONArray(it).toString()
            is String -> JSONObject.quote(it)
            else -> it.toString()
        }
    }

    suspend fun describe(labels: List<String>, keywords: List<String>): List<Candidate> {
        val raw = call("describe(${args(labels, keywords)})")
        if (!raw.startsWith("[")) return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Candidate(o.optBoolean("match"), o.optString("card"))
        }
    }

    suspend fun tick(labels: List<String>, keywords: List<String>, scroll: Boolean): Tick? {
        val raw = call("tick(${args(labels, keywords, scroll)})")
        if (!raw.startsWith("{")) return null
        val o = JSONObject(raw)
        return Tick(
            found = o.optBoolean("found"),
            state = o.optString("state", "disabled"),
            popups = o.optInt("popups"),
            url = o.optString("url"),
            inView = o.optBoolean("inView"),
            hit = o.optBoolean("hit"),
            x = o.optDouble("x", 0.0).toFloat(),
            y = o.optDouble("y", 0.0).toFloat(),
        )
    }

    suspend fun jsClick(): Boolean = call("jsClick()") == "true"

    /** Evaluate a stand-alone expression (no library) and return its JSON result. */
    private suspend fun eval(expr: String): String = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(expr) { if (cont.isActive) cont.resume(it ?: "null") }
    }

    /** "in", "out" or "unknown" from the page's own login link (Melon PC). */
    suspend fun loginState(): String = eval(LOGIN_DOM_JS).trim('"')

    /** Pick Melon's date/time; see SCHEDULE_JS. */
    suspend fun schedule(keywords: List<String>): JSONObject? {
        val raw = eval("$SCHEDULE_JS(${JSONArray(keywords)})")
        return if (raw.startsWith("{")) JSONObject(raw) else null
    }

    /** Text of a visible "log in first" modal, or "". */
    suspend fun loginPopup(): String {
        val raw = call("loginPopup()")
        return if (raw.startsWith("\"")) JSONArray("[$raw]").getString(0) else ""
    }

    suspend fun popupCount(): Int = call("popupCount()").toIntOrNull() ?: 0

    suspend fun dismissLast(): String {
        val raw = call("dismissLast()")
        return if (raw.startsWith("\"")) JSONArray("[$raw]").getString(0) else ""
    }
}
