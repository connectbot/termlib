//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[ModifierManager](index.md)

# ModifierManager

interface [ModifierManager](index.md)

Manages modifier key state for terminal keyboard input. This can be used to combine some external state about the Ctrl, Alt, and Shift keys. For instance, combining a keyboard with an on-screen button.

#### See also

| |
|---|
| [TerminalEmulatorFactory.create](../-terminal-emulator-factory/-companion/create.md) |

## Functions

| Name | Summary |
|---|---|
| [clearTransients](clear-transients.md) | [release]<br>abstract fun [clearTransients](clear-transients.md)()<br>Clear transient modifiers after a key press. |
| [isAltActive](is-alt-active.md) | [release]<br>abstract fun [isAltActive](is-alt-active.md)(): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)<br>Check if Alt modifier is active (transient or locked). |
| [isCtrlActive](is-ctrl-active.md) | [release]<br>abstract fun [isCtrlActive](is-ctrl-active.md)(): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)<br>Check if Ctrl modifier is active (transient or locked). |
| [isShiftActive](is-shift-active.md) | [release]<br>abstract fun [isShiftActive](is-shift-active.md)(): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)<br>Check if Shift modifier is active (transient or locked). |