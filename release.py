"""새 버전 내기: PC exe와 안드로이드 APK를 만들어 GitHub 릴리스에 올린다.

    python release.py "바뀐 점"          만들고 올리기
    python release.py --build-only       만들기만 (dist/)

먼저 booking_assistant/__init__.py의 __version__을 올리고, 바뀐 코드를 커밋해 push해 둔다.
설치된 프로그램과 앱은 최신 릴리스의 .exe / .apk를 보고 업데이트한다.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

from booking_assistant import __version__

ROOT = Path(__file__).resolve().parent
DIST = ROOT / "dist"
NAME = "NOL예매도우미"
# GitHub은 파일 이름의 한글을 지워 버리므로 릴리스에는 영문 이름으로 올린다.
ASSET = f"NOL-booking-assistant-{__version__}"


def sh(*cmd: str, cwd: Path = ROOT) -> None:
    print("$", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=cwd, check=True)


def build_exe() -> Path:
    sh(sys.executable, "-m", "PyInstaller", "--noconfirm", "--onefile", "--windowed",
       "--collect-data", "sv_ttk", "--name", NAME,
       "--distpath", str(DIST), "--workpath", "build", "--specpath", "build", "gui.py")
    return DIST / f"{NAME}.exe"


def build_apk() -> Path:
    android = ROOT / "android"
    sh(str(android / "gradlew.bat"), "assembleRelease", "-q", cwd=android)
    out = DIST / f"{NAME}.apk"
    shutil.copy2(android / "app/build/outputs/apk/release/app-release.apk", out)
    return out


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("notes", nargs="?", default="", help="릴리스 노트 (업데이트할지 묻는 창에 나온다)")
    p.add_argument("--build-only", action="store_true", help="dist/에 만들기만 하고 올리지 않는다")
    args = p.parse_args()

    if not args.build_only:
        if not args.notes:
            raise SystemExit("릴리스 노트를 적어 주세요: python release.py \"바뀐 점\"")
        dirty = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True).stdout
        if dirty.strip():
            raise SystemExit("커밋하지 않은 변경이 있습니다. 커밋하고 push한 뒤 다시 실행하세요.")

    exe, apk = build_exe(), build_apk()
    print(f"\n만들었습니다: {exe}\n            {apk}")
    if args.build_only:
        return

    stage = ROOT / "build" / "release"
    shutil.rmtree(stage, ignore_errors=True)
    stage.mkdir(parents=True)
    assets = [shutil.copy2(exe, stage / f"{ASSET}.exe"), shutil.copy2(apk, stage / f"{ASSET}.apk")]
    sh("gh", "release", "create", f"v{__version__}", *map(str, assets),
       "--title", f"NOL 예매도우미 {__version__}", "--notes", args.notes)


if __name__ == "__main__":
    main()
