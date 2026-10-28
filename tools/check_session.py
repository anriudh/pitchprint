#!/usr/bin/env python3
"""Check a Pitchprint session for gaps, sample rates and broken lines.

Takes a session folder or an exported zip and prints a plain-text report. It only checks that
the logger did its job; it computes no distance, speed or other metrics.

    python tools/check_session.py path/to/session.zip
"""

import argparse
import csv
import io
import json
import statistics
import sys
import zipfile
from array import array
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

# Gaps longer than this many seconds are listed for each stream.
GAP_LIMITS = {
    "fix.csv": 3.0,
    "status.csv": 3.0,
    "raw.csv": 3.0,
    "accel.csv": 0.1,
    "gyro.csv": 0.1,
    "mag.csv": 0.2,
}
# One row per satellite, so rows sharing a timestamp form one epoch.
EPOCH_STREAMS = {"status.csv", "raw.csv"}
# Fields that may contain commas, so these files need a real CSV parser.
QUOTED_STREAMS = {"nmea.csv"}
# Arrive in bursts each epoch, so only an average rate means anything.
BURST_STREAMS = {"nmea.csv", "nav.csv"}
STREAMS = ["fix.csv", "status.csv", "raw.csv", "nav.csv", "nmea.csv", "accel.csv", "gyro.csv", "mag.csv"]


class Source:
    """A session folder or a zip holding one."""

    def __init__(self, path):
        p = Path(path)
        if p.is_file():
            self.zip = zipfile.ZipFile(p)
            entries = [n for n in self.zip.namelist() if not n.endswith("/")]
            self.prefix = entries[0].rsplit("/", 1)[0] + "/" if "/" in entries[0] else ""
            self.names = {n[len(self.prefix):] for n in entries}
        else:
            self.zip = None
            self.dir = p
            self.names = {f.name for f in p.iterdir()}

    def open(self, name):
        if self.zip:
            return io.TextIOWrapper(self.zip.open(self.prefix + name), encoding="utf-8", newline="")
        return open(self.dir / name, encoding="utf-8", newline="")


def clock(seconds):
    s = int(round(seconds))
    return f"{s // 3600}:{s // 60 % 60:02d}:{s % 60:02d}"


def read_events(src):
    events, bad = [], 0
    with src.open("events.jsonl") as f:
        for line in f:
            if not line.endswith("\n"):
                bad += 1
                continue
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                bad += 1
    return events, bad


class Timeline:
    """Maps t_ns to seconds since session start, using each run's clock anchor.

    Elapsed time restarts at zero after a reboot, so a stream's timestamps going backwards
    marks the start of the next boot's runs.
    """

    def __init__(self, start_wall_ms, events):
        self.start_wall_ms = start_wall_ms
        self.anchors = []  # one (t_ns, wall_ms) per boot, in order
        last_boot = None
        for e in events:
            if e["type"] == "run_start" and e.get("boot_count") != last_boot:
                self.anchors.append((e["t_ns"], e["wall_ms"]))
                last_boot = e.get("boot_count")
        if not self.anchors:
            self.anchors.append((0, start_wall_ms))

    def seconds(self, t_ns, boot_index):
        t0, wall0 = self.anchors[min(boot_index, len(self.anchors) - 1)]
        return (wall0 + (t_ns - t0) / 1e6 - self.start_wall_ms) / 1000


