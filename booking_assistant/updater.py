"""Self-update for the PC exe from GitHub releases (same scheme as the Android app).

The newest release's `.exe` asset is downloaded next to the settings
(~/.booking_assistant/updates). Applying it renames the running exe to
`<name>.old` (Windows allows renaming a running exe, not overwriting it),
copies the new one into place and starts it. The `.old` file is removed the
next time the program starts. Running from source (python gui.py) never updates.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from . import __version__

REPO = "Sunya0804/booking-assistant"
LATEST = f"https://api.github.com/repos/{REPO}/releases/latest"
RELEASES = f"https://api.github.com/repos/{REPO}/releases?per_page=50"
UPDATES_DIR = Path.home() / ".booking_assistant" / "updates"
USER_AGENT = "NOL-Booking-Assistant-PC"


@dataclass
class Update:
    version: str
    path: Path
    notes: str


def frozen() -> bool:
    """True when running as the PyInstaller exe."""
    return bool(getattr(sys, "frozen", False))


def newer(a: str, b: str) -> bool:
    """Is version a higher than b? 1.2.10 > 1.2.9"""
    def parts(v: str) -> list[int]:
        return [int(m.group()) if (m := re.match(r"\d+", p)) else 0 for p in v.split(".")]
    x, y = parts(a), parts(b)
    n = max(len(x), len(y))
    return x + [0] * (n - len(x)) > y + [0] * (n - len(y))


def _fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.read()


def _notes_between(old: str, new: str) -> str:
    """Release notes of versions above `old` up to `new`, newest first, without markdown marks."""
    out = []
    for r in json.loads(_fetch(RELEASES)):
        v = r.get("tag_name", "").removeprefix("v")
        if r.get("draft") or r.get("prerelease") or not newer(v, old) or newer(v, new):
            continue
        body = (r.get("body") or "").replace("**", "").replace("`", "")
        body = re.sub(r"(?m)^#+\s*", "", body)
        body = re.sub(r"(?m)^- ", "• ", body).strip()
        out.append(f"[{v}]\n{body}")
    return "\n\n".join(out)


def _download(url: str, dest: Path, size: int, sha256: str | None) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    digest = hashlib.sha256()
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp, open(part, "wb") as out:
        while chunk := resp.read(65536):
            out.write(chunk)
            digest.update(chunk)
    if part.stat().st_size != size or (sha256 and digest.hexdigest() != sha256):
        part.unlink(missing_ok=True)
        raise RuntimeError("내려받은 파일이 손상되었습니다")
    part.replace(dest)


def check() -> Update | None:
    """Look for a newer release and download its exe. Blocking; returns None when up to date."""
    release = json.loads(_fetch(LATEST))
    version = release["tag_name"].removeprefix("v")
    if not newer(version, __version__):
        return None
    asset = next((a for a in release.get("assets", []) if a["name"].lower().endswith(".exe")), None)
    if asset is None:
        raise RuntimeError(f"새 버전 {version}이 나왔지만 PC용 파일이 아직 없습니다")
    dest = UPDATES_DIR / f"update-{version}.exe"
    if not dest.exists() or dest.stat().st_size != asset["size"]:
        digest = asset.get("digest") or ""
        _download(asset["browser_download_url"], dest, asset["size"],
                  digest.removeprefix("sha256:") if digest.startswith("sha256:") else None)
    try:
        notes = _notes_between(__version__, version)
    except Exception:  # notes are nice to have; the update still works without them
        notes = ""
    return Update(version, dest, notes)


def apply(update: Update) -> None:
    """Swap the running exe for the downloaded one and start it. The caller should exit right after."""
    exe = Path(sys.executable)
    old = exe.with_name(exe.name + ".old")
    old.unlink(missing_ok=True)
    exe.rename(old)
    try:
        shutil.copy2(update.path, exe)
    except Exception:
        old.rename(exe)
        raise
    # Make the new onefile exe unpack itself fresh instead of reusing our temp folder.
    env = dict(os.environ, PYINSTALLER_RESET_ENVIRONMENT="1")
    flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    try:
        # The onefile launcher keeps us in a job that is killed when it exits; start outside it.
        subprocess.Popen([str(exe)], env=env, close_fds=True, creationflags=flags | subprocess.CREATE_BREAKAWAY_FROM_JOB)
    except OSError:  # the job does not allow breaking away
        subprocess.Popen([str(exe)], env=env, close_fds=True, creationflags=flags)


def cleanup() -> None:
    """Remove the previous exe left by apply() and downloads that are no longer newer."""
    stale = [f for f in UPDATES_DIR.glob("update-*")
             if not newer(f.name.removeprefix("update-").split(".exe")[0], __version__)]
    if frozen():
        stale.append(Path(sys.executable + ".old"))
    for f in stale:
        try:
            f.unlink(missing_ok=True)
        except OSError:  # the old process may still be shutting down; try again next start
            pass
