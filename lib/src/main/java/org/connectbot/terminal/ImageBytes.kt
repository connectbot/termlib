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
    private var decodedBuffer: ByteArray? = null

    fun accept(data: ByteArray, offset: Int) {
        var index = offset
        while (index < data.size && count != 0) accept(data[index++].toInt() and 255)

        if (index <= data.size - 4) {
            val decoded = decodedBuffer ?: ByteArray(3072).also { decodedBuffer = it }
            var written = 0
            while (index <= data.size - 4) {
                val a = VALUES[data[index].toInt() and 255]
                val b = VALUES[data[index + 1].toInt() and 255]
                val c = VALUES[data[index + 2].toInt() and 255]
                val d = VALUES[data[index + 3].toInt() and 255]
                // Let the scalar path handle padding, malformed input and split quartets.
                if ((a or b or c or d) < 0) break
                decoded[written++] = ((a shl 2) or (b ushr 4)).toByte()
                decoded[written++] = ((b shl 4) or (c ushr 2)).toByte()
                decoded[written++] = ((c shl 6) or d).toByte()
                index += 4
                if (written == decoded.size) {
                    output.write(decoded, 0, written)
                    written = 0
                }
            }
            if (written != 0) output.write(decoded, 0, written)
        }
        while (index < data.size) accept(data[index++].toInt() and 255)
    }

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
        val decoded = VALUES.getOrElse(value) { -1 }
        require(decoded >= 0) {
            "EINVAL:invalid base64 byte 0x${value.toString(16).padStart(2, '0')} " +
                "at quartet offset $count after $padding padding bytes"
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
        if (padding > 0) {
            require(count + padding == 4) {
                "EINVAL:incomplete base64 quartet ($count data, $padding padding)"
            }
        } else {
            when (count) {
                0 -> Unit

                2 -> {
                    require(bits and 15 == 0) { "EINVAL:invalid base64 tail" }
                    output.write(bits shr 4)
                }

                3 -> {
                    require(bits and 3 == 0) { "EINVAL:invalid base64 tail" }
                    output.write(bits shr 10)
                    output.write(bits shr 2)
                }

                else -> throw IllegalArgumentException("EINVAL:incomplete base64 quartet ($count data, 0 padding)")
            }
        }
        count = 0
        bits = 0
        padding = 0
    }

    private companion object {
        val VALUES = IntArray(256) { -1 }.also { values ->
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { index, char ->
                values[char.code] = index
            }
        }
    }
}
