//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalEmulator](index.md)/[close](close.md)

# close

[release]\
abstract override fun [close](close.md)()

Permanently dispose this session and release its native, screen, and image state. Closing is synchronous and idempotent, and does not initialize an unused terminal. Stop session I/O and detach its UI before closing. A temporary UI detachment does not end a session. Mutations and content queries throw [IllegalStateException](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-illegal-state-exception/index.html) after close; scalar configuration and dimensions remain readable.