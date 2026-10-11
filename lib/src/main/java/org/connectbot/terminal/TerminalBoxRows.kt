/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/** Aliased row pixels have only opaque foreground and transparent holes. */
internal class TerminalBoxRows {
    private val renderer = TerminalBoxDrawing(cacheRows = false)
    private val entries = LinkedHashMap<Key, Row>(16, 0.75f, true)
    var retainedPixelBytes = 0
        private set
    private var width = Float.NaN
    private var height = Float.NaN
    private var size = Float.NaN
    private var period = 1L

    fun draw(target: Canvas, curves: TerminalBoxDrawing, cells: PackedCells, text: String, above: PackedCells?, below: PackedCells?, y: Float, cellWidth: Float, cellHeight: Float, color: Int, textSize: Float): Boolean {
        if (cellWidth < 1f) return false
        val top = round(y).toInt()
        val w = round(cells.size * cellWidth).toInt()
        val h = round(y + cellHeight).toInt() - top
        if (w !in 1..2048 || h !in 1..128) return false
        if (width != cellWidth || height != cellHeight || size != textSize) {
            entries.clear()
            retainedPixelBytes = 0
            width = cellWidth
            height = cellHeight
            size = textSize
            period = max(1, floor(cellWidth / 12f).toInt()).toLong() * 2
            for (count in 2..4) {
                val cadence = max(2, round(cellWidth / count).toInt()).toLong()
                var a = period
                var b = cadence
                while (b != 0L) {
                    val remainder = a % b
                    a = b
                    b = remainder
                }
                period = period / a * cadence
            }
        }
        val key = Key(text, above?.verticalDashSignature().orEmpty(), below?.verticalDashSignature().orEmpty(), h, color, Math.floorMod(top.toLong(), period))
        var row = entries[key]
        if (row == null) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
            val canvas = Canvas(bitmap)
            canvas.translate(0f, -top.toFloat())
            renderer.prepareDraw(canvas)
            renderer.beginBatch(canvas, color)
            val curved = ArrayList<Int>()
            val neighbors = ArrayList<Int>()
            for (col in cells.indices) {
                val cp = cells.boxCodePoint(col)
                val dashes = neighboringDashes(cells, col, above, below)
                if (cp in 0x256D..0x2573) {
                    curved.add(col)
                    neighbors.add(dashes)
                } else {
                    renderer.draw(canvas, cp.toChar(), col * cellWidth, y, cellWidth, cellHeight, color, textSize, dashes, clipPrepared = true)
                }
            }
            renderer.endBatch()
            row = Row(bitmap, curved.toIntArray(), neighbors.toIntArray())
            val bytes = bitmap.allocationByteCount
            val old = entries.entries.iterator()
            while ((retainedPixelBytes + bytes > MAX_PIXEL_BYTES || entries.size >= 256) && old.hasNext()) {
                retainedPixelBytes -= old.next().value.bitmap.allocationByteCount
                old.remove()
            }
            entries[key] = row
            retainedPixelBytes += bytes
        }
        target.drawBitmap(row.bitmap, 0f, top.toFloat(), null)
        for (index in row.curves.indices) {
            val col = row.curves[index]
            curves.draw(target, cells.boxCodePoint(col).toChar(), col * cellWidth, y, cellWidth, cellHeight, color, textSize, row.neighbors[index], clipPrepared = true)
        }
        return true
    }

    private data class Key(val text: String, val above: String, val below: String, val height: Int, val color: Int, val phase: Long)
    private class Row(val bitmap: Bitmap, val curves: IntArray, val neighbors: IntArray)

    companion object {
        const val MAX_PIXEL_BYTES = 8 * 1024 * 1024

        fun neighboringDashes(cells: PackedCells, col: Int, above: PackedCells?, below: PackedCells?): Int {
            fun kind(cp: Int, horizontal: Boolean): Int = when (cp) {
                in if (horizontal) 0x254C..0x254D else 0x254E..0x254F -> 1
                in if (horizontal) 0x2504..0x2505 else 0x2506..0x2507 -> 2
                in if (horizontal) 0x2508..0x2509 else 0x250A..0x250B -> 3
                else -> 0
            }
            return kind(cells.boxCodePoint(col - 1), true) or
                (kind(cells.boxCodePoint(col + 1), true) shl 2) or
                (kind(above?.boxCodePoint(col) ?: 0, false) shl 4) or
                (kind(below?.boxCodePoint(col) ?: 0, false) shl 6)
        }
    }
}
