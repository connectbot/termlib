//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalEmulator](index.md)/[applyColorScheme](apply-color-scheme.md)

# applyColorScheme

[release]\
abstract fun [applyColorScheme](apply-color-scheme.md)(ansiColors: [IntArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int-array/index.html), defaultForeground: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), defaultBackground: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html))

Apply a complete color scheme to the terminal.

Convenience method that sets both ANSI palette and default colors from a color scheme. This is the recommended way to apply themes.

#### Parameters

release

| | |
|---|---|
| ansiColors | IntArray of 16 ARGB colors for ANSI palette |
| defaultForeground | ARGB color for default foreground |
| defaultBackground | ARGB color for default background |