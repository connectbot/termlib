//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalAccessibilityController](index.md)

# TerminalAccessibilityController

[release]\
interface [TerminalAccessibilityController](index.md)

Main-thread controls for inspecting a frozen copy of terminal output.

## Properties

| Name | Summary |
|---|---|
| [isReviewing](is-reviewing.md) | [release]<br>abstract val [isReviewing](is-reviewing.md): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) |

## Functions

| Name | Summary |
|---|---|
| [refreshReview](refresh-review.md) | [release]<br>abstract fun [refreshReview](refresh-review.md)() |
| [requestAccessibilityFocus](request-accessibility-focus.md) | [release]<br>open fun [requestAccessibilityFocus](request-accessibility-focus.md)()<br>Focus the current reading position without changing review or selection. |
| [resumeLiveOutput](resume-live-output.md) | [release]<br>abstract fun [resumeLiveOutput](resume-live-output.md)() |
| [returnToInput](return-to-input.md) | [release]<br>abstract fun [returnToInput](return-to-input.md)() |
| [startReview](start-review.md) | [release]<br>abstract fun [startReview](start-review.md)() |