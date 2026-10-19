"""What differs between ticket sites: time servers, login check, and Melon's schedule picking.

NOL (nol.yanolja.com, ex-Interpark): the booking button sits on the page with an
"오픈 예정" note; date/time are chosen before the opening and survive until the click.

Melon (ticket.melon.com, m.ticket.melon.com): there is no booking button before the
opening, so the page is reloaded at the opening, which also clears any date/time the
user picked. The app picks them again from the keywords ("10월 24일 18시"):
  PC     date li[data-perfday] -> time li[data-scheduleno] (loaded ~150 ms later) -> 예매하기
  mobile 예매하기 -> sheet of button.schedule_datalist_Btn "2026.10.24(토) 18시 00분" -> tap
Seat choice, CAPTCHA and payment stay with the person.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Site:
    name: str
    # Servers whose clock we measure (plus the event page's own server).
    time_urls: tuple[str, ...]
    # Cookies a logged-out visit already gets; see the login check in browser.py.
    guest_cookies: frozenset[str]
    # Melon: keywords pick the date/time instead of a card with several booking buttons.
    picks_schedule: bool = False
    cookie_domain: re.Pattern = field(default=re.compile(r"yanolja|interpark|nol\."))


NOL = Site(
    name="NOL",
    time_urls=("https://poticket.interpark.com/", "https://ticket.interpark.com/", "https://tickets.interpark.com/"),
    guest_cookies=frozenset({"nol_platform", "nol_device_id", "nol_app_version", "app-device", "cgntId",
                             "yanolja_sid", "cf_clearance", "__cf_bm"}),
)

MELON = Site(
    name="멜론티켓",
    # All answer directly (no CDN) in ~20-40 ms; m.ticket is usually the quickest.
    time_urls=("https://m.ticket.melon.com/", "https://ticket.melon.com/", "https://tktapi.melon.com/"),
    guest_cookies=frozenset({"NetFunnel_ID", "PCID", "TKT_POC_ID", "_T_ANO", "ARY_CM", "search.perf",
                             "JSESSIONID", "wcs_bt", "__T_", "__T_SECURE", "_fwb",
                             "TSID", "TUID", "UUID", "_ISUID", "_SUID"}),
    picks_schedule=True,
    cookie_domain=re.compile(r"melon\.com"),
)


def site_for(url: str) -> Site:
    host = urlsplit(url.strip()).hostname or ""
    return MELON if host.endswith("melon.com") else NOL


# "로그인" link in Melon's PC header when logged out, "로그아웃" when logged in.
# Returns "in", "out" or "unknown" (other sites, Melon mobile).
LOGIN_DOM_JS = r"""() => {
  const vis = e => e.getBoundingClientRect().width > 0;
  const links = Array.from(document.querySelectorAll('#global_top_ticketLogin_Button, a, button')).filter(vis);
  if (links.some(e => /^\s*로그아웃\s*$/.test(e.innerText || ''))) return 'in';
  const top = document.getElementById('global_top_ticketLogin_Button');
  if (top && vis(top) && /^\s*로그인\s*$/.test(top.innerText || '')) return 'out';
  return 'unknown';
}"""

# Pick Melon's date/time from the keywords. Called repeatedly until it says "done"
# (PC: date click loads the times) or "tap" (mobile sheet: where to tap).
#   {status: none|wait|done|tap|ambiguous, picked, what, options, cx, cy, x, y}
SCHEDULE_JS = r"""(keywords) => {
  const text = keywords.join(' ');
  const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  const clean = s => (s || '').replace(/\s+/g, ' ').trim();
  // What the person asked for: month/day and hour, any of them may be missing.
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

  // PC: date list, then the times for that date.
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
      // Melon selects the first time by itself; only a single choice is safe to accept silently.
      if (times.length > 1) return ambiguous('시각', times);
      t = { el: times[0] };
    }
    if (!t) return ambiguous('시각', times);
    if (!t.el.classList.contains('on')) { t.el.click(); return { status: 'wait' }; }
    return { status: 'done', picked: clean(d.el.innerText) + ' ' + clean(t.el.innerText) };
  }
  // Mobile: the sheet that opens after 예매하기.
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
}"""

# Messages that mean "log in first" (alert or in-page modal).
LOGIN_NEEDED_RE = re.compile(r"로그인이\s*필요|로그인\s*후\s*(이용|예매)|로그인\s*하시겠|로그인\s*페이지로")
# Melon PC alert when the date/time got lost (e.g. after a reload): pick again and retry.
PICK_AGAIN_RE = re.compile(r"날짜와\s*시간을\s*선택|회차를\s*선택")
