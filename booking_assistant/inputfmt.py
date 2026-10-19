"""Turn loosely typed dates/times into the canonical form.

    200000 -> 20:00:00     2000 -> 20:00:00     20 -> 20:00:00
    20:0   -> 20:00:00     20시 -> 20:00:00     20시30분 -> 20:30:00
    20261020 -> 2026-10-20   1020 -> <this year>-10-20   2026.10.20 -> 2026-10-20
Anything that cannot be understood comes back as None.
"""
from __future__ import annotations

import re
from datetime import date, datetime


def normalize_time(text: str) -> str | None:
    s = text.strip()
    if not s:
        return None
    parts = [p for p in re.split(r"[^0-9]+", s) if p]
    if len(parts) == 1:
        digits = parts[0]
        if len(digits) <= 2:
            parts = [digits]
        elif len(digits) in (3, 4):
            parts = [digits[:-2], digits[-2:]]
        elif len(digits) in (5, 6):
            parts = [digits[:-4], digits[-4:-2], digits[-2:]]
        else:
            return None
    if not 1 <= len(parts) <= 3:
        return None
    h, m, sec = (list(map(int, parts)) + [0, 0])[:3]
    if not (0 <= h < 24 and 0 <= m < 60 and 0 <= sec < 60):
        return None
    return f"{h:02d}:{m:02d}:{sec:02d}"


def normalize_date(text: str, today: date | None = None) -> str | None:
    today = today or date.today()
    s = text.strip()
    if not s:
        return None
    parts = [p for p in re.split(r"[^0-9]+", s) if p]
    if len(parts) == 1:
        digits = parts[0]
        if len(digits) == 8:
            parts = [digits[:4], digits[4:6], digits[6:]]
        elif len(digits) == 6:
            parts = ["20" + digits[:2], digits[2:4], digits[4:]]
        elif len(digits) in (3, 4):
            parts = [digits[:-2], digits[-2:]]
        else:
            return None
    if len(parts) == 2:
        parts = [str(today.year)] + parts
    if len(parts) != 3:
        return None
    y, m, d = map(int, parts)
    if y < 100:
        y += 2000
    try:
        return date(y, m, d).isoformat()
    except ValueError:
        return None


def parse_open_at(date_text: str, time_text: str) -> float | None:
    d, t = normalize_date(date_text), normalize_time(time_text)
    if not d or not t:
        return None
    return datetime.strptime(f"{d} {t}", "%Y-%m-%d %H:%M:%S").timestamp()
