#!/usr/bin/env python3
"""Summarize Macrobenchmark JSON and optionally correlate content with Perfetto.

Usage: python3 benchmark/summarize-frames.py RESULTS --processor /path/to/trace_processor
The processor may also be the official get.perfetto.dev Python launcher.
"""
import argparse
from bisect import bisect_left
from collections import defaultdict
import csv
import io
import json
from pathlib import Path
import subprocess
import sys


def percentile(values, percent):
    if not values:
        return None
    values = sorted(values)
    index = (len(values) - 1) * percent / 100
    low = int(index)
    high = min(low + 1, len(values) - 1)
    return round(values[low] + (values[high] - values[low]) * (index - low), 3)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("results", type=Path)
parser.add_argument("--processor", type=Path)
parser.add_argument("--expected-duration-ms", type=float, default=20_000,
                    help="Expected capture duration; reject windows differing by more than 10%% or one second")
args = parser.parse_args()
summary = []
for filename in sorted(args.results.rglob("*benchmarkData.json")):
    data = json.loads(filename.read_text())
    for bench in data["benchmarks"]:
        metrics = bench.get("sampledMetrics", {})
        result = {"file": str(filename), "name": bench["name"]}
        for name, metric in metrics.items():
            samples = [value for run in metric["runs"] for value in run]
            result[name] = {f"p{p}": percentile(samples, p) for p in (50, 95, 99)}
            if name == "frameOverrunMs":
                result["frames"] = len(samples)
                result["missed_deadline_percent"] = round(100 * sum(v > 0 for v in samples) / len(samples), 3)
                result["iterations"] = len(metric["runs"])
                result["missed_deadline_percent_per_run"] = [
                    round(100 * sum(v > 0 for v in run) / len(run), 3) if run else None
                    for run in metric["runs"]
                ]
        summary.append(result)

