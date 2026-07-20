/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import java.io.InputStream
import java.io.OutputStream

/** Immutable chunks: decoding never needs to flatten or duplicate the encoded file. */
internal class ImageBytes(val chunks: List<ByteArray>, val size: Int) {
    fun input(): InputStream = object : InputStream() {
        var chunk = 0
        var offset = 0
        var remaining = size

        override fun read(): Int {
            if (remaining == 0) return -1
            val result = chunks[chunk][offset++].toInt() and 255
            remaining--
            if (offset == chunks[chunk].size) {
                chunk++
                offset = 0
            }
            return result
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            require(off >= 0 && len >= 0 && off <= b.size - len)
            if (len == 0) return 0
            if (remaining == 0) return -1
            val count = minOf(len, remaining, chunks[chunk].size - offset)
            chunks[chunk].copyInto(b, off, offset, offset + count)
            offset += count
            remaining -= count
            if (offset == chunks[chunk].size) {
                chunk++
                offset = 0
            }
            return count
        }
    }

    class Builder(private val limit: Int, private val reserve: (Int) -> Unit = {}) : OutputStream() {
        private val chunks = mutableListOf<ByteArray>()
        var size = 0
            private set

        override fun write(value: Int) {
            require(size < limit) { "E2BIG:image upload exceeds limit" }
            if (size % CHUNK == 0) allocate()
            chunks.last()[size % CHUNK] = value.toByte()
            size++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            require(off >= 0 && len >= 0 && off <= b.size - len)
            require(len <= limit - size) { "E2BIG:image upload exceeds limit" }
            var consumed = 0
            while (consumed < len) {
                if (size % CHUNK == 0) allocate()
                val count = minOf(len - consumed, chunks.last().size - size % CHUNK)
                b.copyInto(chunks.last(), size % CHUNK, off + consumed, off + consumed + count)
                consumed += count
                size += count
            }
        }

        fun build(): ImageBytes = ImageBytes(chunks.toList(), size)

        private fun allocate() {
            val bytes = minOf(CHUNK, limit - size)
            reserve(bytes)
            chunks.add(ByteArray(bytes))
        }
    }

    companion object {
        const val CHUNK = 4096
    }
}

/** Strict incremental base64, including padding split across transport fragments. */
internal class ImageBase64(private val output: OutputStream) {
    private var bits = 0
    private var count = 0
    private var padding = 0

    fun accept(value: Int) {
        if (value == '='.code) {
            require(count in 2..3 && padding < 2 && count + padding < 4) { "EINVAL:invalid base64 padding" }
            if (padding == 0) {
                require(if (count == 2) bits and 15 == 0 else bits and 3 == 0) { "EINVAL:invalid base64 tail" }
                if (count == 2) {
                    output.write(bits shr 4)
                } else {
                    output.write(bits shr 10)
                    output.write(bits shr 2)
                }
            }
            padding++
            return
        }
        require(padding == 0) { "EINVAL:data after base64 padding" }
        val decoded = when (value) {
            in 65..90 -> value - 65
            in 97..122 -> value - 71
            in 48..57 -> value + 4
            43 -> 62
            47 -> 63
            else -> throw IllegalArgumentException("EINVAL:invalid base64")
        }
        bits = (bits shl 6) or decoded
        count++
        if (count == 4) {
            output.write(bits shr 16)
            output.write(bits shr 8)
            output.write(bits)
            count = 0
            bits = 0
        }
    }

    fun finish() {
        require(count == 0 || (padding > 0 && count + padding == 4)) { "EINVAL:incomplete base64" }
    }
}
