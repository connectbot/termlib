//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[ComposeController](index.md)

# ComposeController

[release]\
interface [ComposeController](index.md)

Public API interface for controlling compose mode in the terminal.

Compose mode buffers typed text locally and displays it as an overlay at the cursor position. Enter commits the text; Escape cancels.

## Properties

| Name | Summary |
|---|---|
| [isComposeModeActive](is-compose-mode-active.md) | [release]<br>abstract val [isComposeModeActive](is-compose-mode-active.md): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)<br>Whether compose mode is currently active. |
| [pendingDeadChar](pending-dead-char.md) | [release]<br>open val [pendingDeadChar](pending-dead-char.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)<br>The current pending dead character (accent) waiting for a base character. 0 if no dead character is pending. |

## Functions

| Name | Summary |
|---|---|
| [getComposedText](get-composed-text.md) | [release]<br>abstract fun [getComposedText](get-composed-text.md)(): [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html)<br>Get the currently composed (buffered) text. |
| [startComposeMode](start-compose-mode.md) | [release]<br>abstract fun [startComposeMode](start-compose-mode.md)()<br>Start compose mode. Clears any active selection first. |
| [stopComposeMode](stop-compose-mode.md) | [release]<br>abstract fun [stopComposeMode](stop-compose-mode.md)()<br>Stop compose mode, discarding any buffered text. |
| [syncImeShortcutInputMode](sync-ime-shortcut-input-mode.md) | [release]<br>open fun [syncImeShortcutInputMode](sync-ime-shortcut-input-mode.md)(mode: [ImeShortcutInputMode](../-ime-shortcut-input-mode/index.md))<br>Synchronize the IME's temporary shortcut input mode after an on-screen terminal modifier changes state. |
| [toggleComposeMode](toggle-compose-mode.md) | [release]<br>abstract fun [toggleComposeMode](toggle-compose-mode.md)()<br>Toggle compose mode on/off. |