//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalEmulator](index.md)/[setAnsiPalette](set-ansi-palette.md)

# setAnsiPalette

[release]\
abstract fun [setAnsiPalette](set-ansi-palette.md)(ansiColors: [IntArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int-array/index.html)): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)

Set ANSI palette colors (indices 0-15).

This configures the 16 ANSI colors used by terminal escape sequences. Changing the palette triggers a full redraw with new colors.

#### Return

Number of colors set, or -1 on error

#### Parameters

release

| | |
|---|---|
| ansiColors | IntArray of ARGB colors (size 16 for all ANSI colors) |