"""Estimate a web server's clock offset from its HTTP `Date` header.

The `Date` header only has 1-second resolution, so we poll rapidly and watch
for the moment the second ticks over. The rollover lies between two requests,
which pins the server's clock to within roughly half a round trip.
"""
from __future__ import annotations

import threading
import time
import urllib.request
from dataclasses import dataclass
from email.utils import parsedate_to_datetime
from urllib.parse import urlsplit

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) booking_assistant/0.1"

# Servers whose clock we can read. All of them agree to within measurement noise;
# what differs is precision: the booking servers answer in ~30 ms, while
# tickets.interpark.com is a CDN redirect at ~160 ms, so its estimate is ~4x looser.
TIME_URLS = [
    "https://poticket.interpark.com/",
    "https://ticket.interpark.com/",
    "https://tickets.interpark.com/",
]

# Never press more than this much ahead of the opening, however slow the network looks.
MAX_LEAD = 0.15


@dataclass
class Offset:
    seconds: float      # server_time - local_time
    error: float        # +/- uncertainty in seconds
    rtt: float          # median round-trip time in seconds
    url: str = ""       # server the offset was measured against

    def server_now(self) -> float:
        return time.time() + self.seconds

    @property
    def host(self) -> str:
        return urlsplit(self.url).hostname or self.url

    def speed(self) -> str:
        """How good the connection to the time server is, for comparing Wi-Fi and wired/mobile."""
        ms = self.rtt * 1000
        return "빠름" if ms <= 40 else "보통" if ms <= 100 else "느림"

    def lead(self) -> float:
        """How early to press so the request reaches the server at the opening.

        One-way trip is about half the round trip; we take the measurement error off
        that, because arriving early costs a "not open yet" round and arriving late
        only costs the difference.
        """
        return min(max(self.rtt / 2 - self.error, 0.0), MAX_LEAD)


def _probe(url: str, timeout: float) -> tuple[float, float, int]:
    """Return (local_send, local_recv, server_epoch_second)."""
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": USER_AGENT})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        date = resp.headers.get("Date")
    t1 = time.time()
    if not date:
        raise RuntimeError(f"{url} returned no Date header")
    return t0, t1, int(parsedate_to_datetime(date).timestamp())


def measure(url: str, max_seconds: float = 4.0, interval: float = 0.03, timeout: float = 3.0) -> Offset:
    """Poll `url` until the server's second rolls over, then derive the offset."""
    deadline = time.time() + max_seconds
    prev = _probe(url, timeout)
    rtts = [prev[1] - prev[0]]
    while time.time() < deadline:
        time.sleep(interval)
        cur = _probe(url, timeout)
        rtts.append(cur[1] - cur[0])
        if cur[2] != prev[2]:
            # Server read its clock somewhere in each request window; the tick to
            # cur[2] happened after prev was stamped and before cur was stamped.
            lo = prev[0]          # earliest local time the tick could have happened
            hi = cur[1]           # latest local time the tick could have happened
            # Narrow using midpoints (server typically stamps near the middle).
            lo_mid = (prev[0] + prev[1]) / 2
            hi_mid = (cur[0] + cur[1]) / 2
            tick_local = (lo_mid + hi_mid) / 2
            rtts.sort()
            return Offset(
                seconds=cur[2] - tick_local,
                error=max((hi_mid - lo_mid) / 2, 0.001) if hi - lo > 0 else 0.5,
                rtt=rtts[len(rtts) // 2],
                url=url,
            )
        prev = cur
    raise RuntimeError("server second did not roll over in time; try again")


def measure_best(url: str, rounds: int = 3) -> Offset:
    """Run several measurements and keep the tightest one."""
    results = []
    for _ in range(rounds):
        try:
            results.append(measure(url))
        except Exception as exc:  # network blips shouldn't kill the whole sync
            print(f"  [time] probe failed: {exc}")
    if not results:
        raise RuntimeError("could not measure server time")
    return min(results, key=lambda o: o.error)


def time_urls(page_url: str = "") -> list[str]:
    """The site's booking servers plus the event page's own server."""
    from .sites import site_for
    urls = list(site_for(page_url).time_urls)
    parts = urlsplit(page_url.strip())
    if parts.scheme in ("http", "https") and parts.hostname:
        origin = f"{parts.scheme}://{parts.netloc}/"
        if origin not in urls:
            urls.insert(0, origin)
    return urls


def measure_servers(urls: list[str], rounds: int = 2) -> Offset:
    """Measure every server at once and keep the most precise estimate."""
    results: list[Offset] = []

    def work(url: str) -> None:
        try:
            results.append(measure_best(url, rounds))
        except Exception:  # an unreachable server just drops out
            pass

    threads = [threading.Thread(target=work, args=(u,), daemon=True) for u in urls]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    if not results:
        raise RuntimeError("could not measure server time")
    return min(results, key=lambda o: o.error)
