/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageDimensionsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun le(value: Int, count: Int) = ByteArray(count) { (value ushr (8 * it)).toByte() }
    private fun be(value: Int, count: Int) = le(value, count).reversedArray()
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
    private fun read(data: ByteArray, limits: InlineImageLimits = InlineImageLimits()): ImageDimensions {
        // Split every byte into a chunk to exercise stream boundaries too.
        return ImageDimensions.read(ImageBytes(data.map { byteArrayOf(it) }, data.size), limits)
    }
    private fun png(w: Int, h: Int) = bytes(137, 80, 78, 71, 13, 10, 26, 10) + be(13, 4) + ascii("IHDR") + be(w, 4) + be(h, 4) + bytes(8, 6, 0, 0, 0, 0, 0, 0, 0)
    private fun gif(w: Int, h: Int) = ascii("GIF89a") + le(w, 2) + le(h, 2) + bytes(0, 0, 0)
    private fun webp(tag: String, payload: ByteArray): ByteArray {
        val padded = payload + if (payload.size % 2 == 1) bytes(0) else byteArrayOf()
        return ascii("RIFF") + le(12 + padded.size, 4) + ascii("WEBP$tag") + le(payload.size, 4) + padded
    }

    @Test
    fun readsPngAndGifWithoutPixels() {
        assertEquals(ImageDimensions(398, 558, "image/png"), read(png(398, 558)))
        assertEquals(ImageDimensions(398, 558, "image/gif"), read(gif(398, 558)))
        assertEquals(ImageDimensions(398, 558, "image/gif"), read(gif(398, 558).apply { this[4] = '7'.code.toByte() }))
    }

    @Test
    fun skipsJpegMetadataAndReadsBaselineAndProgressiveFrames() {
        for (marker in listOf(0xc0, 0xc2)) {
            val app = bytes(0xff, 0xe1) + be(6, 2) + bytes(0xff, 0xd8, 0xff, 0xc0)
            val frame = bytes(0xff, marker) + be(11, 2) + bytes(8) + be(558, 2) + be(398, 2) + bytes(1, 1, 0x11, 0)
            assertEquals(ImageDimensions(398, 558, "image/jpeg"), read(bytes(0xff, 0xd8) + app + frame))
        }
    }

    @Test
    fun readsAllWebpDimensionHeaders() {
        val width = 398
        val height = 558
        assertEquals(
            ImageDimensions(width, height, "image/webp"),
            read(webp("VP8X", bytes(0, 0, 0, 0) + le(width - 1, 3) + le(height - 1, 3))),
        )
        assertEquals(
            ImageDimensions(width, height, "image/webp"),
            read(webp("VP8L", bytes(0x2f) + le((width - 1) or ((height - 1) shl 14), 4))),
        )
        assertEquals(
            ImageDimensions(width, height, "image/webp"),
            read(webp("VP8 ", bytes(0, 0, 0, 0x9d, 1, 0x2a) + le(width, 2) + le(height, 2))),
        )
    }

    @Test
    fun rejectsTruncationAtEveryHeaderBoundary() {
        val headers = listOf(png(398, 558), gif(398, 558), webp("VP8X", bytes(0, 0, 0, 0) + le(397, 3) + le(557, 3)))
        for (header in headers) {
            for (length in header.indices) {
                assertThrows("length=$length", IllegalArgumentException::class.java) { read(header.copyOf(length)) }
            }
        }
    }

    @Test
    fun rejectsInvalidDimensionsAndContainers() {
        for (header in listOf(png(0, 1), png(-1, 1), png(16385, 1), png(10000, 10000), gif(1, 0))) {
            assertThrows(IllegalArgumentException::class.java) { read(header) }
        }
        assertThrows(IllegalArgumentException::class.java) { read(png(20, 20), InlineImageLimits(maxPixels = 399)) }
        assertThrows(IllegalArgumentException::class.java) { read(ascii("not an image")) }
        assertThrows(IllegalArgumentException::class.java) { read(bytes(0xff, 0xd8, 0xff, 0xe1, 0, 1)) }
        assertThrows(IllegalArgumentException::class.java) { read(bytes(0xff, 0xd8, 0xff, 0xda)) }
        assertThrows(IllegalArgumentException::class.java) { read(webp("VP8L", bytes(0x2f, 0, 0, 0, 0xe0))) }
        assertThrows(IllegalArgumentException::class.java) { read(webp("VP8X", ByteArray(10)).apply { this[16] = 0xff.toByte() }) }
    }

    @Test
    fun boundsJpegHeaderSearch() {
        val frame = bytes(0xff, 0xc0) + be(11, 2) + bytes(8) + be(558, 2) + be(398, 2) + bytes(1, 1, 0x11, 0)
        fun jpeg(headerSize: Int): ByteArray {
            val padding = headerSize - 2 - 4 - frame.size
            return bytes(0xff, 0xd8, 0xff, 0xe1) + be(padding + 2, 2) + ByteArray(padding) + frame
        }
        assertEquals(ImageDimensions(398, 558, "image/jpeg"), read(jpeg(32 * 1024)))
        val error = assertThrows(IllegalArgumentException::class.java) { read(jpeg(32 * 1024 + 1)) }
        assertEquals("E2BIG:image header exceeds inspection limit", error.message)
    }
}
