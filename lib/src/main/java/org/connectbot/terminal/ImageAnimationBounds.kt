/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import java.io.InputStream

/** Count encoded frames before asking a platform decoder to allocate animation state. */
internal object ImageAnimationBounds {
    fun count(bytes: ImageBytes, mime: String?, limit: Int): Int = bytes.input().use { input ->
        var count = 0
        when (mime) {
            "image/gif" -> {
                input.skipExactly(10)
                val flags = input.byte()
                input.skipExactly(2)
                if (flags and 128 != 0) input.skipExactly(3 * (2 shl (flags and 7)))
                while (true) {
                    when (input.byte()) {
                        0x3B -> break

                        0x21 -> {
                            input.byte()
                            input.blocks()
                        }

                        0x2C -> {
                            input.skipExactly(8)
                            val local = input.byte()
                            if (local and 128 != 0) input.skipExactly(3 * (2 shl (local and 7)))
                            input.byte()
                            input.blocks()
                            count++
                            require(count <= limit) { "ENOSPC:too many animation frames" }
                        }

                        else -> throw IllegalArgumentException("EINVAL:invalid GIF block")
                    }
                }
            }

            "image/webp" -> {
                input.skipExactly(12)
                var consumed = 12L
                while (consumed + 8 <= bytes.size) {
                    val tag = ByteArray(4) { input.byte().toByte() }.toString(Charsets.US_ASCII)
                    var size = 0L
                    repeat(4) { size = size or (input.byte().toLong() shl (it * 8)) }
                    consumed += 8 + size + (size and 1)
                    require(consumed <= bytes.size) { "EINVAL:invalid WebP chunk" }
                    input.skipExactly((size + (size and 1)).toInt())
                    if (tag == "ANMF") count++
                    require(count <= limit) { "ENOSPC:too many animation frames" }
                }
            }
        }
        count.coerceAtLeast(1)
    }

    private fun InputStream.byte(): Int = read().also { require(it >= 0) { "EINVAL:truncated animation" } }
    private fun InputStream.skipExactly(count: Int) {
        repeat(count) { byte() }
    }
    private fun InputStream.blocks() {
        while (true) {
            val size = byte()
            if (size == 0) return
            skipExactly(size)
        }
    }
}
