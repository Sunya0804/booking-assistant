"""Open the event page in a real Chrome window and press the booking button on time.

Instead of reloading right before the opening (which can make us late when the
server is slow), we watch the booking button and click the moment it becomes
active. Reloading is only a fallback when it stays disabled after the opening.

NOL shows the booking button as clickable even before the opening and only
puts an "오픈 예정" note next to it, so a button with such a note counts as
"pending": we click when the note disappears or the opening time arrives,
whichever is first. If a click lands too early and the site answers with a
"not open yet" alert or popup, we dismiss it and click again.

Everything after the booking window opens (queue, CAPTCHA, seat choice,
payment) is left to the person at the keyboard.
"""
from __future__ import annotations

import re
import threading
import time
import winsound
from pathlib import Path
from typing import Callable
from urllib.parse import urlsplit

from playwright.sync_api import Locator, Page, sync_playwright

from .servertime import Offset
from .sites import LOGIN_DOM_JS, LOGIN_NEEDED_RE, PICK_AGAIN_RE, SCHEDULE_JS, Site, site_for

PROFILE_DIR = Path.home() / ".booking_assistant" / "chrome-profile"
LOG_DIR = PROFILE_DIR.parent / "logs"
KEEP_LOGS = 30


def _open_log_file(offset: Offset) -> tuple[Path, Callable[[str], None]]:
    """A file for this run's log; each line gets local and server time to the millisecond."""
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    for old in sorted(LOG_DIR.glob("run-*.txt"))[:-KEEP_LOGS + 1]:
        old.unlink(missing_ok=True)
    path = LOG_DIR / time.strftime("run-%Y%m%d-%H%M%S.txt")
    f = open(path, "a", encoding="utf-8", buffering=1)

    def write(msg: str) -> None:
        now = time.time()
        stamp = lambda t: time.strftime("%H:%M:%S", time.localtime(t)) + f".{int(t * 1000) % 1000:03d}"
        f.write(f"{stamp(now)} (서버 {stamp(now + offset.seconds)})  {msg}\n")

    return path, write


class Stopped(Exception):
    """Raised when the user cancels a run."""

# Button state:
#   "disabled" - disabled attribute / aria-disabled / disabled-looking class
#   "pending"  - clickable, but a nearby ancestor says it is not open yet
#   "ready"    - clickable with no such note
_STATE_JS = r"""(el, pendingRe) => {
  if (el.disabled || el.getAttribute('aria-disabled') === 'true') return 'disabled';
  const cls = (el.className && el.className.baseVal !== undefined ? el.className.baseVal : el.className) || '';
  if (/disable|inactive|soldout|is-off|dim/i.test(cls)) return 'disabled';
  const re = new RegExp(pendingRe, 'i');
  let node = el;
  for (let i = 0; i < 6 && node.parentElement; i++) {
    node = node.parentElement;
    const text = node.innerText || '';
    if (text.length > 400) break;   // left the button's own card
    if (re.test(text)) return 'pending';
  }
  return 'ready';
}"""

# Notes NOL puts next to a button that is not open yet.
PENDING_PATTERN = r"오픈\s*예정|공지된\s*일정에\s*맞춰|오픈\s*전|판매\s*예정"

# Replies a site gives when a booking button is pressed before the opening.
NOT_OPEN_RE = re.compile(
    r"오픈\s*전|오픈되지|오픈\s*예정|아직.{0,10}(오픈|예매|판매)|"
    r"(예매|판매)\s*(가능\s*)?(시간|기간)이\s*아닙|준비\s*중"
)
# Requests that say nothing about whether a click reached the site's servers.
_NOISE_RE = re.compile(r"google-analytics|googletagmanager|doubleclick|facebook|criteo|kakao.*pixel|"
                       r"/log|/beacon|/collect|analytics|tracking|sentry", re.I)
# If a click sends nothing to the server within this time, the page ignored it.
IGNORED_AFTER = 0.25


def _counts_as_request(req) -> bool:
    return req.resource_type in ("document", "xhr", "fetch") and not _NOISE_RE.search(req.url)


# After each click, every request and response the page makes for this long goes to the
# log file (not the screen), so a dry run shows which servers booking and queue use.
TRACE_FOR = 10.0
_STATIC_TYPES = ("image", "stylesheet", "font", "media")


def _short(url: str) -> str:
    """host/path without the query, which can be long and may carry personal tokens."""
    parts = urlsplit(url)
    return f"{parts.hostname}{parts.path}"[:120]


