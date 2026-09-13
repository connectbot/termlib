//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalEmulator](index.md)

# TerminalEmulator

[release]\
sealed interface [TerminalEmulator](index.md)

Terminal emulator interface. This has no dependency on any UI framework so it may be run in a Service on Android. It handles the management of the terminal emulation state.

## Properties

| Name | Summary |
|---|---|
| [autoDetectUrls](auto-detect-urls.md) | [release]<br>abstract val [autoDetectUrls](auto-detect-urls.md): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)<br>Whether plain-text URL auto-detection is enabled. |
| [boldAsBright](bold-as-bright.md) | [release]<br>abstract val [boldAsBright](bold-as-bright.md): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)<br>Whether bold text using low-intensity ANSI colors (0–7) promotes to the corresponding bright palette color (8–15), matching xterm's boldColors behavior. |
| [dimensions](dimensions.md) | [release]<br>abstract val [dimensions](dimensions.md): [TerminalDimensions](../-terminal-dimensions/index.md) |

## Functions

| Name | Summary |
|---|---|
| [applyColorScheme](apply-color-scheme.md) | [release]<br>abstract fun [applyColorScheme](apply-color-scheme.md)(ansiColors: [IntArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int-array/index.html), defaultForeground: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), defaultBackground: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html))<br>Apply a complete color scheme to the terminal. |
| [clearScreen](clear-screen.md) | [release]<br>abstract fun [clearScreen](clear-screen.md)()<br>Clears the terminal emulator screen. |
| [dispatchCharacter](dispatch-character.md) | [release]<br>open fun [~~dispatchCharacter~~](dispatch-character.md)(modifiers: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), ch: [Char](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-char/index.html))<br>abstract fun [dispatchCharacter](dispatch-character.md)(modifiers: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), codepoint: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html))<br>Dispatch a character to the terminal. |
| [dispatchKey](dispatch-key.md) | [release]<br>abstract fun [dispatchKey](dispatch-key.md)(modifiers: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), key: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html))<br>Dispatch a key event to the terminal. |
| [getLastCommandOutput](get-last-command-output.md) | [release]<br>abstract fun [getLastCommandOutput](get-last-command-output.md)(): [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html)?<br>Get the text output of the last completed command. |
| [getUrls](get-urls.md) | [release]<br>abstract fun [getUrls](get-urls.md)(scope: [UrlScanScope](../-url-scan-scope/index.md) = UrlScanScope.CurrentView): [List](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-list/index.html)&lt;[TerminalUrl](../-terminal-url/index.md)&gt;<br>Extract URLs from terminal output. |
| [resize](resize.md) | [release]<br>abstract fun [resize](resize.md)(newRows: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), newCols: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html))<br>Resize the terminal. |
| [setAnsiPalette](set-ansi-palette.md) | [release]<br>abstract fun [setAnsiPalette](set-ansi-palette.md)(ansiColors: [IntArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int-array/index.html)): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)<br>Set ANSI palette colors (indices 0-15). |
| [setDefaultColors](set-default-colors.md) | [release]<br>abstract fun [setDefaultColors](set-default-colors.md)(foreground: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), background: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)<br>Set default terminal colors. |
| [writeInput](write-input.md) | [release]<br>abstract fun [writeInput](write-input.md)(buffer: [ByteBuffer](https://developer.android.com/reference/kotlin/java/nio/ByteBuffer.html), length: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html))<br>Write data to the terminal using ByteBuffer (more efficient for large data).<br>[release]<br>abstract fun [writeInput](write-input.md)(data: [ByteArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-byte-array/index.html), offset: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 0, length: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = data.size)<br>Write data to the terminal (from PTY/transport). |