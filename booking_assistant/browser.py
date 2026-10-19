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

from playwright.sync_api import Locator, Page, sync_playwright

from .servertime import Offset

PROFILE_DIR = Path.home() / ".booking_assistant" / "chrome-profile"


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
_POPUP_SELECTOR = ("[role=dialog], [role=alertdialog], [class*=modal], [class*=Modal], "
                   "[class*=toast], [class*=Toast], [class*=alert], [class*=popup], [class*=Popup]")


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


def _after_click(page: Page, pages_before: int, url_before: str, dialogs: list[str],
                 popups_before: int, timeout: float = 2.0) -> tuple[str, str]:
    """Watch what the click did. Returns (result, detail).

    result: "not_open" (too early, try again), "blocked" (some other alert),
            "opened" (new window / page change) or "unknown" (nothing visible).
    """
    ctx = page.context
    end = time.time() + timeout
    while time.time() < end:
        if dialogs:
            msg = dialogs.pop(0)
            return ("not_open" if NOT_OPEN_RE.search(msg) else "blocked", msg)
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
        page.wait_for_timeout(50)
    return "unknown", ""


def run(url: str, open_at: float, offset: Offset, labels: list[str], keywords: list[str],
        reload_after: float, give_up_after: float,
        log: Callable[[str], None] = print, stop: threading.Event | None = None) -> None:
    stop = stop or threading.Event()
    PROFILE_DIR.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as pw:
        ctx = pw.chromium.launch_persistent_context(
            str(PROFILE_DIR), channel="chrome", headless=False, no_viewport=True,
        )
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        page.goto(url)
        log("브라우저가 열렸습니다. 로그인이 안 되어 있다면 지금 로그인하고, 날짜/회차를 미리 골라두세요.")

        _wait_until(offset, open_at - 10, "버튼 감시 시작", log, stop)
        cands = describe_candidates(page, labels, keywords)
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

        btn = _find_button(page, labels, keywords)
        first_state = _state(btn) if btn is not None else None
        if btn is None:
            log("  조건에 맞는 예매 버튼이 아직 없습니다. 오픈 시각부터 찾습니다.")
        elif first_state == "ready":
            # 재오픈(N차) 공연 등: 버튼이 이미 열려 있으면 정각에 누른다.
            log("  버튼이 이미 활성 상태입니다. 오픈 시각 정각에 누릅니다.")
            _wait_until(offset, open_at, "오픈", log, stop)
        elif first_state == "pending":
            log("  '오픈 예정' 표시가 있습니다. 표시가 사라지거나 정각이 되면 누릅니다.")
        else:
            log("  버튼이 비활성 상태입니다. 활성화되는 즉시 누릅니다.")

        clicked = False
        blocked = ""
        tries = 0
        reloaded_at = None
        while not clicked:
            if stop.is_set():
                raise Stopped
            now = offset.server_now()
            if now > open_at + give_up_after:
                break
            # 찾은 버튼은 재사용하고, 못 찾았거나 새로고침한 뒤에만 다시 찾는다.
            if btn is None:
                btn = _find_button(page, labels, keywords)
            state = _state(btn) if btn is not None else "disabled"
            if state == "ready" or (state == "pending" and now >= open_at):
                pages_before, url_before = len(page.context.pages), page.url
                popups_before = len(_not_open_popups(page))
                try:
                    btn.click(timeout=500, no_wait_after=True)
                except Exception:
                    btn = None
                    time.sleep(0.01)
                    continue
                tries += 1
                late = offset.server_now() - open_at
                log(f"  클릭 {tries}회째 (오픈 기준 {late * 1000:+.0f} ms)")
                result, detail = _after_click(page, pages_before, url_before, dialogs, popups_before)
                if result == "not_open":
                    log(f"  '오픈 전' 응답 → 닫고 다시 누릅니다 ({detail})")
                    time.sleep(0.15)
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
            if now > last + reload_after:
                log("  버튼이 아직 열리지 않음 → 새로고침")
                t0 = time.time()
                page.reload(wait_until="domcontentloaded")
                log(f"  새로고침 {time.time() - t0:.2f}s 걸림")
                reloaded_at = offset.server_now()
                btn = None
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
