# Running the capture benchmark

## End-to-end Compose frames

Build the release-derived, non-debuggable, shell-profileable benchmark app and
the separate Macrobenchmark runner:

```sh
./gradlew :test-app:assembleBenchmark :macrobenchmark:assembleBenchmark
python3 benchmark/run-frames.py "$PWD" build/terminal-frames \
  --serial DEVICE_SERIAL --adb "$ANDROID_HOME/platform-tools/adb"
python3 benchmark/summarize-frames.py build/terminal-frames \
  --processor /path/to/trace_processor
```

The runner installs both test APKs, compresses collected traces, and removes
their device-side copies after a successful pull. Prefer disk storage over
RAM-backed `/tmp`. It defaults to five 20-second measurements
per workload, with a fresh process and three-second warmup per iteration and
full ART compilation. `--workloads text,cacafire` selects workloads;
`--iterations 1 --duration-ms 5000` is a smoke test, not an acceptance run.
Emulators require the explicit `--emulator` override and cannot establish
physical-device performance. Capture assets for `cacafire` must be placed in
`lib/build/benchmark-assets` as described below before building the app.

Workloads are `idle`, colored Unicode `text`, captured `cacafire`, repeated
512×512 `png`/`rgba` image replacement, Kitty `animation`, and `input` (real
keyboard events and 12,000-character IME commits while terminal dimensions
alternate). Image transport is split into 4 KiB reads; decoding is triggered by
actual Compose display. The benchmark keeps a tiny independent draw heartbeat
running to expose responsiveness even when terminal content stalls.

`FrameTimingMetric` records CPU duration and deadline overrun through Android's
Compose/main-thread/RenderThread pipeline. Positive overrun means a missed
deadline; compare p95, p99, and the fraction of missed deadlines, not only the
median. Raw JSON and Perfetto traces are retained separately for each workload.
`terminal.benchmark.tick` marks reader input, and `terminal.draw.tick` comes
from the first eight cells of the snapshot read during terminal drawing. The
analysis script correlates that draw with FrameTimeline surface tokens to report
input-to-record and input-to-present latency, plus content lag in reader ticks.
It reports the number of successful correlations: missing/coalesced updates
must not be counted as zero-latency updates. The input workload additionally
records `terminal.benchmark.outputBytes` to verify output reaches the callback.
Input-to-present here measures incoming reader data, not remote keyboard echo.
The trace analysis also separates main-thread frame work from RenderThread work
and counts observed main-thread terminal monitor-contention events. Zero such
events is diagnostic evidence, not proof that Android or Compose uses no locks.
With `--processor`, the summarizer also checks Perfetto error/data-loss counters
and the captured reader interval. Invalid captures produce a nonzero exit code
and must be excluded from acceptance results. For a non-default duration, pass
the matching `--expected-duration-ms` (for example, `5000` for a smoke test).
The runner uses Benchmark 1.5.0; use the identical runner for both revisions.
Its frame-focused Perfetto configuration includes scheduling, framework draw
events, app markers, process names, and FrameTimeline. It intentionally excludes
unrelated system data sources: the default broad configuration stalled for
30 seconds at startup on the tested Android 17 development build.
`conditions.json` records device/build, requested sample count and duration,
and thermal state. Macrobenchmark closes the app after measuring, so capture
memory separately while the workload is still active.

For a before/after comparison, build exactly this harness against each library
revision, including the internal `TerminalFrameTrace` diagnostic draw hook.
Use the same device, refresh rate (the activity requests 60 Hz when available),
capture, font, terminal size, compilation mode, and iteration count. Run without
competing builds or tests. Alternate revision order for confirmation runs.

Suggested physical-device acceptance: at least 99% of frames meet deadlines,
text input-to-present p95 stays within two refresh intervals, and content lag
does not grow during sustained input. Investigate regressions above 5% rather
than dismissing them as noise. Also test scrollback, platform GIF/WebP,
detach/reattach, and actual remote keyboard echo on representative devices;
this synthetic suite alone does not certify those cases.

