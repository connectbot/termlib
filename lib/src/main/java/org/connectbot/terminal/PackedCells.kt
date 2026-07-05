/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.nio.ByteBuffer

/** Immutable column-addressed storage. Arrays never escape to mutable consumers. */
internal class PackedCells private constructor(
    private val text: CharArray,
    private val offsets: IntArray,
    private val colors: IntArray,
    private val attributes: IntArray,
) : AbstractList<TerminalLine.Cell>() {
    override val size: Int get() = attributes.size
    fun width(col: Int): Int = attributes[col] ushr 24
    fun flags(col: Int): Int = attributes[col] and 0xFFFFFF
    fun foreground(col: Int): Color = Color(colors[col * 2])
    fun background(col: Int): Color = Color(colors[col * 2 + 1])
    fun charAt(col: Int): Char = if (width(col) == 0 && col > 0) charAt(col - 1) else text.getOrElse(offsets[col]) { '\u0000' }
    fun blank(col: Int): Boolean = width(col) != 0 && offsets[col + 1] - offsets[col] <= 1 && charAt(col).let { it == ' ' || it == '\u0000' }
    fun text(start: Int = 0, end: Int = size): String {
        require(start in 0..end && end <= size)
        if (start == end) return ""
        val first = if (start < size && width(start) == 0 && start > 0) start - 1 else start
        return String(text, offsets[first], offsets[end] - offsets[first])
    }

    fun columnText(): String = String(CharArray(size) { charAt(it) })

    fun draw(canvas: android.graphics.Canvas, col: Int, x: Float, baseline: Float, paint: android.graphics.Paint) {
        canvas.drawText(text, offsets[col], offsets[col + 1] - offsets[col], x, baseline, paint)
    }

    // Compatibility for test fixtures and cold callers; rendering and snapshot construction
    // use primitive accessors, never this allocating List view.
    override fun get(index: Int): TerminalLine.Cell {
        if (index !in indices) throw IndexOutOfBoundsException("Column $index outside 0 until $size")
        val value = text(index, index + 1)
        val flags = flags(index)
        return TerminalLine.Cell(
            char = value.firstOrNull() ?: '\u0000', combiningChars = value.drop(1).toList(),
            fgColor = foreground(index), bgColor = background(index),
            bold = flags and 1 != 0, underline = (flags ushr 1) and 3,
            italic = flags and 8 != 0, blink = flags and 16 != 0,
            reverse = flags and 32 != 0, strike = flags and 128 != 0, width = width(index),
        )
    }

    fun contentEquals(other: PackedCells): Boolean = this === other ||
        (
            text.contentEquals(other.text) && offsets.contentEquals(other.offsets) &&
                colors.contentEquals(other.colors) && attributes.contentEquals(other.attributes)
            )

    override fun equals(other: Any?): Boolean = if (other is PackedCells) contentEquals(other) else super.equals(other)
    override fun hashCode(): Int = super.hashCode()

    fun matches(buffer: ByteBuffer, offset: Int, start: Int, count: Int): Boolean {
        if (start < 0 || count > size - start) return false
        for (i in 0 until count) {
            val col = start + i
            val base = offset + i * CellData.BYTES
            if (width(col) != buffer.getInt(base + 24) || flags(col) != buffer.getInt(base + 36)) return false
            // Continuation cells carry no independently rendered content or attributes.
            if (width(col) == 0) continue
            if (colors[col * 2] != buffer.getInt(base + 28) or (0xFF shl 24) ||
                colors[col * 2 + 1] != buffer.getInt(base + 32) or (0xFF shl 24)
            ) {
                return false
            }
            var index = offsets[col]
            for (slot in 0 until CellData.CODE_POINTS) {
                val raw = buffer.getInt(base + slot * 4)
                if (slot > 0 && raw == 0) break
                val cp = if (raw == 0) {
                    32
                } else if (CellData.isScalar(raw)) {
                    raw
                } else {
                    0xFFFD
                }
                if (index >= offsets[col + 1] || Character.codePointAt(text, index, offsets[col + 1]) != cp) return false
                index += Character.charCount(cp)
            }
            if (index != offsets[col + 1]) return false
        }
        return true
    }

    fun writeRecord(buffer: ByteBuffer, offset: Int, col: Int) {
        for (i in 0 until CellData.STRIDE) buffer.putInt(offset + i * 4, 0)
        var index = offsets[col]
        var slot = 0
        while (index < offsets[col + 1] && slot < CellData.CODE_POINTS) {
            val cp = Character.codePointAt(text, index, offsets[col + 1])
            buffer.putInt(offset + slot++ * 4, if (CellData.isScalar(cp)) cp else 0xFFFD)
            index += Character.charCount(cp)
        }
        buffer.putInt(offset + 24, width(col))
        buffer.putInt(offset + 28, colors[col * 2])
        buffer.putInt(offset + 32, colors[col * 2 + 1])
        buffer.putInt(offset + 36, flags(col))
    }

    class Builder(private val columns: Int) {
        private var text = CharArray(columns)
        private var used = 0
        private val offsets = IntArray(columns + 1)
        private val colors = IntArray(columns * 2)
        private val attributes = IntArray(columns)
        private var column = 0

        private fun append(char: Char) {
            if (used == text.size) text = text.copyOf(maxOf(16, used * 2))
            text[used++] = char
        }

        fun copy(source: PackedCells, start: Int, end: Int) {
            for (col in start until end) {
                offsets[column] = used
                for (i in source.offsets[col] until source.offsets[col + 1]) append(source.text[i])
                colors[column * 2] = source.colors[col * 2]
                colors[column * 2 + 1] = source.colors[col * 2 + 1]
                attributes[column++] = source.attributes[col]
                offsets[column] = used
            }
        }

        fun read(buffer: ByteBuffer, offset: Int, count: Int) {
            require(count >= 0 && count <= columns - column)
            for (i in 0 until count) {
                val base = offset + i * CellData.BYTES
                val width = buffer.getInt(base + 24)
                require(width in 0..2 && width <= columns - column)
                offsets[column] = used
                if (width != 0) {
                    for (slot in 0 until CellData.CODE_POINTS) {
                        val raw = buffer.getInt(base + slot * 4)
                        if (slot > 0 && raw == 0) break
                        val cp = if (raw == 0) {
                            32
                        } else if (CellData.isScalar(raw)) {
                            raw
                        } else {
                            0xFFFD
                        }
                        if (cp <= 0xFFFF) {
                            append(cp.toChar())
                        } else {
                            append(Character.highSurrogate(cp))
                            append(Character.lowSurrogate(cp))
                        }
                    }
                }
                colors[column * 2] = buffer.getInt(base + 28) or (0xFF shl 24)
                colors[column * 2 + 1] = buffer.getInt(base + 32) or (0xFF shl 24)
                attributes[column++] = (width shl 24) or buffer.getInt(base + 36)
                offsets[column] = used
            }
        }

        fun build(): PackedCells {
            check(column == columns)
            return PackedCells(if (used == text.size) text else text.copyOf(used), offsets, colors, attributes)
        }
    }

    companion object {
        fun from(cells: List<TerminalLine.Cell>): PackedCells {
            if (cells is PackedCells) return cells
            val columns = cells.sumOf { it.width.coerceIn(1, 2) }
            val text = StringBuilder(columns)
            val offsets = IntArray(columns + 1)
            val colors = IntArray(columns * 2)
            val attrs = IntArray(columns)
            var col = 0
            for (cell in cells) {
                val width = cell.width.coerceIn(1, 2)
                offsets[col] = text.length
                text.append(cell.char)
                cell.combiningChars.forEach { text.append(it) }
                colors[col * 2] = cell.fgColor.toArgb()
                colors[col * 2 + 1] = cell.bgColor.toArgb()
                attrs[col] = (width shl 24) or (if (cell.bold) 1 else 0) or (cell.underline shl 1) or
                    (if (cell.italic) 8 else 0) or (if (cell.blink) 16 else 0) or
                    (if (cell.reverse) 32 else 0) or (if (cell.strike) 128 else 0)
                offsets[++col] = text.length
                if (width == 2) offsets[++col] = text.length
            }
            return PackedCells(text.toString().toCharArray(), offsets, colors, attrs)
        }

        fun empty(columns: Int, fg: Color, bg: Color): PackedCells = PackedCells(
            CharArray(columns),
            IntArray(columns + 1) { it },
            IntArray(columns * 2) { if (it % 2 == 0) fg.toArgb() else bg.toArgb() },
            IntArray(columns) { 1 shl 24 },
        )
    }
}
