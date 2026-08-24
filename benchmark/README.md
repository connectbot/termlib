# Running the capture benchmark

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
