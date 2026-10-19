"""booking_assistant 창 프로그램. 더블클릭(또는 exe)으로 실행한다."""
from __future__ import annotations

import ctypes
import json
import queue
import threading
import tkinter as tk
from datetime import datetime
from tkinter import messagebox, ttk

import sv_ttk

from booking_assistant import __version__, updater
from booking_assistant.browser import PROFILE_DIR, Stopped, run
from booking_assistant.inputfmt import normalize_date, normalize_time, parse_open_at
from booking_assistant.servertime import Offset, measure_best

TIME_URL = "https://tickets.interpark.com/"
SETTINGS = PROFILE_DIR.parent / "settings.json"
FONT = "맑은 고딕"
MONO = "Consolas"
WEEKDAYS = "월화수목금토일"
UPDATE_EVERY_MS = 6 * 60 * 60 * 1000

# 테마별 색. 나머지 위젯 색은 sv_ttk가 맡는다.
PALETTE = {
    "light": {
        "muted": "#6b6f76", "accent": "#005fb8", "danger": "#d93838",
        "log_bg": "#f6f7f9", "log_fg": "#30343b",
        "idle": ("#eef0f3", "#5b6068"), "busy": ("#e3eefa", "#005fb8"),
        "done": ("#e3f6ea", "#17803d"), "fail": ("#fde8e8", "#c42b2b"),
    },
    "dark": {
        "muted": "#9aa0a8", "accent": "#57c8ff", "danger": "#ff6b6b",
        "log_bg": "#232323", "log_fg": "#d8dbe0",
        "idle": ("#353840", "#b8bdc6"), "busy": ("#1d3a4d", "#7fd4ff"),
        "done": ("#1f3d2a", "#6fdc96"), "fail": ("#4a2323", "#ff8a8a"),
    },
}

STATUS_TEXT = {
    "idle": "대기", "busy": "진행 중", "done": "클릭 완료", "fail": "확인 필요",
}


def system_theme() -> str:
    try:
        import winreg
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                             r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize")
        return "light" if winreg.QueryValueEx(key, "AppsUseLightTheme")[0] else "dark"
    except Exception:
        return "light"


