"""booking_assistant 창 프로그램. 더블클릭(또는 exe)으로 실행한다.

화면은 ui/ 의 HTML·CSS·JS 이고 Windows에 들어 있는 Edge WebView2로 그린다(pywebview).
이 파일은 화면과 예매 엔진(booking_assistant.browser.run)을 잇는다:
화면은 Api의 메서드를 부르고, 파이썬은 window.app.push(evt) 로 소식을 보낸다.
"""
from __future__ import annotations

import ctypes
import json
import os
import re
import sys
import threading
from datetime import datetime
from pathlib import Path

import webview

# The window only loads its own local page. Without this, WebView2 first runs proxy
# auto-detection, which delays the first page load by about 20 seconds.
os.environ.setdefault("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", "--no-proxy-server")

from booking_assistant import __version__, updater
from booking_assistant.browser import LOG_DIR, PROFILE_DIR, Stopped, run
from booking_assistant.inputfmt import normalize_date, normalize_time, parse_open_at
from booking_assistant.servertime import Offset, measure_servers, time_urls

SETTINGS = PROFILE_DIR.parent / "settings.json"
UI_DIR = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent)) / "ui"
WEEKDAYS = "월화수목금토일"
UPDATE_EVERY = 6 * 60 * 60

# Window background and title-bar colors per theme (same as ui/app.css): (bg, text, dark?)
THEMES = {
    "light": ("#F2F4F6", "#191F28", False),
    "dark": ("#0E1116", "#ECEFF3", True),
    "sand": ("#F4EFE6", "#2B241D", False),
    "midnight": ("#0B1020", "#E7EBF6", True),
    "mono": ("#FAFAFA", "#09090B", False),
}

CLICK_RE = re.compile(r"클릭 (\d+)회째 \(오픈 기준 ([+-]?\d+) ms\)")


def system_theme() -> str:
    try:
        import winreg
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                             r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize")
        return "light" if winreg.QueryValueEx(key, "AppsUseLightTheme")[0] else "dark"
    except Exception:
        return "light"


def load_settings() -> dict:
    try:
        return json.loads(SETTINGS.read_text(encoding="utf-8"))
    except Exception:
        return {}


def _colorref(hex_color: str) -> int:
    r, g, b = (int(hex_color[i:i + 2], 16) for i in (1, 3, 5))
    return r | (g << 8) | (b << 16)


