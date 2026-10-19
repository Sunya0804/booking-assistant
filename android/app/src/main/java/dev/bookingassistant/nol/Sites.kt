package dev.bookingassistant.nol

import java.net.URL

/**
 * What differs between ticket sites (same as booking_assistant/sites.py on PC).
 *
 * Melon has no booking button before the opening, so the page is reloaded at the opening,
 * which clears the date/time the user picked; the app picks them again from the keywords
 * ("10월 24일 18시"). On the mobile site 예매하기 opens a sheet of schedules to tap.
 */
data class Site(
    val name: String,
    val timeUrls: List<String>,
    val guestCookies: Set<String>,
    // Pages whose cookies we list for the login check.
    val cookieUrls: List<String>,
    // Melon: keywords pick the date/time instead of a card with several booking buttons.
    val picksSchedule: Boolean = false,
)

val NOL_SITE = Site(
    name = "NOL",
    timeUrls = TIME_URLS,
    guestCookies = setOf("nol_platform", "nol_device_id", "nol_app_version", "app-device", "cgntId",
        "yanolja_sid", "cf_clearance", "__cf_bm"),
    cookieUrls = listOf("https://nol.yanolja.com/", "https://tickets.interpark.com/"),
)

val MELON_SITE = Site(
    name = "멜론티켓",
    // All answer directly (no CDN) in ~20-40 ms.
    timeUrls = listOf("https://m.ticket.melon.com/", "https://ticket.melon.com/", "https://tktapi.melon.com/"),
    guestCookies = setOf("NetFunnel_ID", "PCID", "TKT_POC_ID", "_T_ANO", "ARY_CM", "search.perf",
        "JSESSIONID", "wcs_bt", "__T_", "__T_SECURE", "_fwb", "TSID", "TUID", "UUID", "_ISUID", "_SUID"),
    cookieUrls = listOf("https://m.ticket.melon.com/", "https://ticket.melon.com/"),
    picksSchedule = true,
)

fun siteFor(url: String): Site {
    val host = runCatching { URL(url.trim()).host }.getOrDefault("")
    return if (host.endsWith("melon.com")) MELON_SITE else NOL_SITE
}

// Messages that mean "log in first" (alert or in-page modal).
val LOGIN_NEEDED = Regex("로그인이\\s*필요|로그인\\s*후\\s*(이용|예매)|로그인\\s*하시겠|로그인\\s*페이지로")

// Melon PC alert when the date/time got lost: pick again and retry.
val PICK_AGAIN = Regex("날짜와\\s*시간을\\s*선택|회차를\\s*선택")

// "로그인" link in Melon's PC header when logged out, "로그아웃" when logged in → "in"/"out"/"unknown".
const val LOGIN_DOM_JS = """(function () {
  const vis = e => e.getBoundingClientRect().width > 0;
  const links = Array.from(document.querySelectorAll('#global_top_ticketLogin_Button, a, button')).filter(vis);
  if (links.some(e => /^\s*로그아웃\s*$/.test(e.innerText || ''))) return 'in';
  const top = document.getElementById('global_top_ticketLogin_Button');
  if (top && vis(top) && /^\s*로그인\s*$/.test(top.innerText || '')) return 'out';
  return 'unknown';
})()"""

// Pick Melon's date/time from the keywords (same script as sites.SCHEDULE_JS on PC).
// {status: none|wait|done|tap|ambiguous, picked, what, options, cx, cy, x, y}
const val SCHEDULE_JS = """(function (keywords) {
  const text = keywords.join(' ');
  const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  const clean = s => (s || '').replace(/\s+/g, ' ').trim();
  const want = { month: null, day: null, hour: null };
  let m = text.match(/(\d{1,2})\s*월\s*(\d{1,2})\s*일/) || text.match(/(?:^|\s)(\d{1,2})[./-](\d{1,2})(?=\s|$)/);
  if (m) { want.month = +m[1]; want.day = +m[2]; }
  else if ((m = text.match(/(\d{1,2})\s*일/))) want.day = +m[1];
  if ((m = text.match(/(\d{1,2})\s*시/) || text.match(/(\d{1,2}):\d{2}/))) want.hour = +m[1];
  const slotOf = (s, perfday) => {
    const o = { month: null, day: null, hour: null };
    if (perfday) { o.month = +perfday.slice(4, 6); o.day = +perfday.slice(6, 8); }
    let x = s.match(/\d{4}\s*[.년-]\s*(\d{1,2})\s*[.월-]\s*(\d{1,2})/);
    if (x) { o.month = +x[1]; o.day = +x[2]; }
    if ((x = s.match(/(\d{1,2})\s*시/) || s.match(/(\d{1,2}):\d{2}/))) o.hour = +x[1];
    return o;
  };
  const choose = (items, keys) => {
    const asked = keys.some(k => want[k] != null);
    const fit = items.filter(it => keys.every(k => want[k] == null || it.slot[k] == null || want[k] === it.slot[k]));
    if (fit.length === 1 && (asked || items.length === 1)) return fit[0];
    return null;
  };
  const ambiguous = (what, items) => ({ status: 'ambiguous', what, options: items.map(e => clean(e.innerText)).slice(0, 8) });
  const dates = Array.from(document.querySelectorAll('li[data-perfday]:not([data-scheduleno])')).filter(vis);
  if (dates.length) {
    const d = choose(dates.map(el => ({ el, slot: slotOf(clean(el.innerText), el.dataset.perfday) })), ['month', 'day']);
    if (!d) return ambiguous('날짜', dates);
    if (!d.el.classList.contains('on')) { d.el.click(); return { status: 'wait' }; }
    const times = Array.from(document.querySelectorAll('li[data-scheduleno]'))
      .filter(e => vis(e) && e.dataset.perfday === d.el.dataset.perfday);
    if (!times.length) return { status: 'wait' };
    let t = choose(times.map(el => ({ el, slot: slotOf(clean(el.innerText)) })), ['hour']);
    if (!t && want.hour == null) {
      if (times.length > 1) return ambiguous('시각', times);
      t = { el: times[0] };
    }
    if (!t) return ambiguous('시각', times);
    if (!t.el.classList.contains('on')) { t.el.click(); return { status: 'wait' }; }
    return { status: 'done', picked: clean(d.el.innerText) + ' ' + clean(t.el.innerText) };
  }
  const slots = Array.from(document.querySelectorAll('button.schedule_datalist_Btn')).filter(vis);
  if (slots.length) {
    const s = choose(slots.map(el => ({ el, slot: slotOf(clean(el.innerText)) })), ['month', 'day', 'hour']);
    if (!s) return ambiguous('회차', slots);
    // The mobile site ignores script clicks here; hand back where to tap for a real touch.
    const r = s.el.getBoundingClientRect();
    const vv = window.visualViewport || { offsetLeft: 0, offsetTop: 0, scale: 1 };
    const cx = r.left + r.width / 2, cy = r.top + r.height / 2, k = vv.scale * (window.devicePixelRatio || 1);
    // The sheet slides up from below; wait until the slot is on screen.
    if (cy < 0 || cy > innerHeight) return { status: 'wait' };
    return { status: 'tap', picked: clean(s.el.innerText), cx, cy, x: (cx - vv.offsetLeft) * k, y: (cy - vv.offsetTop) * k };
  }
  return { status: 'none' };
})"""
