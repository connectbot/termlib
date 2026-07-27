#!/usr/bin/env python3
"""Sample CPU stacks in the installed release-derived terminal benchmark app.

Uses the device's system simpleperf and shell-profileable app, without adb root.
Run separately from frame-timing measurements: sampling perturbs execution.
"""
import argparse
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output", type=Path)
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
parser.add_argument("--workload", default="cacafire", choices=("text", "cacafire", "rgba", "png", "animation", "input", "idle"))
parser.add_argument("--duration", type=int, default=20)
parser.add_argument("--frequency", type=int, default=500)
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.output.name):
    parser.error("output directory name must contain only letters, numbers, dots, underscores or hyphens")
if args.duration <= 0 or args.frequency <= 0:
    parser.error("duration and frequency must be positive")
args.output.mkdir(parents=True, exist_ok=False)
adb = [args.adb, "-s", args.serial]
package = "org.connectbot.terminal.testapp"
remote = f"/data/local/tmp/termlib-{args.output.name}.data"


def require_foreground():
    state = subprocess.run(
        adb + ["shell", "dumpsys", "activity", "activities"],
        check=True, capture_output=True, text=True, timeout=30,
    ).stdout
    resumed = next((line for line in state.splitlines() if "topResumedActivity=" in line), "")
    if package not in resumed:
        raise RuntimeError(f"Benchmark activity is not foreground: {resumed.strip() or 'unknown'}")


try:
    subprocess.run(adb + ["shell", "cmd", "package", "compile", "-m", "speed", "-f", package], check=True, timeout=120)
    subprocess.run(adb + ["shell", "am", "start", "-W", "-S", "-n", package + "/.FrameBenchmarkActivity",
                          "--es", "workload", args.workload], check=True, timeout=60)
    require_foreground()
    with (args.output / "record.txt").open("w") as log:
        subprocess.run(adb + ["shell", "simpleperf", "record", "--app", package,
                              "-e", "task-clock:u", "-f", str(args.frequency), "-g",
                              "--delay", "3000", "--duration", str(args.duration),
                              "--size-limit", "128M", "-o", remote], check=True,
                       stdout=log, stderr=subprocess.STDOUT, timeout=args.duration + 120)
    subprocess.run(adb + ["pull", remote, str(args.output / "perf.data")], check=True, timeout=120)
    record_log = (args.output / "record.txt").read_text()
    lost = re.search(r"Samples lost:\s*([0-9,]+)", record_log)
    recorded = re.search(r"Recorded for\s+([0-9.]+) seconds", record_log)
    samples = re.search(r"Samples recorded:\s*([0-9,]+)", record_log)
    minimum_samples = max(100, args.duration * args.frequency // 20)
    if (not lost or int(lost.group(1).replace(",", "")) or
            not recorded or float(recorded.group(1)) < args.duration * 0.9 or
            not samples or int(samples.group(1).replace(",", "")) < minimum_samples):
        raise RuntimeError("Incomplete CPU profile: inspect record.txt before using its results")
    require_foreground()
    with (args.output / "meminfo.txt").open("w") as memory:
        subprocess.run(adb + ["shell", "dumpsys", "meminfo", package], check=True, stdout=memory, timeout=30)
    if "Process is frozen" in (args.output / "meminfo.txt").read_text():
        raise RuntimeError("Benchmark process was frozen during profile collection")
finally:
    subprocess.run(adb + ["shell", "rm", remote], check=False, timeout=30)
    subprocess.run(adb + ["shell", "am", "force-stop", package], check=False, timeout=30)
