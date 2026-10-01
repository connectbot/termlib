//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalEmulator](index.md)/[dispatchCharacter](dispatch-character.md)

# dispatchCharacter

[release]\
abstract fun [dispatchCharacter](dispatch-character.md)(modifiers: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), codepoint: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html))

Dispatch a character to the terminal.

[release]\
open fun [~~dispatchCharacter~~](dispatch-character.md)(modifiers: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), ch: [Char](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-char/index.html))

---

### Deprecated

Use dispatchCharacter(modifiers, codepoint) for full Unicode code point support

#### Replace with

```kotlin
dispatchCharacter(modifiers, ch.code)
```
---

Dispatch a character to the terminal.