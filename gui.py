"""booking_assistant 창 프로그램. 더블클릭(또는 exe)으로 실행한다."""
from __future__ import annotations

import ctypes
import json
import os
import queue
import sys
from pathlib import Path
import threading
import tkinter as tk
from datetime import datetime
from tkinter import messagebox, ttk
from tkinter import font as tkfont

import sv_ttk

from booking_assistant import __version__, updater
from booking_assistant.browser import LOG_DIR, PROFILE_DIR, Stopped, run
from booking_assistant.inputfmt import normalize_date, normalize_time, parse_open_at
from booking_assistant.servertime import Offset, measure_servers, time_urls

SETTINGS = PROFILE_DIR.parent / "settings.json"
FONT = "맑은 고딕"
MONO = "Segoe UI"  # Tk on Windows renders regular Segoe UI with equal-width digits.
WEEKDAYS = "월화수목금토일"
UPDATE_EVERY_MS = 6 * 60 * 60 * 1000

# 테마별 색. 나머지 위젯 색은 sv_ttk가 맡는다.
PALETTE = {
    "light": {
        "bg": "#f4f6f6", "surface": "#ffffff", "muted": "#586b68",
        "accent": "#0f766e", "danger": "#b42335", "log_bg": "#ffffff", "log_fg": "#182625",
        "idle": ("#e4eeeb", "#354f49"), "busy": ("#ddf2ee", "#0f766e"),
        "done": ("#d9f4e4", "#17633c"), "fail": ("#fce5e8", "#b42335"),
    },
    "dark": {
        "bg": "#121918", "surface": "#1c2725", "muted": "#a3b7b2",
        "accent": "#79d5c5", "danger": "#ffb1bb", "log_bg": "#1c2725", "log_fg": "#e5efec",
        "idle": ("#2d403b", "#cfdfd9"), "busy": ("#173f39", "#79d5c5"),
        "done": ("#1d4230", "#8de0ad"), "fail": ("#4b2930", "#ffb1bb"),
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
        self.early_var = tk.BooleanVar(value=bool(s.get("early_press", False)))
        self.countdown_look: tuple | None = None
        self.clock_var = tk.StringVar(value="--:--:--.---")
        self.offset_var = tk.StringVar(value="서버 시간 측정 중…")
        self.countdown_var = tk.StringVar(value="--:--:--")
        self.target_var = tk.StringVar(value="")
        self.update_var = tk.StringVar(value=f"v{__version__}")
        self.pending_update: updater.Update | None = None
        self.update_declined = False

        icon_dir = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent)) / "assets"
        self.app_icon = tk.PhotoImage(file=str(icon_dir / "app.png"))
        root.iconphoto(True, self.app_icon)
        if sys.platform == "win32":
            root.iconbitmap(str(icon_dir / "app.ico"))
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
        # A scrollable body keeps every field reachable at minimum size and 150% DPI.
        shell = ttk.Frame(self.root, padding=(16, 8))
        shell.pack(fill="both", expand=True)
        shell.columnconfigure(0, weight=1)
        shell.rowconfigure(0, weight=1)
        self.canvas = tk.Canvas(shell, highlightthickness=0, borderwidth=0)
        self.canvas.grid(row=0, column=0, sticky="nsew")
        scroll = ttk.Scrollbar(shell, command=self.canvas.yview)
        scroll.grid(row=0, column=1, sticky="ns")
        self.canvas.configure(yscrollcommand=scroll.set)
        outer = ttk.Frame(self.canvas, padding=(0, 8, 8, 8))
        content = self.canvas.create_window(0, 0, window=outer, anchor="nw")
        outer.bind("<Configure>", lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", lambda e: self.canvas.itemconfigure(content, width=e.width))
        def wheel(e):
            if not isinstance(e.widget, tk.Text):
                self.canvas.yview_scroll(-int(e.delta / 120), "units")
        self.root.bind_all("<MouseWheel>", wheel, add="+")
        outer.columnconfigure(0, weight=1)

        head = ttk.Frame(outer)
        head.grid(row=0, column=0, sticky="ew", pady=(0, 16))
        head.columnconfigure(0, weight=1)
        ttk.Label(head, text="예매 도우미", font=(FONT, 18, "bold")).grid(row=0, column=0, sticky="w")
        self.sub_lbl = self.small(head, "NOL 티켓 · 정각에 버튼까지, 그다음은 직접")
        self.sub_lbl.grid(row=1, column=0, sticky="w", pady=(4, 0))
        self.theme_btn = ttk.Button(head, width=3, command=self.toggle_theme, style="Text.TButton")
        self.theme_btn.grid(row=0, column=1, rowspan=2)

        hero = ttk.Frame(outer, style="Card.TFrame", padding=16)
        hero.grid(row=1, column=0, sticky="ew")
        hero.columnconfigure(0, weight=1)
        self.small(hero, "오픈까지 남은 시간").grid(row=0, column=0, sticky="w")
        self.status_lbl = tk.Canvas(hero, height=28, highlightthickness=0, borderwidth=0)
        self.status_lbl.grid(row=0, column=1, sticky="e")
        self.countdown_lbl = ttk.Label(hero, textvariable=self.countdown_var, font=(MONO, 32))
        self.countdown_lbl.grid(row=1, column=0, columnspan=2, sticky="w", pady=(4, 0))
        self.target_lbl = self.small(hero, textvariable=self.target_var)
        self.target_lbl.grid(row=2, column=0, columnspan=2, sticky="w", pady=(0, 8))
        ttk.Separator(hero).grid(row=3, column=0, columnspan=2, sticky="ew", pady=(0, 8))
        row = ttk.Frame(hero)
        row.grid(row=4, column=0, columnspan=2, sticky="ew")
        row.columnconfigure(1, weight=1)
        self.small(row, "NOL 서버 시각").grid(row=0, column=0, sticky="w", padx=(0, 8))
        ttk.Label(row, textvariable=self.clock_var, font=(MONO, 13)).grid(row=0, column=1, sticky="w")
        ttk.Button(row, text="다시 측정", command=self.measure, style="Text.TButton").grid(row=0, column=2)
        self.offset_lbl = self.small(hero, textvariable=self.offset_var)
        self.offset_lbl.grid(row=5, column=0, columnspan=2, sticky="w", pady=(4, 0))

        form = ttk.Frame(outer, style="Card.TFrame", padding=(16, 8, 16, 16))
        form.grid(row=2, column=0, sticky="ew", pady=(8, 0))
        form.columnconfigure((0, 1), weight=1, uniform="f")
        self.field(form, 0, 0, "공연 페이지 주소", self.url_var, colspan=2)
        d = self.field(form, 2, 0, "오픈 날짜", self.date_var, hint="20261020 · 1020도 가능", padx=(0, 4))
        t = self.field(form, 2, 1, "오픈 시각", self.time_var, hint="200000 · 2000도 가능", padx=(4, 0))
        for entry, fmt, var in ((d, normalize_date, self.date_var), (t, normalize_time, self.time_var)):
            handler = lambda _e, f=fmt, v=var: self.reformat(v, f)
            entry.bind("<FocusOut>", handler)
            entry.bind("<Return>", handler)
        self.field(form, 5, 0, "경기 / 회차 키워드", self.keyword_var, colspan=2,
                   hint="버튼이 여러 개일 때 카드의 글자 · 예: 3차전, 10월 21일")
        self.adv_open = tk.BooleanVar(value=False)
        self.adv_toggle = ttk.Button(form, text="▸  고급 설정", command=self.toggle_advanced, style="Text.TButton")
        ttk.Checkbutton(form, text="미리 누르기", variable=self.early_var, style="Card.TCheckbutton").grid(
            row=8, column=0, columnspan=2, sticky="w", pady=(12, 0))
        self.small(form, "요청이 서버에 닿는 시간만큼 일찍 눌러 정각에 도착하게 합니다 (최대 150 ms)").grid(
            row=9, column=0, columnspan=2, sticky="w", pady=(4, 0))
        self.adv_toggle.grid(row=10, column=0, columnspan=2, sticky="ew", pady=(8, 0), ipady=4)
        self.adv = ttk.Frame(form)
        self.adv.columnconfigure((0, 1), weight=1, uniform="a")
        self.field(self.adv, 0, 0, "예매 버튼 문구 (쉼표 구분)", self.label_var, padx=(0, 4))
        self.field(self.adv, 0, 1, "새로고침 간격 (초)", self.reload_var, padx=(4, 0))

        act = ttk.Frame(outer)
        act.grid(row=3, column=0, sticky="ew", pady=8)
        act.columnconfigure(0, weight=3)
        act.columnconfigure(1, weight=1)
        self.start_btn = tk.Button(act, text="시작  ·  Chrome 열기", command=self.start,
                                   font=(FONT, 11, "bold"), relief="flat", borderwidth=0, cursor="hand2", pady=12)
        self.start_btn.grid(row=0, column=0, sticky="ew", padx=(0, 8))
        self.stop_btn = ttk.Button(act, text="중지", command=self.cancel, state="disabled")
        self.stop_btn.grid(row=0, column=1, sticky="nsew")

        logf = ttk.Frame(outer, style="Card.TFrame", padding=16)
        logf.grid(row=4, column=0, sticky="nsew")
        logf.columnconfigure(0, weight=1)
        self.small(logf, "진행 기록").grid(row=0, column=0, sticky="w", pady=(0, 8))
        self.log_box = tk.Text(logf, height=5, font=(FONT, 9), state="disabled", wrap="word",
                               relief="flat", borderwidth=0, padx=0, pady=0, highlightthickness=0)
        self.log_box.grid(row=1, column=0, sticky="nsew")
        sb = ttk.Scrollbar(logf, command=self.log_box.yview)
        sb.grid(row=1, column=1, sticky="ns")
        self.log_box.configure(yscrollcommand=sb.set)
        footer = ttk.Frame(shell)
        footer.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(8, 0))
        footer.columnconfigure(0, weight=1)
        self.small(footer, textvariable=self.update_var).grid(row=0, column=0, sticky="w")
        ttk.Button(footer, text="기록 폴더", style="Text.TButton",
                   command=self.open_logs).grid(row=0, column=1)
        ttk.Button(footer, text="업데이트 확인", style="Text.TButton",
                   command=lambda: self.check_update(manual=True)).grid(row=0, column=2)

    def small(self, parent, text: str = "", **kw) -> ttk.Label:
        lbl = ttk.Label(parent, text=text, font=(FONT, 9), **kw)
        self.muted_labels.append(lbl)
        return lbl

    def field(self, parent, row: int, col: int, label: str, var: tk.StringVar,
              colspan: int = 1, hint: str = "", padx=(0, 0)) -> ttk.Entry:
        ttk.Label(parent, text=label, font=(FONT, 9, "bold")).grid(
            row=row, column=col, columnspan=colspan, sticky="w", padx=padx, pady=(8, 4))
        entry = ttk.Entry(parent, textvariable=var, font=(FONT, 10))
        entry.grid(row=row + 1, column=col, columnspan=colspan, sticky="ew", padx=padx)
        if hint:
            self.small(parent, hint).grid(row=row + 2, column=col, columnspan=colspan,
                                          sticky="w", padx=padx, pady=(4, 0))
        return entry

    def toggle_advanced(self) -> None:
        self.adv_open.set(not self.adv_open.get())
        self.adv_toggle.configure(text="▾  고급 설정" if self.adv_open.get() else "▸  고급 설정")
        if self.adv_open.get():
            self.adv.grid(row=11, column=0, columnspan=2, sticky="ew", pady=(4, 0))
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
        style.configure("TFrame", background=pal["bg"])
        style.configure("Card.TFrame", background=pal["surface"])
        style.configure("TLabel", background=pal["bg"], foreground=pal["log_fg"])
        style.configure("Text.TButton", padding=(8, 4), font=(FONT, 9))
        style.configure("Card.TCheckbutton", background=pal["surface"])
        self.canvas.configure(bg=pal["bg"])
        self.start_btn.configure(bg=pal["accent"], fg=pal["surface"] if name == "light" else pal["bg"],
                                 activebackground=pal["busy"][0], activeforeground=pal["accent"])
        style.configure("Surface.TFrame", background=pal["surface"])
        def surfaces(widget):
            for child in widget.winfo_children():
                if isinstance(child, (ttk.Label, ttk.Frame)):
                    parent = child.master
                    while parent is not None and not (isinstance(parent, ttk.Frame) and parent.cget("style") == "Card.TFrame"):
                        parent = getattr(parent, "master", None)
                    if isinstance(child, ttk.Frame):
                        if parent is not None and child.cget("style") != "Card.TFrame":
                            child.configure(style="Surface.TFrame")
                    else:
                        child.configure(background=pal["surface"] if parent is not None else pal["bg"])
                surfaces(child)
        surfaces(self.root)
        self.log_box.tag_configure("time", foreground=pal["muted"])
        self.log_box.tag_configure("body", foreground=pal["log_fg"])
        for lbl in self.muted_labels:
            lbl.configure(foreground=pal["muted"])
        self.log_box.configure(bg=pal["log_bg"], fg=pal["log_fg"], insertbackground=pal["log_fg"],
                               selectbackground=pal["accent"])
        self.theme_btn.configure(text="☀" if name == "dark" else "☾")
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
        text = f"{dict(idle='◷', busy='●', done='✓', fail='!')[self.status]}  {text}"
        font = tkfont.Font(family=FONT, size=10, weight="bold")
        pad = self.root.winfo_fpixels("1i") / 96
        width, height, radius = font.measure(text) + int(24 * pad), int(28 * pad), int(12 * pad)
        self.status_lbl.configure(width=width, height=height, bg=PALETTE[self.theme]["surface"])
        self.status_lbl.delete("all")
        self.status_lbl.create_polygon(radius, 0, width-radius, 0, width, 0, width, radius,
                                       width, height-radius, width, height, width-radius, height,
                                       radius, height, 0, height, 0, height-radius, 0, radius, 0, 0,
                                       smooth=True, fill=bg, outline="")
        self.status_lbl.create_text(width/2, height/2, text=text, fill=fg, font=font)

    def paint_countdown(self, urgent: bool = False) -> None:
        pal = PALETTE[self.theme]
        active = self.countdown_var.get() not in ("오픈 시각이 지났습니다", "날짜와 시각을 확인하세요", "서버 시각 측정 중…")
        color = pal["muted"] if not active else pal["danger"] if urgent else pal["accent"]
        look = (color, MONO if active else FONT, 32 if active else 17)
        if look != self.countdown_look:  # tick() runs every 30 ms; only touch the widget on a change
            self.countdown_look = look
            self.countdown_lbl.configure(foreground=color, font=look[1:])

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
            "early_press": self.early_var.get(),
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
            line = self.logs.get()
            self.log_box.insert("end", line[:10], "time")
            self.log_box.insert("end", line[10:] + "\n", "body")
            self.log_box.see("end")
            self.log_box.configure(state="disabled")

        target = parse_open_at(self.date_var.get(), self.time_var.get())
        if target is None:
            self.target_var.set("날짜/시각 형식 확인")
        else:
            t = datetime.fromtimestamp(target)
            self.target_var.set(f"{t.month}월 {t.day}일({WEEKDAYS[t.weekday()]}) {t:%H:%M:%S}")

        urgent = False
        if self.offset:
            now = self.offset.server_now()
            self.clock_var.set(datetime.fromtimestamp(now).strftime("%H:%M:%S.%f")[:-3])
            if target is None:
                self.countdown_var.set("날짜와 시각을 확인하세요")
            elif target > now:
                left = target - now
                whole = int(left)
                d, rem = divmod(whole, 86400)
                text = f"{rem // 3600:02d}:{rem % 3600 // 60:02d}:{rem % 60:02d}"
                if whole < 60:
                    text += f".{int((left - whole) * 10)}"
                self.countdown_var.set(f"{d}일 {text}" if d else text)
                urgent = left < 60
            else:
                self.countdown_var.set("오픈 시각이 지났습니다")
        if target is None:
            self.countdown_var.set("날짜와 시각을 확인하세요")
        elif not self.offset:
            self.countdown_var.set("서버 시각 측정 중…")
        self.paint_countdown(urgent)
        self.root.after(30, self.tick)

    # -------------------------------------------------------------- actions
    def measure(self) -> None:
        self.offset_var.set("서버 시간 측정 중…")

        def work() -> None:
            try:
                off = measure_servers(time_urls(self.url_var.get()))
                self.offset = off
                diff = "빠름" if off.seconds < 0 else "느림"
                text = (f"내 PC가 {abs(off.seconds) * 1000:.0f} ms {diff}  ·  오차 ±{off.error * 1000:.0f} ms"
                        f"  ·  {off.host}")
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
                    give_up_after=20.0, early=self.early_var.get(), log=self.run_log, stop=self.stop)
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

    def open_logs(self) -> None:
        LOG_DIR.mkdir(parents=True, exist_ok=True)
        os.startfile(LOG_DIR)

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
