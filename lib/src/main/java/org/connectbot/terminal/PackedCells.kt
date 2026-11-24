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
    private val placeholderIds: Map<Int, Int> = emptyMap(),
) : AbstractList<TerminalLine.Cell>() {
    override val size: Int get() = attributes.size

    fun needsShaping(): Boolean {
        if (shapingClass == 0) {
            var complex = false
            var i = 0
            while (i < text.size) {
                val cp = Character.codePointAt(text, i, text.size)
                if (cp < 128) {
                    i++
                    continue
                }
                val direction = Character.getDirectionality(cp)
                if (TerminalShaping.script(cp) != 0 ||
                    direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                    direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                ) {
                    complex = true
                    break
                }
                i += Character.charCount(cp)
            }
            shapingClass = if (complex) -1 else 1
        }
        return shapingClass < 0
    }

    fun sameShaping(other: PackedCells, allowAsciiChanges: Boolean = true): Boolean {
        if (size != other.size || !offsets.contentEquals(other.offsets)) return false
        if (!text.contentEquals(other.text)) {
            if (!allowAsciiChanges) return false
            for (col in indices) {
                val start = offsets[col]
                val end = offsets[col + 1]
                // Single-cell ASCII is always a shaping boundary and is drawn by
                // the legacy renderer. Updating a counter beside Arabic must not
                // allocate an entirely new set of native glyph/font objects.
                if (end == start + 1 && text[start] < '\u0080' && other.text[start] < '\u0080') continue
                for (index in start until end) if (text[index] != other.text[index]) return false
            }
        }
        for (col in indices) if ((attributes[col] xor other.attributes[col]) and 0xFF000009.toInt() != 0) return false
        return true
    }

    // Account for the packed row retained by a cache entry, including array headers.
    val shapingRetentionBytes: Int get() = 128 + text.size * 2 + offsets.size * 4 + colors.size * 4 + attributes.size * 4

    @androidx.annotation.RequiresApi(31)
    fun shape(
        shaper: TerminalShaping,
        logicalToVisual: IntArray? = null,
        visualToLogical: IntArray? = null,
        levels: ByteArray? = null,
        mirrors: IntArray? = null,
        contextBefore: String = "",
        contextAfter: String = "",
    ): ShapedLine {
        if (contextBefore.isEmpty() && contextAfter.isEmpty()) {
            return shaper.shape(text, offsets, this, logicalToVisual, visualToLogical, levels, mirrors)
        }
        val before = contextBefore.toCharArray()
        val after = contextAfter.toCharArray()
        val contextual = CharArray(before.size + text.size + after.size)
        before.copyInto(contextual)
        text.copyInto(contextual, before.size)
        after.copyInto(contextual, before.size + text.size)
        val shiftedOffsets = IntArray(offsets.size) { offsets[it] + before.size }
        return shaper.shape(contextual, shiftedOffsets, this, logicalToVisual, visualToLogical, levels, mirrors)
    }
    fun width(col: Int): Int = attributes[col] ushr 24
    fun placeholder(col: Int): Boolean = offsets[col + 1] - offsets[col] >= 2 && Character.codePointAt(text, offsets[col], offsets[col + 1]) == 0x10EEEE
    fun placeholderPlacement(col: Int): Long = (placeholderIds[col] ?: 0).toLong() and 0xFFFFFF
    fun placeholderImage(col: Int): Long = colors[col * 2].toLong() and 0xFFFFFF
    fun placeholderMarks(col: Int): List<Int> {
        var index = offsets[col] + 2
        return buildList {
            while (index < offsets[col + 1]) {
                val cp = Character.codePointAt(text, index, offsets[col + 1])
                add(cp)
                index += Character.charCount(cp)
            }
        }
    }
    fun flags(col: Int): Int = attributes[col] and 0xFFFFFF
    fun foreground(col: Int): Color = Color(colors[col * 2])
    fun background(col: Int): Color = Color(colors[col * 2 + 1])
    fun charAt(col: Int): Char = if (width(col) == 0 && col > 0) charAt(col - 1) else text.getOrElse(offsets[col]) { '\u0000' }
    fun blank(col: Int): Boolean = width(col) != 0 && offsets[col + 1] - offsets[col] <= 1 && charAt(col).let { it == ' ' || it == '\u0000' }
    fun appendCellText(target: StringBuilder, col: Int) {
        target.append(text, offsets[col], offsets[col + 1] - offsets[col])
    }

    fun text(start: Int = 0, end: Int = size): String {
        require(start in 0..end && end <= size)
        if (start == end) return ""
        val first = if (start < size && width(start) == 0 && start > 0) start - 1 else start
        if ((first until end).none(::placeholder)) return String(text, offsets[first], offsets[end] - offsets[first])
        return buildString {
            for (col in first until end) {
                if (placeholder(col)) append(' ') else append(text, offsets[col], offsets[col + 1] - offsets[col])
            }
        }
    }

    fun columnText(): String = String(CharArray(size) { if (placeholder(it)) ' ' else charAt(it) })

    fun singleAscii(col: Int): Int {
        if (width(col) != 1 || offsets[col + 1] - offsets[col] != 1) return -1
        val ch = text[offsets[col]].code
        return if (ch in 32..126) ch else -1
    }

    fun boxCodePoint(col: Int): Int {
        if (col !in indices || offsets[col + 1] - offsets[col] != 1) return 0
        val codePoint = text[offsets[col]].code
        return if (codePoint in 0x2500..0x259F) codePoint else 0
    }

    // One lazily allocated measurement array per retained row, replaced on font changes.
    private var measuredTypeface: android.graphics.Typeface? = null

    // The background shaper and the renderer classify the immutable text independently.
    @Volatile private var shapingClass = 0
    private var measuredSize = Float.NaN
    private var advances: FloatArray? = null

    fun draw(canvas: android.graphics.Canvas, col: Int, x: Float, baseline: Float, paint: android.graphics.Paint, cellWidth: Float) {
        val start = offsets[col]
        val count = offsets[col + 1] - start
        // Latin-1 advances belong to the fixed font/size paint. A newly damaged
        // row can draw directly from that cache without allocating a row array.
        val advance = if (paint is TerminalTextPaint && count == 1 && text[start].code < 256) {
            paint.measureCell(text, start, count, flags(col) and 1 != 0)
        } else {
            rowAdvance(col, paint, start, count)
        }
        if (advance <= cellWidth || cellWidth <= 0f) {
            canvas.drawText(text, offsets[col], offsets[col + 1] - offsets[col], x, baseline, paint)
            return
        }
        val saved = canvas.save()
        try {
            canvas.translate(x, baseline)
            if (advance > cellWidth && cellWidth > 0f) canvas.scale(cellWidth / advance, 1f)
            canvas.drawText(text, offsets[col], offsets[col + 1] - offsets[col], 0f, 0f, paint)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    private fun rowAdvance(col: Int, paint: android.graphics.Paint, start: Int, count: Int): Float {
        if (measuredTypeface !== paint.typeface || measuredSize != paint.textSize || advances == null) {
            measuredTypeface = paint.typeface
            measuredSize = paint.textSize
            advances = FloatArray(size) { Float.NaN }
        }
        val measurements = advances!!
        val cached = measurements[col]
        if (!cached.isNaN()) return cached
        val skew = paint.textSkewX
        paint.textSkewX = 0f
        val advance = paint.measureText(text, start, count)
        paint.textSkewX = skew
        measurements[col] = advance
        return advance
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

    fun matches(buffer: IntArray, wordOffset: Int, start: Int, count: Int): Boolean {
        if (start < 0 || count > size - start) return false
        var base = wordOffset
        for (col in start until start + count) {
            val meta = buffer[base]
            val span = meta ushr CellData.SCREEN_WIDTH_SHIFT
            if (width(col) != span || flags(col) != buffer[base + 3]) return false
            val first = meta and CellData.SCREEN_CODE_POINT_MASK
            val extra = CellData.screenExtraCount(meta)
            val isPlaceholder = first == CellData.IMAGE_PLACEHOLDER
            val side = base + CellData.SCREEN_WORDS
            if (span != 0) {
                if (isPlaceholder && placeholderIds[col] != buffer[side + extra]) return false
                if (colors[col * 2] != buffer[base + 1] or (0xFF shl 24) ||
                    colors[col * 2 + 1] != buffer[base + 2] or (0xFF shl 24)
                ) {
                    return false
                }
                var index = offsets[col]
                val end = offsets[col + 1]
                for (slot in 0..extra) {
                    val cp = displayCodePoint(if (slot == 0) first else buffer[side + slot - 1])
                    if (index >= end || Character.codePointAt(text, index, end) != cp) return false
                    index += Character.charCount(cp)
                }
                if (index != end) return false
            }
            base = side + extra + if (isPlaceholder) 1 else 0
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
        buffer.putInt(offset + CellData.WIDTH, width(col))
        buffer.putInt(offset + CellData.FOREGROUND, colors[col * 2])
        buffer.putInt(offset + CellData.BACKGROUND, colors[col * 2 + 1])
        buffer.putInt(offset + CellData.FLAGS, flags(col))
        if (placeholder(col)) buffer.putInt(offset + 14 * 4, placeholderIds[col] ?: 0)
    }

    class Builder(private val columns: Int, previous: PackedCells? = null) {
        // A similarly shaped row often needs the same number of UTF-16 units.
        private var text = CharArray(maxOf(columns, minOf(previous?.text?.size ?: 0, columns * 2)))
        private var used = 0
        private val offsets = IntArray(columns + 1)
        private val colors = IntArray(columns * 2)
        private val attributes = IntArray(columns)
        private var column = 0
        private var placeholderIds: MutableMap<Int, Int>? = null

        private fun addPlaceholder(column: Int, id: Int) {
            val ids = placeholderIds ?: mutableMapOf<Int, Int>().also { placeholderIds = it }
            ids[column] = id
        }

        fun copy(source: PackedCells, start: Int, end: Int) {
            if (start == end) return
            val first = source.offsets[start]
            val length = source.offsets[end] - first
            if (length > text.size - used) text = text.copyOf(maxOf(used + length, text.size * 2))
            source.text.copyInto(text, used, first, first + length)
            source.colors.copyInto(colors, column * 2, start * 2, end * 2)
            source.attributes.copyInto(attributes, column, start, end)
            for (col in start..end) offsets[column + col - start] = used + (source.offsets[col] - first)
            if (source.placeholderIds.isNotEmpty()) {
                for (col in start until end) source.placeholderIds[col]?.let { addPlaceholder(column + col - start, it) }
            }
            used += length
            column += end - start
        }

        fun read(buffer: ByteBuffer, offset: Int, count: Int) = readRecords(count) { buffer.getInt(offset + it * 4) }

        fun read(buffer: IntArray, wordOffset: Int, count: Int) {
            require(count in 0..columns - column)
            var base = wordOffset
            repeat(count) {
                val meta = buffer[base]
                val width = meta ushr CellData.SCREEN_WIDTH_SHIFT
                require(width in 0..2 && width <= columns - column)
                val first = meta and CellData.SCREEN_CODE_POINT_MASK
                val extra = CellData.screenExtraCount(meta)
                val side = base + CellData.SCREEN_WORDS
                val isPlaceholder = first == CellData.IMAGE_PLACEHOLDER
                if (isPlaceholder) addPlaceholder(column, buffer[side + extra])
                if (width != 0) {
                    appendCompactCodePoint(first)
                    for (index in side until side + extra) appendCompactCodePoint(buffer[index])
                }
                colors[column * 2] = buffer[base + 1] or (0xFF shl 24)
                colors[column * 2 + 1] = buffer[base + 2] or (0xFF shl 24)
                attributes[column++] = (width shl 24) or buffer[base + 3]
                offsets[column] = used
                base = side + extra + if (isPlaceholder) 1 else 0
            }
        }

        // Keep the common one-unit write in the compact decoder's loop.
        @Suppress("NOTHING_TO_INLINE")
        private inline fun appendCompactCodePoint(raw: Int) {
            if (raw in 0..0xFFFF && raw !in 0xD800..0xDFFF && used < text.size) {
                text[used++] = if (raw == 0) ' ' else raw.toChar()
            } else {
                appendCodePoint(raw)
            }
        }

        private fun appendCodePoint(raw: Int) {
            val cp = displayCodePoint(raw)
            val end = used + Character.charCount(cp)
            if (end > text.size) text = text.copyOf(maxOf(16, text.size * 2, end))
            if (cp <= 0xFFFF) {
                text[used] = cp.toChar()
            } else {
                text[used] = Character.highSurrogate(cp)
                text[used + 1] = Character.lowSurrogate(cp)
            }
            used = end
        }

        private inline fun readRecords(count: Int, getWord: (Int) -> Int) {
            require(count >= 0 && count <= columns - column)
            for (i in 0 until count) {
                val base = i * CellData.STRIDE
                val width = getWord(base + CellData.WIDTH / 4)
                val firstCodePoint = getWord(base)
                if (firstCodePoint == CellData.IMAGE_PLACEHOLDER) addPlaceholder(column, getWord(base + 14))
                require(width in 0..2 && width <= columns - column)
                offsets[column] = used
                if (width != 0) {
                    for (slot in 0 until CellData.CODE_POINTS) {
                        val raw = if (slot == 0) firstCodePoint else getWord(base + slot)
                        if (slot > 0 && raw == 0) break
                        appendCodePoint(raw)
                    }
                }
                colors[column * 2] = getWord(base + CellData.FOREGROUND / 4) or (0xFF shl 24)
                colors[column * 2 + 1] = getWord(base + CellData.BACKGROUND / 4) or (0xFF shl 24)
                attributes[column++] = (width shl 24) or getWord(base + CellData.FLAGS / 4)
                offsets[column] = used
            }
        }

        fun build(): PackedCells {
            check(column == columns)
            return PackedCells(if (used == text.size) text else text.copyOf(used), offsets, colors, attributes, placeholderIds?.toMap().orEmpty())
        }
    }

    companion object {
        private fun displayCodePoint(raw: Int): Int = when {
            raw == 0 -> 32
            CellData.isScalar(raw) -> raw
            else -> 0xFFFD
        }

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
