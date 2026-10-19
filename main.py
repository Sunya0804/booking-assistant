"""booking_assistant: NOL 티켓 오픈 시간에 맞춰 예매창을 정확히 열어주는 도우미.

  python main.py time
  python main.py run --url <공연 상세 URL> --open "2026-10-20 20:00:00"
"""
from __future__ import annotations

import argparse
import time
from datetime import datetime

from booking_assistant.servertime import measure_best

DEFAULT_TIME_URL = "https://tickets.interpark.com/"


def cmd_time(args: argparse.Namespace) -> None:
    print(f"서버 시간 측정 중: {args.time_url}")
    off = measure_best(args.time_url)
    print(f"  오프셋 {off.seconds * 1000:+.0f} ms (서버 - 내 PC), 오차 ±{off.error * 1000:.0f} ms, RTT {off.rtt * 1000:.0f} ms")
    try:
        while True:
            now = datetime.fromtimestamp(off.server_now())
            print(f"\r  서버 시각 {now:%H:%M:%S.%f}"[:-3], end="", flush=True)
            time.sleep(0.05)
    except KeyboardInterrupt:
        print()


def cmd_run(args: argparse.Namespace) -> None:
    from booking_assistant.browser import run

    open_at = datetime.strptime(args.open, "%Y-%m-%d %H:%M:%S").timestamp()
    print(f"서버 시간 측정 중: {args.time_url}")
    off = measure_best(args.time_url)
    print(f"  오프셋 {off.seconds * 1000:+.0f} ms, 오차 ±{off.error * 1000:.0f} ms")
    if open_at <= off.server_now():
        raise SystemExit("오픈 시각이 이미 지났습니다.")
    run(args.url, open_at, off, args.label or ["예매하기", "예매 하기"], args.match or [],
        reload_after=args.reload_after, give_up_after=args.give_up_after)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--time-url", default=DEFAULT_TIME_URL, help="서버 시간을 잴 주소")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("time", help="서버 시간 오프셋을 재고 서버 시계를 표시")

    r = sub.add_parser("run", help="오픈 시각에 예매 버튼을 눌러 예매창을 연다")
    r.add_argument("--url", required=True, help="공연 상세 페이지 URL")
    r.add_argument("--open", required=True, help='오픈 시각 "YYYY-MM-DD HH:MM:SS" (한국 시간)')
    r.add_argument("--match", action="append", help='예매 버튼이 여러 개일 때 경기/회차 키워드 (예: --match 3차전)')
    r.add_argument("--label", action="append", help="예매 버튼 문구 (여러 번 지정 가능)")
    r.add_argument("--reload-after", type=float, default=2.0, help="오픈 후 버튼이 몇 초간 안 열리면 새로고침할지 (기본 2.0)")
    r.add_argument("--give-up-after", type=float, default=20.0, help="오픈 후 몇 초까지 시도할지 (기본 20)")

    args = p.parse_args()
    {"time": cmd_time, "run": cmd_run}[args.cmd](args)


if __name__ == "__main__":
    main()