# Anything login-like beyond a site's guest cookies suggests a session. The real login
# cookie names are not known yet (see the log's list).
_LOGIN_COOKIE_RE = re.compile(r"access|refresh|token|auth|login|member|jwt|sso|_at$|_rt$", re.I)


def _check_login(ctx, page: Page, site: Site, log: Callable[[str], None], write: Callable[[str], None]) -> None:
    """Warn if the page or the cookies say we are not logged in, a minute before the opening."""
    names = sorted({c["name"] for c in ctx.cookies() if site.cookie_domain.search(c["domain"])})
    write(f"  쿠키 이름: {', '.join(names) or '(없음)'}")
    try:
        shown = page.evaluate(LOGIN_DOM_JS)
    except Exception:
        shown = "unknown"
    if shown == "in" or (shown == "unknown" and
                         any(n not in site.guest_cookies and _LOGIN_COOKIE_RE.search(n) for n in names)):
        log("  로그인 확인: " + ("화면에 로그아웃 버튼이 있습니다." if shown == "in" else "로그인 쿠키가 있습니다."))
        return
    log("  ⚠ 로그인이 안 된 것 같습니다. Chrome 창에서 로그인 상태를 확인하세요.")
    winsound.Beep(1000, 250)
    winsound.Beep(1000, 250)


_POPUP_SELECTOR = ("[role=dialog], [role=alertdialog], [class*=modal], [class*=Modal], "
                   "[class*=toast], [class*=Toast], [class*=alert], [class*=popup], [class*=Popup], "
                   "[class*=alarm_pop]")  # Melon: div.layer_alarm_pop


def _wait_until(offset: Offset, target: float, label: str,
                log: Callable[[str], None], stop: threading.Event) -> None:
    """Sleep until server time reaches `target`, printing a countdown."""
    last_shown = None
    while True:
        if stop.is_set():
            raise Stopped
        remaining = target - offset.server_now()
        if remaining <= 0:
            return
        shown = int(remaining)
        if shown != last_shown and (shown <= 10 or shown % 30 == 0):
            log(f"  {label} 까지 {shown}s")
            last_shown = shown
        if remaining > 0.05:
            time.sleep(min(remaining - 0.03, 0.2))


# Text of the smallest ancestor that describes the button's game/session
# (e.g. "준플레이오프 3차전 10월 20일(화) 18:30 잠실야구장 ... 예매하기").
_CARD_TEXT_JS = r"""el => {
  let card = el;
  for (let i = 0; i < 8 && card.parentElement; i++) {
    card = card.parentElement;
    if (card.innerText.trim().length > el.innerText.trim().length + 15) break;
  }
  return card.innerText.replace(/\s+/g, ' ').trim();
}"""


def _candidates(page: Page, labels: list[str]) -> list[tuple[Locator, str]]:
    """All booking buttons on the page with the text of the card around each."""
    found = []
    for label in labels:
        loc = page.get_by_role("link", name=label).or_(page.get_by_role("button", name=label))
        for i in range(loc.count()):
            btn = loc.nth(i)
            try:
                found.append((btn, btn.evaluate(_CARD_TEXT_JS)))
            except Exception:
                continue
        if found:
            break
    return found


def _find_button(page: Page, labels: list[str], keywords: list[str]) -> Locator | None:
    """The booking button whose card contains every keyword (first one if no keywords)."""
    for btn, card in _candidates(page, labels):
        if all(k in card for k in keywords):
            return btn
    return None


def describe_candidates(page: Page, labels: list[str], keywords: list[str]) -> list[str]:
    lines = []
    for _, card in _candidates(page, labels):
        mark = "▶" if keywords and all(k in card for k in keywords) else " "
        lines.append(f"  {mark} {card[:70]}")
    return lines


def _state(btn: Locator) -> str:
    try:
        return btn.evaluate(_STATE_JS, PENDING_PATTERN)
    except Exception:
        return "disabled"


def _is_active(btn: Locator) -> bool:
    return _state(btn) != "disabled"


def _not_open_popups(page: Page) -> list[Locator]:
    """Visible popups/toasts on the page saying booking is not open yet."""
    found = []
    try:
        loc = page.locator(_POPUP_SELECTOR).filter(has_text=NOT_OPEN_RE)
        for i in range(min(loc.count(), 10)):
            if loc.nth(i).is_visible():
                found.append(loc.nth(i))
    except Exception:
        pass
    return found


