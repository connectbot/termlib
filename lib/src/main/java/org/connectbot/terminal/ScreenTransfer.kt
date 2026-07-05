/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

/** Fixed-capacity JNI scratch. The owning emulator serializes all use. */
internal class ScreenTransfer {
    private val buffer = CellData.buffer()
    var calls = 0L
        private set
    var bytes = 0L
        private set

    fun fetch(native: TerminalNative, ranges: IntArray, consume: (Int, Int, Int, java.nio.ByteBuffer, Int, Boolean) -> Unit) {
        var row = 0
        var col = ranges.getOrElse(0) { 0 }
        while (row < ranges.size / 2) {
            var requests = 0
            var records = 0
            val capacity = (CellData.BUFFER_BYTES - CellData.HEADER_BYTES) / CellData.BYTES
            while (row < ranges.size / 2 && requests < CellData.MAX_REQUESTS && records < capacity) {
                val end = ranges[row * 2 + 1]
                if (col >= end) {
                    row++
                    col = ranges.getOrElse(row * 2) { 0 }
                    continue
                }
                val count = minOf(end - col, capacity - records)
                val header = requests++ * 16
                buffer.putInt(header, row)
                buffer.putInt(header + 4, col)
                buffer.putInt(header + 8, count)
                records += count
                col += count
            }
            if (requests == 0) continue
            check(native.getCells(buffer, requests) == records) { "Incomplete cell transfer" }
            calls++
            bytes += records * CellData.BYTES + requests * 16L
            var offset = CellData.HEADER_BYTES
            for (request in 0 until requests) {
                val header = request * 16
                val count = buffer.getInt(header + 8)
                consume(buffer.getInt(header), buffer.getInt(header + 4), count, buffer, offset, buffer.getInt(header + 12) != 0)
                offset += count * CellData.BYTES
            }
        }
    }
}
