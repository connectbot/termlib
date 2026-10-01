//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalDimensions](index.md)

# TerminalDimensions

[release]\
data class [TerminalDimensions](index.md)(val rows: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), val columns: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), val widthPixels: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), val heightPixels: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html))

Represents the terminal's character grid and physical viewport.

Pixel dimensions are populated by the Compose [Terminal](../-terminal.md). Headless users get dimensions based on the cell size supplied to [TerminalEmulator.setCellPixelSize](../-terminal-emulator/set-cell-pixel-size.md).

## Constructors

| | |
|---|---|
| [TerminalDimensions](-terminal-dimensions.md) | [release]<br>constructor(rows: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), columns: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), widthPixels: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), heightPixels: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html))constructor(rows: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), columns: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html))<br>Retained for source and binary compatibility with character-only callers. |

## Properties

| Name | Summary |
|---|---|
| [columns](columns.md) | [release]<br>val [columns](columns.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |
| [heightPixels](height-pixels.md) | [release]<br>val [heightPixels](height-pixels.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |
| [rows](rows.md) | [release]<br>val [rows](rows.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |
| [widthPixels](width-pixels.md) | [release]<br>val [widthPixels](width-pixels.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |

## Functions

| Name | Summary |
|---|---|
| [copy](copy.md) | [release]<br>fun [copy](copy.md)(rows: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), columns: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)): [TerminalDimensions](index.md)<br>Retained for binary compatibility with the original two-field data class. |