//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalEmulator](index.md)/[writeInput](write-input.md)

# writeInput

[release]\
abstract fun [writeInput](write-input.md)(data: [ByteArray](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-byte-array/index.html), offset: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 0, length: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = data.size - offset)

Write data to the terminal (from PTY/transport).

[release]\
abstract fun [writeInput](write-input.md)(buffer: [ByteBuffer](https://developer.android.com/reference/kotlin/java/nio/ByteBuffer.html), length: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html))

Write data to the terminal using ByteBuffer (more efficient for large data).