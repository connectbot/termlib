//[ConnectBot Terminal](../../../../index.md)/[org.connectbot.terminal](../../index.md)/[TerminalEmulatorFactory](../index.md)/[Companion](index.md)/[create](create.md)

# create

[release]\
fun [create](create.md)(looper: [Looper](https://developer.android.com/reference/kotlin/android/os/Looper.html) = Looper.getMainLooper(), initialRows: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 24, initialCols: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 80, defaultForeground: [Color](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Color.html) = Color.White, defaultBackground: [Color](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Color.html) = Color.Black, onKeyboardInput: ([ByteArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-byte-array/index.html)) -&gt; [Unit](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-unit/index.html) = {}, onBell: () -&gt; [Unit](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-unit/index.html)? = null, onResize: ([TerminalDimensions](../../-terminal-dimensions/index.md)) -&gt; [Unit](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-unit/index.html)? = null, onClipboardCopy: ([String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html)) -&gt; [Unit](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-unit/index.html)? = null, onProgressChange: ([ProgressState](../../-progress-state/index.md), [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)) -&gt; [Unit](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-unit/index.html)? = null, autoDetectUrls: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = false, boldAsBright: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = true, inlineImages: [InlineImages](../../-inline-images/index.md) = InlineImages.Off): [TerminalEmulator](../../-terminal-emulator/index.md)

Creates the default implementation of TerminalEmulator.

#### Parameters

release

| | |
|---|---|
| looper | The Looper to use for callback handling (typically main looper) |
| initialRows | Initial number of rows |
| initialCols | Initial number of columns |
| defaultForeground | Default foreground color |
| defaultBackground | Default background color |
| onKeyboardInput | Callback for keyboard output (to write to PTY) |
| onBell | Optional callback for terminal bell |
| onResize | Optional callback for terminal resize |
| onClipboardCopy | Optional callback for OSC 52 clipboard copy operations.                         The callback receives the decoded text to copy. |
| onProgressChange | Optional callback for OSC 9;4 progress reporting.                          The callback receives the progress state and percentage (0-100). |
| autoDetectUrls | Whether to continuously scan visible terminal line text for                        plain-text URLs and expose them via hit-testing as a fallback                        when no OSC 8 hyperlink covers the column. Defaults to false.                        [TerminalEmulator.getUrls](../../-terminal-emulator/get-urls.md) always performs its own one-shot                        regex URL scan regardless of this setting. |
| boldAsBright | Whether bold text using low-intensity ANSI colors (0–7) promotes to                      the corresponding bright palette color (8–15), matching xterm's                      default boldColors behavior. Defaults to true. |
| inlineImages | Policy for accepting iTerm2 and Kitty inline images. Defaults to off. |