def _dismiss(page: Page, popup: Locator) -> None:
    try:
        popup.get_by_role("button", name=re.compile("확인|닫기|OK|close", re.I)).first.click(timeout=300)
    except Exception:
        page.keyboard.press("Escape")


def _visible_popup_text(page: Page, pattern: re.Pattern) -> str:
    """Text of a visible popup/modal matching `pattern`, or ""."""
    try:
        loc = page.locator(_POPUP_SELECTOR).filter(has_text=pattern)
        for i in range(min(loc.count(), 10)):
            if loc.nth(i).is_visible():
                return " ".join(loc.nth(i).inner_text().split())[:60]
    except Exception:
        pass
    return ""


def _after_click(page: Page, pages_before: int, url_before: str, dialogs: list[str],
                 popups_before: int, requests: list[float], timeout: float = 2.0,
                 site: Site | None = None, keywords: list[str] | None = None,
                 allow_pick: bool = True) -> tuple[str, str]:
    """Watch what the click did. Returns (result, detail).

    result: "not_open" (too early, try again), "pick_again" (Melon lost the date/time),
            "picked" (Melon mobile: chose a slot in the sheet that opened; keep watching),
            "blocked" (some other alert, e.g. log in first),
            "opened" (new window / page change), "ignored" (the page sent nothing to
            its servers, so it dropped the click itself and pressing again is safe)
            or "unknown" (a request went out but nothing visible came back).
    """
    ctx = page.context
    start = time.time()
    end = start + timeout
    while time.time() < end:
        if dialogs:
            msg = dialogs.pop(0)
            if LOGIN_NEEDED_RE.search(msg):
                return "blocked", f"로그인이 필요합니다. 먼저 로그인해 두세요. ({msg[:40]})"
            if PICK_AGAIN_RE.search(msg):
                return "pick_again", msg
            return ("not_open" if NOT_OPEN_RE.search(msg) else "blocked", msg)
        login = _visible_popup_text(page, LOGIN_NEEDED_RE)
        if login:
            return "blocked", f"로그인이 필요합니다. 먼저 로그인해 두세요. ({login[:40]})"
        if site is not None and site.picks_schedule and allow_pick:
            try:
                r = page.evaluate(SCHEDULE_JS, keywords or [])
            except Exception:
                r = {"status": "none"}
            if r.get("status") == "tap":
                # A real mouse click: the mobile site ignores script clicks on the schedule.
                page.mouse.click(r["cx"], r["cy"])
                return "picked", r.get("picked", "")
            if r.get("status") == "ambiguous":
                return "blocked", f"{r.get('what')}를 정할 수 없습니다. 키워드를 확인하세요: {' / '.join(r.get('options', []))}"
        popups = _not_open_popups(page)
        if len(popups) > popups_before:
            text = " ".join(popups[-1].inner_text().split())[:60]
            _dismiss(page, popups[-1])
            return "not_open", text
        if len(ctx.pages) > pages_before:
            return "opened", ctx.pages[-1].url
        if page.url != url_before:
            if "login" in page.url.lower():
                return "blocked", "로그인 페이지로 이동했습니다. 먼저 로그인해 두세요."
            return "opened", page.url
        if time.time() - start >= IGNORED_AFTER and not any(t >= start for t in requests):
            return "ignored", ""
        page.wait_for_timeout(50)
    return "unknown", ""


def _pick_schedule(page: Page, keywords: list[str], timeout: float = 3.0) -> tuple[str, str]:
    """Pick Melon's date/time (PC lists). Returns (done|none|ambiguous, detail)."""
    end = time.time() + timeout
    while time.time() < end:
        try:
            r = page.evaluate(SCHEDULE_JS, keywords)
        except Exception:
            r = {"status": "wait"}
        status = r.get("status")
        if status == "done":
            return "done", r.get("picked", "")
        if status == "ambiguous":
            return "ambiguous", f"{r.get('what')}를 정할 수 없습니다. 키워드를 확인하세요: {' / '.join(r.get('options', []))}"
        if status == "none":
            return "none", ""
        page.wait_for_timeout(30)
    return "none", "회차 목록이 늦게 떠서 그대로 누릅니다"


