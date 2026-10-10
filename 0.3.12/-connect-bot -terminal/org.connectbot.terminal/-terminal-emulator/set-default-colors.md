//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalEmulator](index.md)/[setDefaultColors](set-default-colors.md)

# setDefaultColors

[release]\
abstract fun [setDefaultColors](set-default-colors.md)(foreground: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), background: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)

Set default terminal colors.

These colors are used when terminal content explicitly requests &quot;default&quot; foreground or background (different from ANSI color 7/0). Changing default colors triggers a full redraw.

#### Return

0 on success, -1 on error

#### Parameters

release

| | |
|---|---|
| foreground | ARGB foreground color |
| background | ARGB background color |