## Parser and memory microbenchmarks

### Sample CPU hot paths on Android

With the benchmark app installed, run this separately from Macrobenchmark:

```sh
python3 benchmark/profile-frames.py build/cacafire-profile \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial DEVICE_SERIAL
/path/to/ndk/simpleperf/bin/linux/x86_64/simpleperf report \
  -i build/cacafire-profile/perf.data --children --sort comm,symbol \
  --symdir /path/to/matching/unstripped/arm64-v8a/libraries
```

This uses the device's system `simpleperf`, full ART compilation, three seconds
of warmup, and 20 seconds of 500 Hz userspace task-clock sampling with call
stacks. It does not restart ADB or require root. Keep matching unstripped native
libraries (check build IDs) for symbolization. `--workload` selects another
workload. Profiles with dropped samples or truncated duration are rejected.
Sampling perturbs execution: use these profiles to choose optimizations, then
use separate frame benchmarks to assess them. `meminfo.txt` captures the live
app after profiling; it is not a peak-memory or allocation-traffic measurement.
See the [Android simpleperf documentation](https://android.googlesource.com/platform/system/extras/+/master/simpleperf/doc/android_application_profiling.md).

### Replay and allocation measurements

Capture once and use the same files for both revisions:

```sh
python3 benchmark/capture-cacafire.py
TERMLIB_CACAFIRE=/tmp/termlib-cacafire TERMLIB_BENCHMARK=1 \
  ./gradlew :lib:testDebugUnitTest --tests '*BoundaryBenchmarkTest' \
  --no-configuration-cache --rerun-tasks
```

The replay groups captured reads into 60 Hz frames, warms up twice, then reports
the median of five complete replays. Capture parsing and input-array preparation
are outside the measured loop. This measures parsing and snapshot construction,
not end-to-end Compose frame presentation. `BOUNDARY_TRANSFER` reports screen
retrieval calls and bytes (records plus used descriptors), excluding input,
scrollback callbacks, and OSC messages.

For ART, place the same capture in ignored test-only build assets:

```sh
mkdir -p lib/build/benchmark-assets
cp /tmp/termlib-cacafire.bin lib/build/benchmark-assets/cacafire.bin
cp /tmp/termlib-cacafire.json lib/build/benchmark-assets/cacafire.json
./gradlew :lib:connectedDebugAndroidTest --no-configuration-cache
adb logcat -d -s TermMemoryBenchmark:I '*:S'
```

The capture is not packaged into the library. The ART replay is skipped when the
assets are absent. ART allocations are process-wide runtime counters; host
allocations are per-thread counters, so compare revisions within each runtime.
Run benchmarks without another build or benchmark competing for CPU.

To measure Kitty RGBA stream ingestion, retained encoded bytes, and first-frame
decode cost, run:

```sh
TERMLIB_BENCHMARK=1 ./gradlew :lib:testDebugUnitTest \
  --tests '*InlineImageBenchmarkTest' --no-configuration-cache --rerun-tasks
```

The optional Kitty TGP 001 transcript test keeps the third-party archive out of
the source tree. Extract it locally and point the test at the directory that
contains `timing3` and `typescript3`:

```sh
mkdir -p /tmp/kitty-tgp-001
tar -xzf kitty-TGP-001.tar.gz -C /tmp/kitty-tgp-001
TERMLIB_KITTY_TGP=/tmp/kitty-tgp-001 ./gradlew :lib:testDebugUnitTest \
  --tests '*InlineImageTest.kittyTgp001Transcript' --no-configuration-cache
```

The replay honors the recorded output boundaries but omits delays. It verifies
all 36 uploads, the peak set of 18 simultaneous placements, and the absence of
protocol rejections without packaging the fixture in the library or test APK.
