#!/usr/bin/env python3
"""Run already-built benchmark APKs, keeping each workload's raw artifacts.

Build :test-app:assembleBenchmark :macrobenchmark:assembleBenchmark first.
This installs the supplied test app and instrumentation APK on the selected
device. Do not run other builds/benchmarks concurrently with measurements.
"""
import argparse
import gzip
import json
from pathlib import Path
import re
import shutil
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("checkout", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--workloads", default="idle,text,cacafire,png,rgba,animation,input")
parser.add_argument("--iterations", default="5")
parser.add_argument("--duration-ms", default="20000")
parser.add_argument("--emulator", action="store_true", help="Explicitly suppress the emulator validity warning")
parser.add_argument("--skip-runner-install", action="store_true", help="Reuse the identical runner APK already installed by a previous run")
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.output.name):
    parser.error("output directory name must use only letters, numbers, dots, underscores or hyphens")
workloads = args.workloads.split(",")
if any(workload not in ("idle", "text", "cacafire", "png", "rgba", "animation", "input") for workload in workloads):
    parser.error("unknown workload")
adb = [args.adb, "-s", args.serial]
args.output.mkdir(parents=True, exist_ok=True)
for apk in ("test-app/build/outputs/apk/benchmark/test-app-benchmark.apk", "macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk"):
    if args.skip_runner_install and apk.startswith("macrobenchmark/"):
        continue
    subprocess.run(adb + ["install", "-r", str(args.checkout / apk)], check=True)
for workload in workloads:
    output = args.output / workload
    if output.exists() and any(output.iterdir()):
        raise RuntimeError(f"Refusing to mix new measurements with existing artifacts: {output}")
    output.mkdir(exist_ok=True)
    metadata = {"serial": args.serial, "checkout": str(args.checkout), "workload": workload,
                "iterations": int(args.iterations), "duration_ms": int(args.duration_ms)}
    for label, command in (("model", ["getprop", "ro.product.model"]),
                           ("build", ["getprop", "ro.build.fingerprint"]),
                           ("thermal_before", ["dumpsys", "thermalservice"]),
                           ("battery_before", ["dumpsys", "battery"])):
        metadata[label] = subprocess.run(adb + ["shell"] + command, check=True, text=True, capture_output=True).stdout
    (output / "conditions.json").write_text(json.dumps(metadata, indent=2))
    remote = f"/sdcard/Android/media/org.connectbot.terminal.benchmark/{args.output.name}/{workload}"
    command = adb + ["shell", "am", "instrument", "-w", "-r",
                     "-e", "workload", workload, "-e", "iterations", args.iterations,
                     "-e", "durationMs", args.duration_ms, "-e", "additionalTestOutputDir", remote]
    if args.emulator:
        command += ["-e", "androidx.benchmark.suppressErrors", "EMULATOR"]
    command += ["org.connectbot.terminal.benchmark/androidx.test.runner.AndroidJUnitRunner"]
    print(f"Measuring {args.output.name}/{workload}", flush=True)
    # Do not keep test/build processes alive together on a memory-constrained host.
    # Abort the test if Linux reports dangerously little memory remaining.
    with (output / "instrumentation.txt").open("w") as log:
        process = subprocess.Popen(command, text=True, stdout=log, stderr=subprocess.STDOUT)
        try:
            while process.poll() is None:
                meminfo = Path("/proc/meminfo")
                if meminfo.exists():
                    available = next(int(line.split()[1]) for line in meminfo.read_text().splitlines() if line.startswith("MemAvailable:"))
                    if available < 1536 * 1024:
                        raise RuntimeError("Stopped benchmark: host MemAvailable fell below 1.5 GiB")
                time.sleep(5)
        except BaseException:
            for package in ("org.connectbot.terminal.benchmark", "org.connectbot.terminal.testapp"):
                subprocess.run(adb + ["shell", "am", "force-stop", package], check=False)
            process.terminate()
            process.wait()
            raise
    text = (output / "instrumentation.txt").read_text()
    if process.returncode or "FAILURES" in text or "INSTRUMENTATION_FAILED" in text or "OK (1 test)" not in text:
        raise RuntimeError(f"Benchmark failed: {output / 'instrumentation.txt'}")
    subprocess.run(adb + ["pull", remote + "/.", str(output)], check=True)
    metadata["thermal_after"] = subprocess.run(adb + ["shell", "dumpsys", "thermalservice"], check=True, text=True, capture_output=True).stdout
    (output / "conditions.json").write_text(json.dumps(metadata, indent=2))
    # Keep traces, but don't consume gigabytes of tmpfs or duplicate them on device.
    for trace in output.glob("*.perfetto-trace"):
        with trace.open("rb") as source, gzip.open(str(trace) + ".gz", "wb", compresslevel=1) as target:
            shutil.copyfileobj(source, target)
        subprocess.run(adb + ["shell", "rm", remote + "/" + trace.name], check=True)
        trace.unlink()
    print(f"Saved {output}", flush=True)