def run(url: str, open_at: float, offset: Offset, labels: list[str], keywords: list[str],
        reload_after: float, give_up_after: float, early: bool = False,
        log: Callable[[str], None] = print, stop: threading.Event | None = None) -> None:
    stop = stop or threading.Event()
    PROFILE_DIR.mkdir(parents=True, exist_ok=True)
    log_path, write = _open_log_file(offset)
    site = site_for(url)
    # Melon: one booking button; the keywords pick the date/time instead of a card.
    button_keywords = [] if site.picks_schedule else keywords
    show = log

    def log(msg: str) -> None:
        write(msg)
        show(msg)

    log(f"기록 파일: {log_path}")
    log(f"  사이트: {site.name}")
    if site.picks_schedule:
        log(f"  회차 키워드: {' '.join(keywords)}" if keywords else
            "  ⚠ 회차 키워드(예: 10월 24일 18시)가 없어 회차가 하나뿐일 때만 고릅니다.")
    log(f"  기준 서버 {offset.host} · 오프셋 {offset.seconds * 1000:+.0f} ms, "
        f"오차 ±{offset.error * 1000:.0f} ms, 왕복 {offset.rtt * 1000:.0f} ms ({offset.speed()})")
    # 미리 누르기: 요청이 서버에 닿는 시간만큼 일찍 눌러 정각에 도착하게 한다.
    lead = offset.lead() if early else 0.0
    if early:
        log(f"  미리 누르기: {lead * 1000:.0f} ms 일찍 누릅니다")
    press_at = open_at - lead
    with sync_playwright() as pw:
        ctx = pw.chromium.launch_persistent_context(
            str(PROFILE_DIR), channel="chrome", headless=False, no_viewport=True,
        )
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        # Times of requests the page sends to its servers; tells an ignored click from a slow one.
        requests: list[float] = []
        trace_until = [0.0]

        def on_request(req) -> None:
            if _counts_as_request(req):
                requests.append(time.time())
            if time.time() < trace_until[0] and req.resource_type not in _STATIC_TYPES:
                write(f"    → {req.method} {_short(req.url)}")

        def on_response(resp) -> None:
            if time.time() < trace_until[0] and resp.request.resource_type not in _STATIC_TYPES:
                write(f"    ← {resp.status} {_short(resp.url)}")

        ctx.on("request", on_request)
        ctx.on("response", on_response)
        page.goto(url)
        log("브라우저가 열렸습니다. 로그인이 안 되어 있다면 지금 로그인하고, 날짜/회차를 미리 골라두세요.")

        _wait_until(offset, open_at - 60, "로그인 확인", log, stop)
        _check_login(ctx, page, site, log, write)
        _wait_until(offset, open_at - 10, "버튼 감시 시작", log, stop)
        cands = describe_candidates(page, labels, button_keywords)
        if len(cands) > 1 or keywords:
            log(f"  페이지의 예매 버튼 {len(cands)}개" + (f" (키워드: {' '.join(keywords)})" if keywords else ""))
            for line in cands:
                log(line)
            if len(cands) > 1 and not keywords:
                log("  ⚠ 경기/회차 키워드가 없어 첫 번째 버튼을 누릅니다.")
        dialogs: list[str] = []

        def on_dialog(dialog) -> None:
            dialogs.append(dialog.message)
            try:
                dialog.accept()
            except Exception:
                pass

        page.on("dialog", on_dialog)

        btn = _find_button(page, labels, button_keywords)
        first_state = _state(btn) if btn is not None else None
        # No button yet (Melon shows it only after the opening): reload right at the opening.
        reload_at_open = btn is None
        schedule_ready = False
        if site.picks_schedule and btn is not None:
            # The button is already there: pick the date/time now instead of after the opening.
            status, detail = _pick_schedule(page, keywords)
            if status == "done":
                log(f"  회차 미리 선택: {detail}")
                schedule_ready = True
            elif status == "ambiguous":
                log(f"  ⚠ {detail}")
        if btn is None:
            log("  예매 버튼이 아직 없습니다. 오픈 시각에 바로 새로고침해서 찾습니다.")
        elif first_state == "ready":
            # 재오픈(N차) 공연 등: 버튼이 이미 열려 있으면 정각에 누른다.
            log("  버튼이 이미 활성 상태입니다. 오픈 시각 정각에 누릅니다.")
            _wait_until(offset, press_at, "오픈", log, stop)
        elif first_state == "pending":
            log("  '오픈 예정' 표시가 있습니다. 표시가 사라지거나 정각이 되면 누릅니다.")
        else:
            log("  버튼이 비활성 상태입니다. 활성화되는 즉시 누릅니다.")

        clicked = False
        blocked = ""
        tries = 0
        reloaded_at = None
        prev_state = first_state
        while not clicked:
            if stop.is_set():
                raise Stopped
            now = offset.server_now()
            if now > open_at + give_up_after:
                break
            # 찾은 버튼은 재사용하고, 못 찾았거나 새로고침한 뒤에만 다시 찾는다.
            if btn is None:
                btn = _find_button(page, labels, button_keywords)
            state = _state(btn) if btn is not None else "disabled"
            if prev_state == "pending" and state == "ready":
                # 표시가 내 시계가 아니라 사이트 시계로 사라지는지 예행연습 기록으로 확인한다.
                log(f"  '오픈 예정' 표시가 사라짐 (오픈 기준 {(now - open_at) * 1000:+.0f} ms)")
            prev_state = state
            if state == "ready" or (state == "pending" and now >= press_at):
                if site.picks_schedule and not schedule_ready:
                    status, detail = _pick_schedule(page, keywords)
                    if status == "ambiguous":
                        blocked = detail
                        log(f"  알림: {detail}")
                        break
                    if status == "done":
                        log(f"  회차 선택: {detail}")
                    schedule_ready = True
                pages_before, url_before = len(page.context.pages), page.url
                popups_before = len(_not_open_popups(page))
                try:
                    btn.click(timeout=500, no_wait_after=True)
                except Exception:
                    btn = None
                    time.sleep(0.01)
                    continue
                tries += 1
                trace_until[0] = time.time() + TRACE_FOR
                clicked_at = offset.server_now()
                log(f"  클릭 {tries}회째 (오픈 기준 {(clicked_at - open_at) * 1000:+.0f} ms)")
                result, detail = _after_click(page, pages_before, url_before, dialogs, popups_before, requests,
                                              site=site, keywords=keywords)
                if result == "picked":
                    # Only once per press: the sheet stays on screen while it closes.
                    log(f"  회차 선택: {detail}")
                    result, detail = _after_click(page, pages_before, url_before, dialogs, popups_before,
                                                  requests, site=site, keywords=keywords, allow_pick=False)
                if result == "pick_again":
                    log(f"  '{detail}' → 회차를 다시 고르고 누릅니다")
                    schedule_ready = False
                    continue
                if result == "not_open":
                    log(f"  '오픈 전' 응답 → 닫고 다시 누릅니다 ({detail})")
                    time.sleep(0.15)
                    continue
                if result == "ignored":
                    # 페이지가 서버에 아무것도 보내지 않았다 = 사이트가 아직 안 열렸다고 보고 버튼을 무시했다.
                    log("  사이트가 클릭을 무시함 (요청 없음) → 다시 누릅니다")
                    continue
                if result == "blocked":
                    blocked = detail
                    log(f"  알림: {detail}")
                    break
                clicked = True
                if result == "opened":
                    log(f"  예매 화면으로 이동: {detail[:80]}")
                else:
                    log("  눌렀지만 화면 변화가 감지되지 않았습니다. Chrome 화면을 확인하세요.")
                break
            # 오픈 후 reload_after 초가 지나도 안 열리면 새로고침 (그 뒤로도 같은 간격으로)
            last = reloaded_at or open_at
            if (reload_at_open and reloaded_at is None and now >= press_at) or now > last + reload_after:
                log("  버튼이 아직 열리지 않음 → 새로고침")
                t0 = time.time()
                page.reload(wait_until="domcontentloaded")
                log(f"  새로고침 {time.time() - t0:.2f}s 걸림")
                reloaded_at = offset.server_now()
                btn = None
                schedule_ready = False
                continue
            time.sleep(0.01)

        winsound.Beep(1500, 400)
        if clicked:
            log("예매창을 열었습니다. 이제부터 직접 진행하세요 (대기열/좌석/결제).")
        elif blocked:
            log("사이트 알림 때문에 멈췄습니다. 내용을 확인하고 직접 진행하세요.")
        else:
            log("예매 버튼을 누르지 못했습니다. 직접 눌러주세요. (버튼 문구 설정을 확인하세요)")
        log("Chrome 창을 닫으면 끝납니다.")
        # Keep Playwright's event loop alive until the user closes Chrome.
        while not stop.is_set():
            try:
                if not ctx.pages:
                    break
                ctx.pages[0].wait_for_timeout(200)
            except Exception:
                break