class Api:
    """Methods the page calls. Underscored members stay hidden from the page."""

    def __init__(self) -> None:
        s = load_settings()
        self._settings = s
        self._theme = s.get("theme") if s.get("theme") in THEMES else system_theme()
        self._window: webview.Window | None = None
        self._ready = False
        self._pending: list[dict] = []
        self._lock = threading.Lock()
        self._offset: Offset | None = None
        self._stop = threading.Event()
        self._closed = False
        self._worker: threading.Thread | None = None
        self._update: updater.Update | None = None
        self._update_declined = False
        self._run_info: dict = {}

    # ---------------------------------------------------------- to the page
    def _push(self, evt: dict) -> None:
        with self._lock:
            if self._closed:
                return
            if not self._ready or self._window is None:
                self._pending.append(evt)
                return
        try:
            self._window.evaluate_js(f"window.app.push({json.dumps(evt, ensure_ascii=False)})")
        except Exception:
            pass

    def _log(self, msg: str) -> None:
        self._push({"type": "log", "time": f"{datetime.now():%H:%M:%S}", "text": msg.strip()})

    def _status(self, key: str, detail: str = "") -> None:
        self._push({"type": "status", "key": key, "detail": detail})

    # ---------------------------------------------------------- page calls
    def init(self) -> dict:
        with self._lock:
            self._ready = True
            events, self._pending = self._pending, []
        self.measure()
        threading.Timer(1.5, self._update_loop).start()
        self._paint_titlebar()
        return {"settings": self._settings, "theme": self._theme, "version": __version__,
                "frozen": updater.frozen(), "events": events}

    def preview(self, date_text: str, time_text: str) -> dict:
        d = normalize_date(date_text or "")
        t = normalize_time(time_text or "")
        at = parse_open_at(d or "", t or "") if d and t else None
        label = None
        if at is not None:
            x = datetime.fromtimestamp(at)
            label = f"{x.month}월 {x.day}일 ({WEEKDAYS[x.weekday()]}) {x:%H:%M:%S} 오픈"
        return {"date": d, "time": t, "open_at": at, "label": label}

    def save(self, values: dict) -> None:
        s = dict(self._settings)
        s.update({
            "url": str(values.get("url", "")).strip(),
            "date": normalize_date(values.get("date", "")) or str(values.get("date", "")).strip(),
            "time": normalize_time(values.get("time", "")) or str(values.get("time", "")).strip(),
            "keywords": str(values.get("keywords", "")),
            "labels": str(values.get("labels", "")),
            "reload_after": str(values.get("reload_after", "")),
            "early_press": bool(values.get("early_press", False)),
            "theme": self._theme,
        })
        self._settings = s
        SETTINGS.parent.mkdir(parents=True, exist_ok=True)
        SETTINGS.write_text(json.dumps(s, ensure_ascii=False, indent=2), encoding="utf-8")

    def set_theme(self, name: str) -> None:
        if name in THEMES:
            self._theme = name
            self.save(self._settings)
            self._paint_titlebar()

    def measure(self) -> None:
        def work() -> None:
            try:
                off = measure_servers(time_urls(self._settings.get("url", "")))
                self._offset = off
                self._push({"type": "offset", "ok": True, "seconds": off.seconds, "error": off.error,
                             "rtt": off.rtt, "host": off.host, "speed": off.speed()})
            except Exception as exc:
                self._push({"type": "offset", "ok": False, "message": str(exc)[:60]})
                self._log(f"서버 시간 측정 실패: {exc}")

        threading.Thread(target=work, daemon=True).start()

    def start(self, values: dict) -> dict:
        if self._worker and self._worker.is_alive():
            return {"ok": False, "error": "이미 실행 중입니다."}
        self.save(values)
        url = self._settings["url"]
        target = parse_open_at(self._settings["date"], self._settings["time"])
        labels = [x.strip() for x in self._settings["labels"].split(",") if x.strip()]
        keywords = self._settings["keywords"].split()
        try:
            reload_after = float(self._settings["reload_after"])
        except ValueError:
            reload_after = -1
        if not url.startswith("http"):
            return {"ok": False, "error": "공연 페이지 주소를 넣어 주세요."}
        if target is None:
            return {"ok": False, "error": "오픈 날짜/시각을 확인하세요. 예: 20261024 · 200000"}
        if not labels or reload_after <= 0:
            return {"ok": False, "error": "고급 설정의 버튼 문구와 새로고침 간격을 확인하세요."}
        if self._offset is None:
            return {"ok": False, "error": "서버 시간 측정이 끝난 뒤 시작하세요."}
        if target <= self._offset.server_now():
            return {"ok": False, "error": "오픈 시각이 이미 지났습니다."}

        self._stop.clear()
        self._run_info = {"tries": 0, "late_ms": None, "alert": ""}
        self._push({"type": "phase", "phase": "running"})
        self._status("busy", "Chrome 여는 중")
        offset, early = self._offset, bool(self._settings.get("early_press"))

        def work() -> None:
            message = ""
            try:
                self._log("Chrome을 엽니다…")
                run(url, target, offset, labels, keywords, reload_after=reload_after,
                    give_up_after=20.0, early=early, log=self._run_log, stop=self._stop)
                self._log("종료되었습니다.")
            except Stopped:
                self._log("중지했습니다.")
                self._status("idle")
            except Exception as exc:
                message = f"오류: {exc}"
                self._log(message)
                self._status("fail")
            finally:
                self._push({"type": "finished", "message": message})
                # 실행 중에 받아 둔 업데이트가 있으면 끝난 뒤에 묻는다.
                self._offer_update()

        self._worker = threading.Thread(target=work, daemon=True)
        self._worker.start()
        return {"ok": True}

    def stop(self) -> None:
        self._stop.set()

    def open_logs(self) -> None:
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        os.startfile(LOG_DIR)

    def check_update(self) -> None:
        threading.Thread(target=self._check_update, daemon=True).start()

    def decline_update(self) -> None:
        self._update_declined = True

    def install_update(self) -> None:
        if self._update is None:
            return
        try:
            updater.apply(self._update)
        except Exception as exc:
            self._push({"type": "update", "text": f"v{__version__} · 설치 실패: {exc}"})
            return
        self._close()

    # ---------------------------------------------------------- engine log
    def _run_log(self, msg: str) -> None:
        """Log callback for the browser runner; also drives the status chip and the result."""
        self._log(msg)
        m = CLICK_RE.search(msg)
        if m:
            self._run_info["tries"] = int(m.group(1))
            self._run_info["late_ms"] = int(m.group(2))
        if msg.strip().startswith("알림:"):
            self._run_info["alert"] = msg.strip()[3:].strip()
        if "예매창을 열었습니다" in msg:
            self._status("done")
            self._push({"type": "result", "outcome": "clicked", **self._result()})
        elif "누르지 못했" in msg or "알림 때문에" in msg:
            self._status("fail")
            text = self._run_info["alert"] or msg.strip()
            self._push({"type": "result", "outcome": "fail", "message": text, **self._result()})
        elif "다시 누릅니다" in msg:
            self._status("busy", "재시도 중")
        elif "감시" in msg or "활성" in msg or "오픈 예정" in msg:
            self._status("busy", "버튼 감시 중")
        elif "브라우저가 열렸" in msg:
            self._status("busy", "오픈 대기 중")

    def _result(self) -> dict:
        return {"late_ms": self._run_info.get("late_ms"), "tries": self._run_info.get("tries")}

    # ---------------------------------------------------------- updates
    def _update_loop(self) -> None:
        self._check_update()
        t = threading.Timer(UPDATE_EVERY, self._update_loop)
        t.daemon = True
        t.start()

    def _check_update(self) -> None:
        if not updater.frozen():
            self._push({"type": "update", "text": f"v{__version__} · 소스 실행은 자동 업데이트하지 않음"})
            return
        self._push({"type": "update", "text": f"v{__version__} · 업데이트 확인 중…"})
        try:
            found = updater.check()
        except Exception as exc:
            self._push({"type": "update", "text": f"v{__version__} · 업데이트 확인 실패"})
            self._log(f"업데이트 확인 실패: {exc}")
            return
        if found is None:
            self._push({"type": "update", "text": f"v{__version__} · 최신 버전"})
            return
        self._update, self._update_declined = found, False
        self._push({"type": "update", "text": f"v{__version__} · 새 버전 {found.version} 준비됨"})
        self._offer_update()

    def _offer_update(self) -> None:
        """Ask to install the downloaded update, but never while a run is in progress."""
        upd = self._update
        busy = self._worker is not None and self._worker.is_alive() and threading.current_thread() is not self._worker
        if upd is None or self._update_declined or busy:
            return
        notes = upd.notes if len(upd.notes) <= 1200 else upd.notes[:1200] + "…"
        self._push({"type": "update", "offer": {"version": upd.version, "notes": notes}})

    # ---------------------------------------------------------- window
    def _paint_titlebar(self) -> None:
        """Title bar in the theme's colors (Windows 11; older Windows just ignores it)."""
        try:
            bg, fg, dark = THEMES[self._theme]
            hwnd = int(self._window.native.Handle.ToInt64())
            dwm = ctypes.windll.dwmapi
            for attr, value in ((20, 1 if dark else 0), (35, _colorref(bg)), (36, _colorref(fg))):
                v = ctypes.c_int(value)
                dwm.DwmSetWindowAttribute(hwnd, attr, ctypes.byref(v), ctypes.sizeof(v))
        except Exception:
            pass

    def _on_closing(self) -> None:
        self._closed = True
        self._stop.set()
        try:
            self.save(self._settings)
        except Exception:
            pass
        if self._worker and self._worker.is_alive():
            self._worker.join(timeout=3)

    def _close(self) -> None:
        if self._window is not None:
            self._window.destroy()


def main() -> None:
    updater.cleanup()
    api = Api()
    window = webview.create_window(
        "NOL 예매 도우미", url=str(UI_DIR / "index.html"), js_api=api,
        width=500, height=900, min_size=(400, 620), background_color=THEMES[api._theme][0],
    )
    api._window = window
    window.events.closing += api._on_closing
    window.events.closed += api._on_closing
    webview.start(http_server=True)


if __name__ == "__main__":
    main()