def check_stream(src, name, timeline):
    rows = bad = partial = 0
    times = array("q")
    boots = array("b")
    boot = 0
    with src.open(name) as f:
        header = f.readline()
        width = len(next(csv.reader([header])))
        for line in f:
            if not line.endswith("\n"):
                partial += 1
                continue
            fields = next(csv.reader([line])) if name in QUOTED_STREAMS else line.rstrip("\n").split(",")
            if len(fields) != width:
                bad += 1
                continue
            try:
                t = int(fields[0])
            except ValueError:
                bad += 1
                continue
            rows += 1
            if name in EPOCH_STREAMS and times and t == times[-1]:
                continue
            if times and t < times[-1]:
                boot += 1
            times.append(t)
            boots.append(boot)

    out = {"name": name, "rows": rows, "bad": bad, "partial": partial, "samples": len(times)}
    if len(times) < 2:
        return out
    intervals = array("d")
    gaps = []
    limit = GAP_LIMITS.get(name)
    for i in range(1, len(times)):
        if boots[i] != boots[i - 1]:
            continue
        dt = (times[i] - times[i - 1]) / 1e9
        intervals.append(dt)
        if limit is not None and dt > limit:
            gaps.append((timeline.seconds(times[i - 1], boots[i - 1]), dt))
    median = statistics.median(intervals) if intervals else 0
    out.update(
        span=sum(intervals),
        median=median,
        rate=1 / median if median > 0 else 0,
        max_gap=max(intervals) if intervals else 0,
        over_3x=sum(1 for d in intervals if d > 3 * median) if median > 0 else 0,
        backwards=boot,
        gaps=gaps,
    )
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("session", help="session folder or exported zip")
    args = parser.parse_args()
    src = Source(args.session)

    meta = json.load(src.open("meta.json")) if "meta.json" in src.names else {}
    events, bad_events = read_events(src)
    start_wall = meta.get("start_wall_ms") or (events[0]["wall_ms"] if events else 0)
    timeline = Timeline(start_wall, events)
    device = meta.get("device", {})

    print(f"Session {meta.get('session', '?')}, format {meta.get('format', '?')}")
    print(f"Phone   {device.get('manufacturer', '?')} {device.get('model', '?')}, "
          f"Android {device.get('android', '?')}, app {device.get('app_version', '?')}")
    if start_wall:
        started = datetime.fromtimestamp(start_wall / 1000, tz=timezone.utc).astimezone()
        print(f"Started {started:%Y-%m-%d %H:%M:%S %Z}")
    if events:
        print(f"Length  {clock((events[-1]['wall_ms'] - start_wall) / 1000)} by wall clock, first to last event")

    runs = [e for e in events if e["type"] == "run_start"]
    print(f"Runs    {len(runs)}: " + ", ".join(f"{e.get('reason')} (boot {e.get('boot_count')})" for e in runs))
    stops = [e for e in events if e["type"] == "stop"]
    print("Stopped " + (", ".join(e.get("reason", "?") for e in stops) if stops else "no stop event"))
    print()

    counts = Counter(e["type"] for e in events)
    print("Events  " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    if bad_events:
        print(f"        {bad_events} unreadable event lines")
    markers = [e for e in events if e["type"] == "marker"]
    if markers:
        print("Markers " + ", ".join(
            f"{clock((e['wall_ms'] - start_wall) / 1000)} {e.get('key')}{'' if e.get('screen_on') else ' (screen off)'}"
            for e in markers))
    for kind in ("repaired", "crash", "gnss_error", "sensor_error", "service_destroyed"):
        for e in (e for e in events if e["type"] == kind):
            details = {k: v for k, v in e.items() if k not in ("t_ns", "wall_ms", "type")}
            print(f"{kind:8s}{clock((e['wall_ms'] - start_wall) / 1000)} {details}")

    statuses = [e for e in events if e["type"] == "status"]
    if statuses:
        first, last = statuses[0], statuses[-1]
        span = clock((last["wall_ms"] - first["wall_ms"]) / 1000)
        print(f"Battery {first.get('battery_pct')}% -> {last.get('battery_pct')}% over {span}, "
              f"plugged {first.get('plugged')} -> {last.get('plugged')}, "
              f"temperature {first.get('temp_c')} -> {last.get('temp_c')} C")
        stall = max((e.get("max_writer_stall_ms") or 0) for e in statuses)
        queue = max((e.get("queue") or 0) for e in statuses)
        errors = {e["writer_error"] for e in statuses if e.get("writer_error")}
        print(f"Writer  longest flush {stall} ms, deepest queue {queue}"
              + (f", errors: {sorted(errors)}" if errors else ", no errors"))
    offsets = {}
    for e in events:
        if e["type"] == "clock":
            for sensor, ns in (e.get("sensor_offset_ns") or {}).items():
                offsets.setdefault(sensor, []).append(ns / 1e6)
    if offsets:
        print("Clock   sensor receipt minus timestamp, ms (median / max): " + ", ".join(
            f"{s} {statistics.median(v):.1f} / {max(v):.1f}" for s, v in sorted(offsets.items())))
    print()

    print(f"{'stream':11s} {'rows':>9s} {'bad':>4s} {'part':>4s} {'span':>8s} {'rate Hz':>8s} "
          f"{'median ms':>9s} {'max gap s':>9s} {'>3x med':>7s}")
    gap_lines = []
    for name in STREAMS:
        if name not in src.names:
            print(f"{name:11s} missing")
            continue
        s = check_stream(src, name, timeline)
        if s["samples"] < 2:
            print(f"{name:11s} {s['rows']:9d} {s['bad']:4d} {s['partial']:4d}  too few samples")
            continue
        if name in BURST_STREAMS:
            print(f"{name:11s} {s['rows']:9d} {s['bad']:4d} {s['partial']:4d} {clock(s['span']):>8s} "
                  f"{s['rows'] / s['span']:8.2f} {'-':>9s} {s['max_gap']:9.3f} {'-':>7s}")
            continue
        print(f"{name:11s} {s['rows']:9d} {s['bad']:4d} {s['partial']:4d} {clock(s['span']):>8s} "
              f"{s['rate']:8.2f} {s['median'] * 1000:9.2f} {s['max_gap']:9.3f} {s['over_3x']:7d}")
        if s["backwards"]:
            gap_lines.append(f"{name}: timestamps went backwards {s['backwards']} time(s), treated as reboots")
        if s["gaps"]:
            listed = ", ".join(f"{dt:.2f} s at {clock(at)}" for at, dt in s["gaps"][:5])
            more = f" and {len(s['gaps']) - 5} more" if len(s["gaps"]) > 5 else ""
            gap_lines.append(f"{name}: {len(s['gaps'])} gap(s) over {GAP_LIMITS[name]} s: {listed}{more}")
    if gap_lines:
        print()
        print("\n".join(gap_lines))
    print()
    print("Rates for status.csv and raw.csv are epochs per second. 'part' counts a final line with no "
          "newline, which a reader should drop.")


if __name__ == "__main__":
    sys.exit(main())