class App:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.offset: Offset | None = None
        self.logs: queue.Queue[str] = queue.Queue()
        self.stop = threading.Event()
        self.worker: threading.Thread | None = None
        self.status = "idle"
        self.status_detail = ""
        self.muted_labels: list[ttk.Label] = []

        s = self.load_settings()
        self.theme = s.get("theme") or system_theme()

        root.title("NOL 예매 도우미")
        scale = root.winfo_fpixels("1i") / 96
        root.geometry(f"{int(600 * scale)}x{int(780 * scale)}")
        root.minsize(int(520 * scale), int(640 * scale))

        self.url_var = tk.StringVar(value=s.get("url", ""))
        self.date_var = tk.StringVar(value=s.get("date", datetime.now().strftime("%Y-%m-%d")))
        self.time_var = tk.StringVar(value=s.get("time", "20:00:00"))
        self.keyword_var = tk.StringVar(value=s.get("keywords", ""))
        self.label_var = tk.StringVar(value=s.get("labels", "예매하기, 예매 하기"))
        self.reload_var = tk.StringVar(value=str(s.get("reload_after", 2.0)))
        self.clock_var = tk.StringVar(value="--:--:--.---")
        self.offset_var = tk.StringVar(value="서버 시간 측정 중…")
        self.countdown_var = tk.StringVar(value="--:--:--")
        self.target_var = tk.StringVar(value="")
        self.update_var = tk.StringVar(value=f"v{__version__}")
        self.pending_update: updater.Update | None = None
        self.update_declined = False

        self.build()
        self.apply_theme(self.theme)

        self.log("Chrome 창에서 직접 로그인하고, 날짜/회차를 미리 골라두세요.")
        self.log("Chrome이 뜨는 데 20초 넘게 걸릴 수 있으니 오픈 5분 전에는 시작하세요.")
        self.log("정각에 예매 버튼까지만 눌러드립니다. 대기열·좌석·결제는 직접 진행하세요.")

        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.measure()
        self.tick()
        updater.cleanup()
        self.root.after(1500, self.check_update)

    # ------------------------------------------------------------------ UI
    def build(self) -> None:
        outer = ttk.Frame(self.root, padding=(20, 16, 20, 20))
        outer.pack(fill="both", expand=True)
        outer.columnconfigure(0, weight=1)

        # 헤더
        head = ttk.Frame(outer)
        head.grid(row=0, column=0, sticky="ew", pady=(0, 14))
        head.columnconfigure(0, weight=1)
        ttk.Label(head, text="NOL 예매 도우미", font=(FONT, 17, "bold")).grid(row=0, column=0, sticky="w")
        self.sub_lbl = ttk.Label(head, text="정각에 예매 버튼까지, 그다음은 직접", font=(FONT, 9))
        self.sub_lbl.grid(row=1, column=0, sticky="w")
        self.theme_btn = ttk.Button(head, width=8, command=self.toggle_theme)
        self.theme_btn.grid(row=0, column=1, rowspan=2, sticky="e")

        # 시계 카드
        hero = ttk.Frame(outer, style="Card.TFrame", padding=(18, 14))
        hero.grid(row=1, column=0, sticky="ew")
        hero.columnconfigure(0, weight=1)
        self.small(hero, "NOL 서버 시각").grid(row=0, column=0, sticky="w")
        self.status_lbl = tk.Label(hero, font=(FONT, 9, "bold"), padx=10, pady=2)
        self.status_lbl.grid(row=0, column=1, sticky="e")
        ttk.Label(hero, textvariable=self.clock_var, font=(MONO, 34, "bold")).grid(
            row=1, column=0, columnspan=2, sticky="w", pady=(2, 0))
        row = ttk.Frame(hero)
        row.grid(row=2, column=0, columnspan=2, sticky="ew")
        row.columnconfigure(0, weight=1)
        self.offset_lbl = self.small(row, textvariable=self.offset_var)
        self.offset_lbl.grid(row=0, column=0, sticky="w")
        ttk.Button(row, text="다시 측정", command=self.measure).grid(row=0, column=1, sticky="e")

        ttk.Separator(hero).grid(row=3, column=0, columnspan=2, sticky="ew", pady=12)
        self.small(hero, "오픈까지").grid(row=4, column=0, sticky="w")
        self.target_lbl = self.small(hero, textvariable=self.target_var)
        self.target_lbl.grid(row=4, column=1, sticky="e")
        self.countdown_lbl = ttk.Label(hero, textvariable=self.countdown_var, font=(MONO, 24, "bold"))
        self.countdown_lbl.grid(row=5, column=0, columnspan=2, sticky="w")

        # 입력 카드
        form = ttk.Frame(outer, style="Card.TFrame", padding=(18, 14))
        form.grid(row=2, column=0, sticky="ew", pady=(12, 0))
        form.columnconfigure((0, 1), weight=1, uniform="f")

        self.field(form, 0, 0, "공연 페이지 주소", self.url_var, colspan=2)
        d = self.field(form, 2, 0, "오픈 날짜", self.date_var, hint="20261020, 1020도 가능", padx=(0, 6))
        t = self.field(form, 2, 1, "오픈 시각", self.time_var, hint="200000, 2000도 가능", padx=(6, 0))
        for entry, fmt, var in ((d, normalize_date, self.date_var), (t, normalize_time, self.time_var)):
            handler = lambda _e, f=fmt, v=var: self.reformat(v, f)
            entry.bind("<FocusOut>", handler)
            entry.bind("<Return>", handler)
        self.field(form, 5, 0, "경기/회차 키워드", self.keyword_var, colspan=2,
                   hint="예매하기 버튼이 여러 개일 때 카드에 적힌 글자 (예: 3차전 · 10월 21일)")

        self.adv_open = tk.BooleanVar(value=False)
        self.adv_toggle = ttk.Checkbutton(form, text="고급 설정", style="Switch.TCheckbutton",
                                          variable=self.adv_open, command=self.toggle_advanced)
        self.adv_toggle.grid(row=8, column=0, columnspan=2, sticky="w", pady=(10, 0))
        self.adv = ttk.Frame(form)
        self.adv.columnconfigure((0, 1), weight=1, uniform="a")
        self.field(self.adv, 0, 0, "예매 버튼 문구 (쉼표 구분)", self.label_var, padx=(0, 6))
        self.field(self.adv, 0, 1, "안 열리면 새로고침 (초)", self.reload_var, padx=(6, 0))

        # 버튼
        act = ttk.Frame(outer)
        act.grid(row=3, column=0, sticky="ew", pady=14)
        act.columnconfigure(0, weight=3)
        act.columnconfigure(1, weight=1)
        self.start_btn = ttk.Button(act, text="시작  ·  Chrome 열기", style="Accent.TButton", command=self.start)
        self.start_btn.grid(row=0, column=0, sticky="ew", padx=(0, 8), ipady=6)
        self.stop_btn = ttk.Button(act, text="중지", command=self.cancel, state="disabled")
        self.stop_btn.grid(row=0, column=1, sticky="ew", ipady=6)

        # 로그
        outer.rowconfigure(4, weight=1)
        logf = ttk.Frame(outer, style="Card.TFrame", padding=(14, 10))
        logf.grid(row=4, column=0, sticky="nsew")
        logf.columnconfigure(0, weight=1)
        logf.rowconfigure(1, weight=1)
        self.small(logf, "진행 기록").grid(row=0, column=0, sticky="w", pady=(0, 6))
        upd = ttk.Frame(logf)
        upd.grid(row=0, column=0, columnspan=2, sticky="e", pady=(0, 6))
        self.small(upd, textvariable=self.update_var).pack(side="left", padx=(0, 8))
        ttk.Button(upd, text="업데이트 확인", command=lambda: self.check_update(manual=True)).pack(side="left")
        self.log_box = tk.Text(logf, height=8, font=(FONT, 9), state="disabled", wrap="word",
                               relief="flat", borderwidth=0, padx=10, pady=8, highlightthickness=0)
        self.log_box.grid(row=1, column=0, sticky="nsew")
        sb = ttk.Scrollbar(logf, command=self.log_box.yview)
        sb.grid(row=1, column=1, sticky="ns")
        self.log_box.configure(yscrollcommand=sb.set)

        self.muted_labels.append(self.sub_lbl)

    def small(self, parent, text: str = "", **kw) -> ttk.Label:
        lbl = ttk.Label(parent, text=text, font=(FONT, 9), **kw)
        self.muted_labels.append(lbl)
        return lbl

    def field(self, parent, row: int, col: int, label: str, var: tk.StringVar,
              colspan: int = 1, hint: str = "", padx=(0, 0)) -> ttk.Entry:
        ttk.Label(parent, text=label, font=(FONT, 9, "bold")).grid(
            row=row, column=col, columnspan=colspan, sticky="w", padx=padx, pady=(10, 4))
        entry = ttk.Entry(parent, textvariable=var, font=(FONT, 10))
        entry.grid(row=row + 1, column=col, columnspan=colspan, sticky="ew", padx=padx)
        if hint:
            self.small(parent, hint).grid(row=row + 2, column=col, columnspan=colspan,
                                          sticky="w", padx=padx, pady=(3, 0))
        return entry

    def toggle_advanced(self) -> None:
        if self.adv_open.get():
            self.adv.grid(row=9, column=0, columnspan=2, sticky="ew", pady=(4, 0))
        else:
            self.adv.grid_remove()

    # --------------------------------------------------------------- theme
    def apply_theme(self, name: str) -> None:
        self.theme = name
        sv_ttk.set_theme(name)
        pal = PALETTE[name]
        style = ttk.Style()
        style.configure(".", font=(FONT, 10))
        style.configure("Accent.TButton", font=(FONT, 11, "bold"))
        for lbl in self.muted_labels:
            lbl.configure(foreground=pal["muted"])
        self.log_box.configure(bg=pal["log_bg"], fg=pal["log_fg"], insertbackground=pal["log_fg"],
                               selectbackground=pal["accent"])
        self.theme_btn.configure(text="☀ 라이트" if name == "dark" else "☾ 다크")
        self.paint_status()
        self.paint_countdown()
        self.dark_titlebar(name == "dark")

    def toggle_theme(self) -> None:
        self.apply_theme("light" if self.theme == "dark" else "dark")
        self.save_settings()

    def dark_titlebar(self, dark: bool) -> None:
        try:
            self.root.update_idletasks()
            hwnd = ctypes.windll.user32.GetParent(self.root.winfo_id())
            value = ctypes.c_int(1 if dark else 0)
            ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(value), ctypes.sizeof(value))
        except Exception:
            pass

    def set_status(self, key: str, detail: str = "") -> None:
        self.status, self.status_detail = key, detail
        self.paint_status()

    def paint_status(self) -> None:
        bg, fg = PALETTE[self.theme][self.status]
        text = self.status_detail or STATUS_TEXT[self.status]
        self.status_lbl.configure(text=f"●  {text}", bg=bg, fg=fg)

    def paint_countdown(self, urgent: bool = False) -> None:
        pal = PALETTE[self.theme]
        self.countdown_lbl.configure(foreground=pal["danger"] if urgent else pal["accent"])

    # ------------------------------------------------------------ settings
    def load_settings(self) -> dict:
        try:
            return json.loads(SETTINGS.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def save_settings(self) -> None:
        SETTINGS.parent.mkdir(parents=True, exist_ok=True)
        SETTINGS.write_text(json.dumps({
            "url": self.url_var.get().strip(),
            "date": self.date_var.get().strip(),
            "time": self.time_var.get().strip(),
            "keywords": self.keyword_var.get(),
            "labels": self.label_var.get(),
            "reload_after": self.reload_var.get(),
            "theme": self.theme,
        }, ensure_ascii=False, indent=2), encoding="utf-8")

    # ------------------------------------------------------- input / clock
    def reformat(self, var: tk.StringVar, fmt) -> None:
        fixed = fmt(var.get())
        if fixed:
            var.set(fixed)

    def log(self, msg: str) -> None:
        self.logs.put(f"{datetime.now():%H:%M:%S}  {msg.strip()}")

    def run_log(self, msg: str) -> None:
        """Log callback for the browser runner; also drives the status pill."""
        self.log(msg)
        if "예매창을 열었습니다" in msg:
            self.root.after(0, self.set_status, "done")
        elif "누르지 못했" in msg or "알림 때문에" in msg:
            self.root.after(0, self.set_status, "fail")
        elif "다시 누릅니다" in msg:
            self.root.after(0, self.set_status, "busy", "재시도 중")
        elif "감시" in msg or "활성" in msg or "오픈 예정" in msg:
            self.root.after(0, self.set_status, "busy", "버튼 감시 중")
        elif "브라우저가 열렸" in msg:
            self.root.after(0, self.set_status, "busy", "오픈 대기 중")

    def tick(self) -> None:
        while not self.logs.empty():
            self.log_box.configure(state="normal")
            self.log_box.insert("end", self.logs.get() + "\n")
            self.log_box.see("end")
            self.log_box.configure(state="disabled")

        target = parse_open_at(self.date_var.get(), self.time_var.get())
        if target is None:
            self.target_var.set("날짜/시각 형식 확인")
        else:
            t = datetime.fromtimestamp(target)
            self.target_var.set(f"{t.month}월 {t.day}일({WEEKDAYS[t.weekday()]}) {t:%H:%M:%S}")

        if self.offset:
            now = self.offset.server_now()
            self.clock_var.set(datetime.fromtimestamp(now).strftime("%H:%M:%S.%f")[:-3])
            if target is None:
                self.countdown_var.set("--:--:--")
            elif target > now:
                left = target - now
                whole = int(left)
                d, rem = divmod(whole, 86400)
                text = f"{rem // 3600:02d}:{rem % 3600 // 60:02d}:{rem % 60:02d}"
                if whole < 60:
                    text += f".{int((left - whole) * 10)}"
                self.countdown_var.set(f"{d}일 {text}" if d else text)
                self.paint_countdown(urgent=left < 60)
            else:
                self.countdown_var.set("오픈됨")
                self.paint_countdown(urgent=False)
        self.root.after(30, self.tick)

    # -------------------------------------------------------------- actions
    def measure(self) -> None:
        self.offset_var.set("서버 시간 측정 중…")

        def work() -> None:
            try:
                off = measure_best(TIME_URL)
                self.offset = off
                diff = "빠름" if off.seconds < 0 else "느림"
                text = f"내 PC가 {abs(off.seconds) * 1000:.0f} ms {diff}  ·  오차 ±{off.error * 1000:.0f} ms"
                self.root.after(0, self.offset_var.set, text)
            except Exception as exc:
                self.root.after(0, self.offset_var.set, "측정 실패 — 인터넷 연결을 확인하세요")
                self.log(f"서버 시간 측정 실패: {exc}")

        threading.Thread(target=work, daemon=True).start()

    def start(self) -> None:
        self.reformat(self.date_var, normalize_date)
        self.reformat(self.time_var, normalize_time)
        url = self.url_var.get().strip()
        target = parse_open_at(self.date_var.get(), self.time_var.get())
        labels = [x.strip() for x in self.label_var.get().split(",") if x.strip()]
        keywords = self.keyword_var.get().split()
        try:
            reload_after = float(self.reload_var.get())
        except ValueError:
            reload_after = -1
        if not url.startswith("http"):
            return messagebox.showwarning("확인", "공연 페이지 주소를 넣어주세요.")
        if target is None:
            return messagebox.showwarning("확인", "오픈 날짜/시각을 확인하세요.\n예: 20261020 / 200000")
        if not labels or reload_after <= 0:
            return messagebox.showwarning("확인", "고급 설정의 버튼 문구와 새로고침 초를 확인하세요.")
        if self.offset is None:
            return messagebox.showwarning("확인", "서버 시간 측정이 끝난 뒤 시작하세요.")
        if target <= self.offset.server_now():
            return messagebox.showwarning("확인", "오픈 시각이 이미 지났습니다.")

        self.save_settings()
        self.stop.clear()
        self.start_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")
        self.set_status("busy", "Chrome 여는 중")

        def work() -> None:
            try:
                self.log("Chrome을 엽니다…")
                run(url, target, self.offset, labels, keywords, reload_after=reload_after,
                    give_up_after=20.0, log=self.run_log, stop=self.stop)
                self.log("종료되었습니다.")
            except Stopped:
                self.log("중지했습니다.")
                self.root.after(0, self.set_status, "idle")
            except Exception as exc:
                self.log(f"오류: {exc}")
                self.root.after(0, self.set_status, "fail")
            finally:
                self.root.after(0, self.reset_buttons)

        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()

    # -------------------------------------------------------------- update
    def check_update(self, manual: bool = False) -> None:
        """Look for a new release in the background; checks again every 6 hours."""
        if not manual:
            self.root.after(UPDATE_EVERY_MS, self.check_update)
        if not updater.frozen():
            self.update_var.set(f"v{__version__} · 소스 실행은 자동 업데이트하지 않음")
            return
        self.update_var.set(f"v{__version__} · 업데이트 확인 중…")

        def work() -> None:
            try:
                found = updater.check()
            except Exception as exc:
                self.root.after(0, self.update_var.set, f"v{__version__} · 업데이트 확인 실패")
                self.log(f"업데이트 확인 실패: {exc}")
                return
            if found is None:
                self.root.after(0, self.update_var.set, f"v{__version__} · 최신 버전")
                return
            self.pending_update = found
            self.update_declined = False
            self.root.after(0, self.update_var.set, f"v{__version__} · 새 버전 {found.version} 준비됨")
            self.root.after(0, self.offer_update)

        threading.Thread(target=work, daemon=True).start()

    def offer_update(self) -> None:
        """Ask to install the downloaded update, but never while a run is in progress."""
        upd = self.pending_update
        if upd is None or self.update_declined or (self.worker and self.worker.is_alive()):
            return
        notes = upd.notes if len(upd.notes) <= 1200 else upd.notes[:1200] + "…"
        if not messagebox.askyesno(
                "업데이트",
                f"새 버전 {upd.version}을 내려받았습니다. 지금 설치하고 다시 시작할까요?"
                + (f"\n\n업데이트 내용\n{notes}" if notes else "")):
            self.update_declined = True
            return
        try:
            updater.apply(upd)
        except Exception as exc:
            messagebox.showerror("업데이트", f"설치하지 못했습니다: {exc}")
            return
        self.on_close()

    def cancel(self) -> None:
        self.stop.set()

    def reset_buttons(self) -> None:
        self.start_btn.configure(state="normal")
        self.stop_btn.configure(state="disabled")
        if self.status == "busy":
            self.set_status("idle")
        # 실행 중에 받아 둔 업데이트가 있으면 끝난 뒤에 묻는다.
        self.root.after(500, self.offer_update)

    def on_close(self) -> None:
        self.stop.set()
        try:
            self.save_settings()
        except Exception:
            pass
        if self.worker and self.worker.is_alive():
            self.worker.join(timeout=3)
        self.root.destroy()


def main() -> None:
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)  # 고해상도 화면에서 글자가 흐려지지 않게
    except Exception:
        pass
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
