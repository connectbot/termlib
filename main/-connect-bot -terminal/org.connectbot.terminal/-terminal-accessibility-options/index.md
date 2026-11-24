//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalAccessibilityOptions](index.md)

# TerminalAccessibilityOptions

[release]\
data class [TerminalAccessibilityOptions](index.md)(val announceOutput: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = true, val announceCursor: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = true, val requestInitialFocus: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = false)

Feedback preferences. Direct terminal input remains the default input mode.

## Constructors

| | |
|---|---|
| [TerminalAccessibilityOptions](-terminal-accessibility-options.md) | [release]<br>constructor(announceOutput: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = true, announceCursor: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = true, requestInitialFocus: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = false)constructor(announceOutput: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = true, announceCursor: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = true) |

## Properties

| Name | Summary |
|---|---|
| [announceCursor](announce-cursor.md) | [release]<br>val [announceCursor](announce-cursor.md): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) |
| [announceOutput](announce-output.md) | [release]<br>val [announceOutput](announce-output.md): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) |
| [requestInitialFocus](request-initial-focus.md) | [release]<br>val [requestInitialFocus](request-initial-focus.md): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)<br>One initial focus request per view, when the hosting screen is ready. |

## Functions

| Name | Summary |
|---|---|
| [copy](copy.md) | [release]<br>fun [copy](copy.md)(announceOutput: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = this.announceOutput, announceCursor: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = this.announceCursor): [TerminalAccessibilityOptions](index.md) |