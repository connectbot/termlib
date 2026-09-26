//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[ModifierManager](index.md)/[clearTransients](clear-transients.md)

# clearTransients

[release]\
abstract fun [clearTransients](clear-transients.md)()

Clear transient modifiers after a key press.

This should be called by KeyboardHandler after each key is dispatched to the terminal. Transient modifiers are one-shot and clear automatically, while locked modifiers persist.