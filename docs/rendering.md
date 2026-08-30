# Terminal text rendering

## Compose scheduling and input ordering

Terminal input parsing remains synchronous: feed `writeInput` from the caller's
reader/IO thread, not the UI thread. The input buffer can be reused when the
call returns. Snapshot construction is coalesced on a background dispatcher;
Compose applies the latest snapshot on its frame clock. Row content is read in
the draw phase, so ordinary text damage does not recompose every row.
Native cursor movement notifications are also coalesced within each
`writeInput`: libvterm retains every logical movement, while rendering receives
the final position, visibility, and the batch's original position once the
write has completed. Cursor callbacks outside input writes remain immediate.

Composition, layout, and drawing do not acquire terminal or image-store locks.
Compose submits keyboard/IME input, resize, and cell-size updates asynchronously.
Existing synchronous public methods retain their behavior and may still block
when explicitly called by an application on the UI thread.

Keyboard events, committed IME text, and `TerminalEmulator.pasteText(text)` use
one FIFO per terminal. Each paste or IME commit is an indivisible command group;
UTF-8 output may be split into transport chunks, but later typing cannot appear
between them. Paste honors the application's bracketed-paste mode. Handle
`onPasteRequest` by obtaining clipboard text and calling `pasteText`; writing
clipboard data directly to a separate transport bypasses this ordering.
Output callbacks still run on the configured Handler. Ordering starts when text
is submitted, not when an asynchronous clipboard request was initiated.

Image decode requests and animation advancement are coalesced off the UI thread.
Draw reads a published bitmap/drawable handle, without consulting the mutable
image store. Platform drawable lifecycle work remains on the main thread.
Published bitmaps are not recycled while recorded draws may reference them;
retired resources remain in the decode budget until weak-reference notification
reports their release. Memory pressure can therefore defer a decode rather than
exceed the budget.
This accounts for library-owned image resources and estimated decoder working
memory, not a hard process-RAM ceiling: Android's RenderThread, GPU caches, and
allocator overhead can retain additional native memory beyond Java lifetimes.

The frame benchmark in `benchmark/README.md` measures the complete Android
Compose/RenderThread pipeline. Removing backend locks does not guarantee every
device meets 60 Hz: text shaping, recording, GPU work, GC, and scheduling still
consume frame time.

Terminal columns come from the native parser, independently of glyph advances.
Emoji properties and sequences are generated from Unicode 17.0.0 by `python3
tools/generate-unicode.py`. The source files, generated tables, and source
hashes are checked in. Normal builds require no network access; `--check`
verifies reproducibility and `--download` refreshes the pinned inputs.
Changing Unicode versions is an explicit compatibility change.

Default emoji presentation, valid variation selectors, and complete recognized
emoji sequences use two columns. Text presentation keeps the base text width.
An incomplete sequence is displayed immediately and may extend the preceding
cell on subsequent input. Input chunk boundaries do not terminate a sequence.
Terminal commands do. A widening cluster at the right margin wraps when
autowrap is enabled; narrowing never undoes a previous wrap. With autowrap off,
the complete text is retained in the available margin cell. Older remote
applications may use different width tables.

Native and JNI cells hold sixteen code points. Longer combining input continues
in another cell instead of being discarded. The generated supported emoji
sequences are checked against that capacity.

The renderer paints all backgrounds before any foreground text, including
selection backgrounds. Complete cell text is shaped together at the cell's
column origin. Oversized advances are compressed horizontally; normal advances
and italic overhang are preserved. Decorations follow terminal columns. Each
row's Compose display list has viewport bounds, so changes invalidate previous
ink outside the row without retaining a screen-sized bitmap. The magnifier uses
the same two passes.

Measurements are cached in one lazy float array per packed row, replaced when
the font or size changes. The Compose renderer also uses a bounded 2 KiB
single-glyph Latin-1 advance cache per font/size paint, with separate regular
and bold entries. Complex clusters still use normal platform shaping. This
avoids repeating text measurement just because row contents/colors changed.
Regular glyphs use direct Canvas drawing; only
oversized glyphs require a canvas transform.

## Box drawing and block elements

Box drawing (U+2500–U+257F) and block elements (U+2580–U+259F) use
cell-relative Canvas geometry instead of font outlines. Integer row metrics
remain unchanged. Shared rounded boundaries keep full blocks, fractional
blocks, quadrants, and box junctions continuous across cells. Straight edges
are drawn without antialiasing; arcs and diagonals are antialiased.
Light/heavy Unicode variants control line weight independently of SGR bold or
italic. Selection and reverse video still determine the drawing color.
Shades use fine repeating stipples with approximately 25%, 50%, and 75%
foreground coverage, following kitty's patterned appearance.

Only standalone characters take this path; combining clusters retain font
shaping. The magnifier uses the same renderer. Powerline, Braille, and symbols
outside these two Unicode ranges retain normal font rendering.

The Roborazzi `BoxDrawingGoldenTest` specimens include all 160 characters,
connected boxes, mixed junctions, shades, and the full-block O at 13, 16,
and 21 pixel text sizes. The specimens render parsed terminal snapshots using
the shared two-pass renderer with native graphics, then capture the resulting
bitmap through Roborazzi. Review generated images visually before accepting
their appearance as a baseline:

```sh
./gradlew :lib:recordRoborazziDebug --tests org.connectbot.terminal.BoxDrawingGoldenTest
./gradlew :lib:verifyRoborazziDebug --tests org.connectbot.terminal.BoxDrawingGoldenTest
```

## Explicit font fallback

Callers can pass a `Typeface` built with `CustomFallbackBuilder` on API 29+.
Load actual font data into `Font` objects, combine styles into families, and
order custom families before the system fallback. An already constructed
`Typeface` cannot be used as a `FontFamily`. Font downloads and configuration
belong to the application. The test app demonstrates asset-backed construction.

Fallback follows coverage, not a user-specified Unicode-range mapping. Explicit
font files avoid system family-name substitutions for covered characters, but
system fallback and shaping still use Android. Font selection never changes
terminal column accounting.

Host tests use tiny original geometric fonts, generated by `uv run --with
fonttools python tools/generate-font-fixtures.py`.  Android tests verify Canvas
fallback on API 29+ and actual selected fonts with `TextRunShaper` on API 31+,
including VS16 selecting system emoji when the primary custom font covers the
unadorned text symbol. These tests passed on an API 36 emulator; OEM-specific
behavior still requires device testing. The fixtures are test-only and are not
packaged in the published library.

## Verification

Run the host unit suite and `:lib:verifyRoborazziDebug`. Goldens cover emoji,
combining marks, colors, selection, and italic overhang. Independent bitmap
assertions check placement and background ordering. Android's
`TerminalRedrawTest` compares incremental rendering with fresh surfaces using
real window capture; Robolectric's PixelCopy path cannot perform that capture.

`AndroidRenderBenchmarkTest` measures warmed software Canvas rendering on ART,
not GPU frame presentation. Enable it with
`-Pandroid.testInstrumentationRunnerArguments.renderBenchmark=true`.  Use the
capture/replay and memory tests described in `benchmark/README.md` to measure
the native boundary separately.
