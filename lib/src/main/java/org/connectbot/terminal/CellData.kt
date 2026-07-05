/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit native-endian wire format: 6 code points, width, RGB colors, packed flags. */
internal object CellData {
    const val CODE_POINTS = 6
    const val STRIDE = 10
    const val BYTES = STRIDE * 4
    const val BUFFER_BYTES = 64 * 1024
    const val HEADER_BYTES = 4096
    const val MAX_REQUESTS = HEADER_BYTES / 16
    fun buffer(): ByteBuffer = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder())

    fun read(data: ByteBuffer, columns: Int): PackedCells = PackedCells.Builder(columns).apply { read(data, 0, columns) }.build()

    fun writeRange(data: ByteBuffer, start: Int, count: Int, cells: PackedCells, foreground: Color, background: Color) {
        require(count >= 0 && count <= data.capacity() / BYTES)
        for (i in 0 until count) {
            val col = start + i
            if (col < cells.size) {
                cells.writeRecord(data, i * BYTES, col)
            } else {
                for (slot in 0 until STRIDE) data.putInt(i * BYTES + slot * 4, 0)
                data.putInt(i * BYTES, 32)
                data.putInt(i * BYTES + 24, 1)
                data.putInt(i * BYTES + 28, foreground.toArgb())
                data.putInt(i * BYTES + 32, background.toArgb())
            }
        }
    }

    fun isScalar(cp: Int): Boolean = cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF
}
