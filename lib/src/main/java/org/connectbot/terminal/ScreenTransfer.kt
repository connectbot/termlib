/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

/** Fixed-capacity JNI scratch. The owning emulator serializes all use. */
internal class ScreenTransfer {
    private var buffer: java.nio.ByteBuffer? = CellData.buffer()
    private var words = buffer?.asIntBuffer()
    private var recordsCopy: IntArray? = null
    var calls = 0L
        private set
    var bytes = 0L
        private set

    fun close() {
        buffer = null
        words = null
        recordsCopy = null
    }

    fun fetch(native: TerminalNative, ranges: IntArray, consume: (Int, Int, Int, IntArray, Int, Boolean) -> Unit) {
        val buffer = checkNotNull(buffer)
        var row = 0
        var col = ranges.getOrElse(0) { 0 }
        while (row < ranges.size / 2) {
            var requests = 0
            var records = 0
            val capacity = (CellData.BUFFER_BYTES - CellData.HEADER_BYTES) / (CellData.SCREEN_WORDS * 4)
            var requestRow = row
            var requestCol = col
            while (requestRow < ranges.size / 2 && requests < CellData.MAX_REQUESTS && records < capacity) {
                val end = ranges[requestRow * 2 + 1]
                if (requestCol >= end) {
                    requestRow++
                    requestCol = ranges.getOrElse(requestRow * 2) { 0 }
                    continue
                }
                val count = minOf(end - requestCol, capacity - records)
                val header = requests++ * 16
                buffer.putInt(header, requestRow)
                buffer.putInt(header + 4, requestCol)
                buffer.putInt(header + 8, count)
                records += count
                requestCol += count
            }
            if (requests == 0) return
            val tracing = android.os.Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled()
            if (tracing) android.os.Trace.beginSection("terminal.snapshot.fetch")
            val length = try {
                native.getCells(buffer, requests, compact = true)
            } finally {
                if (tracing) android.os.Trace.endSection()
            }
            check(length in CellData.SCREEN_WORDS..(CellData.BUFFER_BYTES - CellData.HEADER_BYTES) / 4) {
                "Invalid screen transfer length"
            }
            calls++
            bytes += length * 4L + requests * 16L
            val copy = recordsCopy?.takeIf { it.size >= length } ?: IntArray(length).also { recordsCopy = it }
            checkNotNull(words).apply {
                position(CellData.HEADER_BYTES / 4)
                get(copy, 0, length)
            }
            var wordOffset = 0
            for (request in 0 until requests) {
                val header = request * 16
                val count = buffer.getInt(header + 8)
                if (count == 0) break
                row = buffer.getInt(header)
                val start = buffer.getInt(header + 4)
                val metadata = buffer.getInt(header + 12)
                consume(row, start, count, copy, wordOffset, metadata and 1 != 0)
                col = start + count
                wordOffset += metadata ushr 1
            }
            check(wordOffset == length) { "Incomplete screen transfer" }
        }
    }
}
