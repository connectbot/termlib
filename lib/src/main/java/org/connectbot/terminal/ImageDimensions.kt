/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import java.io.InputStream

/** Reads dimensions without invoking a pixel decoder or allocating from file dimensions. */
internal data class ImageDimensions(val width: Int, val height: Int, val mime: String) {
    companion object {
        // JPEG metadata can precede SOF; bound the search independently of upload size.
        private const val HEADER_LIMIT = 32 * 1024

        fun read(bytes: ImageBytes, limits: InlineImageLimits): ImageDimensions = bytes.input().use { input ->
            val reader = Header(input, minOf(bytes.size, HEADER_LIMIT))
            val first = reader.byte()
            val second = reader.byte()
            val dimensions = when {
                first == 0x89 && second == 'P'.code -> {
                    require(reader.text(6) == "NG\r\n\u001a\n") { "EINVAL:invalid PNG signature" }
                    require(reader.uint(4, false) == 13L && reader.text(4) == "IHDR") { "EINVAL:missing PNG IHDR" }
                    val width = reader.uint(4, false)
                    val height = reader.uint(4, false)
                    require(width <= Int.MAX_VALUE && height <= Int.MAX_VALUE) { "E2BIG:invalid PNG dimensions" }
                    reader.skip(9) // Remaining IHDR fields and CRC; no pixel data.
                    ImageDimensions(width.toInt(), height.toInt(), "image/png")
                }

                first == 'G'.code && second == 'I'.code -> {
                    require(reader.text(4) in listOf("F87a", "F89a")) { "EINVAL:invalid GIF signature" }
                    val width = reader.uint(2, true).toInt()
                    val height = reader.uint(2, true).toInt()
                    reader.skip(3) // Remaining logical-screen descriptor.
                    ImageDimensions(width, height, "image/gif")
                }

                first == 0xff && second == 0xd8 -> jpeg(reader)

                first == 'R'.code && second == 'I'.code -> webp(reader, bytes.size)

                else -> throw IllegalArgumentException("ENOTSUP:unsupported image file")
            }
            require(
                dimensions.width in 1..limits.maxDimension && dimensions.height in 1..limits.maxDimension &&
                    dimensions.width.toLong() * dimensions.height <= limits.maxPixels,
            ) { "E2BIG:invalid image dimensions" }
            dimensions
        }

        private fun jpeg(reader: Header): ImageDimensions {
            while (true) {
                require(reader.byte() == 0xff) { "EINVAL:invalid JPEG marker" }
                var marker = reader.byte()
                while (marker == 0xff) marker = reader.byte()
                require(marker != 0 && marker != 0xda && marker != 0xd9) { "EINVAL:missing JPEG dimensions" }
                if (marker == 0x01 || marker in 0xd0..0xd8) continue
                val length = reader.uint(2, false).toInt()
                require(length >= 2) { "EINVAL:invalid JPEG segment" }
                if (marker in 0xc0..0xcf && marker !in listOf(0xc4, 0xc8, 0xcc)) {
                    require(length >= 8) { "EINVAL:short JPEG frame header" }
                    reader.byte()
                    val height = reader.uint(2, false).toInt()
                    val width = reader.uint(2, false).toInt()
                    val components = reader.byte()
                    require(components in 1..4 && length == 8 + components * 3) { "EINVAL:invalid JPEG frame header" }
                    reader.skip(components * 3)
                    return ImageDimensions(width, height, "image/jpeg")
                }
                reader.skip(length - 2)
            }
        }

        private fun webp(reader: Header, size: Int): ImageDimensions {
            require(reader.text(2) == "FF") { "EINVAL:invalid WebP signature" }
            val length = reader.uint(4, true)
            require(length >= 12 && length + 8 <= size && reader.text(4) == "WEBP") { "EINVAL:invalid WebP container" }
            val tag = reader.text(4)
            val chunkLength = reader.uint(4, true)
            require(chunkLength + (chunkLength and 1) + 12 <= length) { "EINVAL:invalid WebP chunk" }
            return when (tag) {
                "VP8X" -> {
                    require(chunkLength == 10L) { "EINVAL:invalid WebP extended header" }
                    reader.skip(4)
                    ImageDimensions(reader.uint(3, true).toInt() + 1, reader.uint(3, true).toInt() + 1, "image/webp")
                }

                "VP8L" -> {
                    require(chunkLength >= 5 && reader.byte() == 0x2f) { "EINVAL:invalid WebP lossless header" }
                    val bits = reader.uint(4, true)
                    require(bits ushr 29 == 0L) { "ENOTSUP:unsupported WebP version" }
                    ImageDimensions((bits and 0x3fff).toInt() + 1, (bits ushr 14 and 0x3fff).toInt() + 1, "image/webp")
                }

                "VP8 " -> {
                    require(chunkLength >= 10 && reader.byte() and 1 == 0) { "EINVAL:invalid WebP key frame" }
                    reader.skip(2)
                    require(reader.byte() == 0x9d && reader.byte() == 0x01 && reader.byte() == 0x2a) { "EINVAL:invalid VP8 signature" }
                    ImageDimensions(reader.uint(2, true).toInt() and 0x3fff, reader.uint(2, true).toInt() and 0x3fff, "image/webp")
                }

                else -> throw IllegalArgumentException("ENOTSUP:unsupported WebP header")
            }
        }
    }

    private class Header(private val input: InputStream, private var remaining: Int) {
        fun byte(): Int {
            require(remaining > 0) { "E2BIG:image header exceeds available bytes or inspection limit" }
            remaining--
            return input.read().also { require(it >= 0) { "EINVAL:truncated image header" } }
        }
        fun uint(count: Int, little: Boolean): Long {
            var result = 0L
            repeat(count) { index -> result = result or (byte().toLong() shl (8 * if (little) index else count - index - 1)) }
            return result
        }
        fun text(count: Int): String = String(CharArray(count) { byte().toChar() })
        fun skip(count: Int) {
            require(count in 0..remaining) { "E2BIG:image header exceeds inspection limit" }
            repeat(count) { byte() }
        }
    }
}