if args.processor:
    command = [str(args.processor)]
    with args.processor.open("rb") as executable:
        launcher = executable.read(2) == b"#!"
    if launcher:
        command.insert(0, sys.executable)
    query = """
    WITH draws AS (
      SELECT c.ts, c.value FROM counter c JOIN counter_track t ON c.track_id=t.id
      WHERE t.name='terminal.draw.tick'
    ), inputs AS (
      SELECT c.ts, c.value FROM counter c JOIN counter_track t ON c.track_id=t.id
      WHERE t.name='terminal.benchmark.tick'
    ), frames AS MATERIALIZED (
      SELECT s.ts, s.dur, s.depth, s.name, p.upid
      FROM slice s JOIN thread_track tt ON s.track_id=tt.id
      JOIN thread t USING(utid) JOIN process p USING(upid)
      WHERE s.name GLOB 'Choreographer#doFrame *'
        AND p.name='org.connectbot.terminal.testapp' AND t.tid=p.pid
    ), recorded AS (
      SELECT d.ts, d.value, s.depth,
        CAST(STR_SPLIT(s.name,' ',CASE WHEN s.name GLOB '*resynced*' THEN 4 ELSE 1 END) AS INT) token,
        s.upid
      FROM draws d JOIN frames s ON d.ts BETWEEN s.ts AND s.ts+s.dur
    ), correlated AS (
    SELECT CAST(r.value AS INTEGER) tick, f.present_type, r.ts/1e6 draw_ts_ms, (r.ts-i.ts)/1e6 input_to_record_ms,
      (f.ts+f.dur-i.ts)/1e6 input_to_present_ms,
      (SELECT max(value) FROM inputs WHERE ts<=r.ts)-r.value behind_ticks,
      ROW_NUMBER() OVER(PARTITION BY r.ts ORDER BY f.ts IS NULL, r.depth DESC) choice
    FROM recorded r JOIN inputs i ON i.value=r.value AND i.ts<=r.ts
    LEFT JOIN actual_frame_timeline_slice f ON f.upid=r.upid AND f.surface_frame_token=r.token
    WHERE (f.dur IS NULL OR f.dur>0)
    ) SELECT * FROM correlated WHERE choice=1
    """
    for trace in sorted(args.results.rglob("*.perfetto-trace*")):
        health_query = """
        SELECT name, value FROM stats
        WHERE value>0 AND severity IN ('error', 'data_loss')
        """
        completed = subprocess.run(command + ["query", str(trace), health_query], check=True, text=True, capture_output=True)
        health = list(csv.DictReader(io.StringIO(completed.stdout)))
        completed = subprocess.run(command + ["query", str(trace), query], check=True, text=True, capture_output=True)
        rows = list(csv.DictReader(io.StringIO(completed.stdout)))
        result = {"trace": str(trace), "content_samples": len(rows), "trace_errors": health}
        drawn_ticks = {int(row["tick"]) for row in rows}
        result["distinct_drawn_ticks"] = len(drawn_ticks)
        first_present = {}
        for row in rows:
            if row["present_type"] in ("Early Present", "On-time Present", "Late Present"):
                latency = float(row["input_to_present_ms"])
                tick = int(row["tick"])
                first_present[tick] = min(first_present.get(tick, latency), latency)
        result["distinct_presented_ticks"] = len(first_present)
        result["first_input_to_present_ms"] = {
            f"p{p}": percentile(list(first_present.values()), p) for p in (50, 95, 99)
        }
        for field in ("input_to_record_ms", "input_to_present_ms", "behind_ticks"):
            samples = [float(row[field]) for row in rows if row[field] not in ("[NULL]", "")]
            result[field] = {f"p{p}": percentile(samples, p) for p in (50, 95, 99)}
            result[field]["samples"] = len(samples)
        timing_query = """
        SELECT CASE
          WHEN s.name GLOB 'monitor contention*' THEN 'main_backend_monitor_ms'
          WHEN t.tid=p.pid THEN 'main_frame_ms' ELSE 'render_thread_frame_ms'
          END metric, s.dur/1e6 duration, NULL tick
        FROM slice s JOIN thread_track tt ON s.track_id=tt.id
        JOIN thread t USING(utid) JOIN process p USING(upid)
        WHERE p.name='org.connectbot.terminal.testapp' AND s.dur>0 AND (
          (t.tid=p.pid AND s.name GLOB 'Choreographer#doFrame *' AND s.name NOT GLOB '*resynced*') OR
          (t.name='RenderThread' AND s.name GLOB 'DrawFrame*') OR
          (t.tid=p.pid AND s.name GLOB 'monitor contention*org.connectbot.terminal*')
        )
        UNION ALL
        SELECT 'refresh_hz', c.value/1000, NULL FROM counter c
        JOIN counter_track t ON c.track_id=t.id
        WHERE t.name='terminal.benchmark.refreshMilliHz'
        UNION ALL
        SELECT 'accessibility_enabled', c.value, NULL FROM counter c
        JOIN counter_track t ON c.track_id=t.id
        WHERE t.name='terminal.benchmark.accessibilityEnabled'
        UNION ALL
        SELECT 'reader_input_ts_ms', c.ts/1e6, CAST(c.value AS INTEGER) FROM counter c
        JOIN counter_track t ON c.track_id=t.id
        WHERE t.name='terminal.benchmark.tick'
        UNION ALL
        SELECT 'expected_app_frame_ts_ms', f.ts/1e6, NULL FROM expected_frame_timeline_slice f
        JOIN process p USING(upid)
        WHERE p.name='org.connectbot.terminal.testapp'
        """
        completed = subprocess.run(command + ["query", str(trace), timing_query], check=True, text=True, capture_output=True)
        timings = list(csv.DictReader(io.StringIO(completed.stdout)))
        for metric in ("main_frame_ms", "render_thread_frame_ms", "main_backend_monitor_ms", "refresh_hz"):
            samples = [float(row["duration"]) for row in timings if row["metric"] == metric]
            result[metric] = {f"p{p}": percentile(samples, p) for p in (50, 95, 99)}
            result[metric]["samples"] = len(samples)
        accessibility = [int(float(row["duration"])) for row in timings if row["metric"] == "accessibility_enabled"]
        result["accessibility"] = {"enabled_samples": sum(accessibility), "samples": len(accessibility)}
        input_ticks = [
            (int(row["tick"]), float(row["duration"]))
            for row in timings if row["metric"] == "reader_input_ts_ms"
        ]
        input_times = [ts for _, ts in input_ticks]
        expected_frames = sorted(float(row["duration"]) for row in timings if row["metric"] == "expected_app_frame_ts_ms")
        input_start, input_end = (min(input_times), max(input_times)) if input_times else (0, 0)
        phase_samples = []
        phase_bins = defaultdict(lambda: {"input_ticks": 0, "drawn_ticks": 0, "presented_ticks": 0})
        for tick, input_ts in input_ticks:
            next_frame = bisect_left(expected_frames, input_ts)
            if 0 < next_frame < len(expected_frames):
                phase = expected_frames[next_frame] - input_ts
                phase_samples.append(phase)
                if input_start + 100 <= input_ts <= input_end - 100:
                    counts = phase_bins[int(phase * 2) / 2]
                    counts["input_ticks"] += 1
                    counts["drawn_ticks"] += tick in drawn_ticks
                    counts["presented_ticks"] += tick in first_present
        result["input_to_expected_frame_ms"] = {f"p{p}": percentile(phase_samples, p) for p in (5, 50, 95)}
        result["input_to_expected_frame_ms"]["samples"] = len(phase_samples)
        result["content_by_input_phase"] = [
            {"phase_start_ms": start, "phase_end_ms": start + 0.5, **counts}
            for start, counts in sorted(phase_bins.items())
        ]
        result["reader_samples"] = len(input_times)
        result["presented_tick_percent"] = round(100 * len(first_present) / len(input_times), 3) if input_times else None
        observed_duration = input_end - input_start
        result["observed_duration_ms"] = round(observed_duration, 3)
        result["valid_capture"] = not health and abs(observed_duration - args.expected_duration_ms) <= max(1000, args.expected_duration_ms * 0.1)
        if rows and input_times:
            events = sorted((float(row["draw_ts_ms"]), row["tick"]) for row in rows)
            progress = [events[0][0]] + [events[i][0] for i in range(1, len(events)) if events[i][1] != events[i - 1][1]]
            gaps = [progress[0] - input_start, input_end - progress[-1]]
            gaps += [end - start for start, end in zip(progress, progress[1:])]
            result["max_content_record_gap_ms"] = round(max(gaps), 3)
            result["drawn_tick_percent"] = round(100 * result["distinct_drawn_ticks"] / len(input_times), 3)
        if first_present:
            presentation_times = sorted(ts + first_present[tick] for tick, ts in input_ticks if tick in first_present)
            gaps = [presentation_times[0] - input_start, input_end - presentation_times[-1]]
            gaps.extend(end - start for start, end in zip(presentation_times, presentation_times[1:]))
            result["max_content_present_gap_ms"] = round(max(gaps), 3)
        summary.append(result)
print(json.dumps(summary, indent=2))
if any(result.get("valid_capture") is False for result in summary):
    print("Invalid capture: trace errors/data loss or unexpected measurement duration; do not use for acceptance.", file=sys.stderr)
    sys.exit(1)
