/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.fonts.Font
import android.graphics.fonts.FontVariationAxis
import android.graphics.text.PositionedGlyphs
import android.graphics.text.TextRunShaper
import android.icu.text.Bidi
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.snapshots.Snapshot
import java.util.IdentityHashMap

/** UI-thread-only shaping state. Neither snapshots nor scrollback own glyph caches. */
internal class TerminalShaping(sourcePaint: TerminalTextPaint, private val byteLimit: Int = 256 * 1024) {
    private val cache = IdentityHashMap<PackedCells, ShapedLine>()
    private val keys = ArrayList<PackedCells>()
    private val shapingPaint = Paint(sourcePaint).apply { fontFeatureSettings = "'liga' 0, 'clig' 0" }
    private var viewportRows = 1
    private var viewport: TerminalScreenState? = null
    private var sequence = -1L
    private var scrollback = -1
    private var width = Float.NaN
    private var contextLines: List<TerminalLine> = emptyList()

    var retainedBytes = 0
        private set
    val cachedRows: Int get() = cache.size
    var shapeCount = 0
        private set

    fun clear() {
        cache.clear()
        keys.clear()
        retainedBytes = 0
        viewport = null
        sequence = -1L
        contextLines = emptyList()
    }

    // Cache maintenance must not subscribe every retained row's display list to
    // the entire snapshot. The caller already observes its own immutable line.
    fun viewport(state: TerminalScreenState) = Snapshot.withoutReadObservation {
        viewport = state
        viewportRows = state.snapshot.rows
        if (sequence == state.snapshot.sequenceNumber && scrollback == state.scrollbackPosition) return@withoutReadObservation
        val newContext = state.snapshot.scrollback + state.snapshot.lines
        if (contextLines.size != newContext.size || contextLines.indices.any {
                contextLines[it].cells !== newContext[it].cells || contextLines[it].softWrapped != newContext[it].softWrapped
            }
        ) {
            cache.clear()
            keys.clear()
            retainedBytes = 0
            contextLines = newContext
        }
        sequence = state.snapshot.sequenceNumber
        scrollback = state.scrollbackPosition
        var index = keys.lastIndex
        while (index >= 0) {
            val key = keys[index]
            var visible = false
            for (row in 0 until viewportRows) {
                val cells = state.getVisibleLine(row).cells
                if (cells === key || cells.sameShaping(key)) {
                    visible = true
                    break
                }
            }
            if (!visible) remove(index)
            index--
        }
    }

    private fun remove(index: Int) {
        val key = keys.removeAt(index)
        retainedBytes -= cache.remove(key)!!.bytes + key.shapingRetentionBytes
    }

    private fun visible(key: PackedCells): Boolean = Snapshot.withoutReadObservation {
        val state = viewport ?: return@withoutReadObservation true
        for (row in 0 until viewportRows) if (state.getVisibleLine(row).cells === key) return@withoutReadObservation true
        false
    }

    // Also used by standalone render tests, which have no Compose viewport owner.
    fun resize(rows: Int) {
        viewportRows = rows.coerceAtLeast(1)
        if (cache.size > viewportRows) clear()
    }

    @RequiresApi(31)
    fun layout(cells: PackedCells, cellWidth: Float): ShapedLine {
        if (width != cellWidth) {
            cache.clear()
            keys.clear()
            retainedBytes = 0
            width = cellWidth
        }
        viewport?.let(::viewport)
        cache[cells]?.let { return it }
        // Reuse geometry across colors and identical visible rows. Do not re-key an
        // entry still in use by another visible row: that allocates on every redraw.
        for (index in keys.indices) {
            val key = keys[index]
            if (cells.sameShaping(key)) {
                val result = cache[key]!!
                if (!visible(key)) remove(index)
                admit(cells, result)
                return result
            }
        }
        val result = cells.shape(this)
        shapeCount++
        admit(cells, result)
        return result
    }

    /** Layout one physical row using the complete hard-newline-delimited paragraph. */
    @RequiresApi(31)
    fun layout(state: TerminalScreenState, row: Int, cellWidth: Float): ShapedLine? = Snapshot.withoutReadObservation {
        viewport(state)
        if (width != cellWidth) {
            cache.clear()
            keys.clear()
            retainedBytes = 0
            width = cellWidth
        }
        val absolute = state.visibleLineIndex(row)
        if (absolute !in 0 until state.totalLines) return@withoutReadObservation null
        cache[state.getLine(absolute).cells]?.let { return@withoutReadObservation it }

        var first = absolute
        while (first > 0 && state.getLine(first - 1).softWrapped && absolute - first < MAX_PARAGRAPH_ROWS) first--
        var last = absolute
        while (last + 1 < state.totalLines && state.getLine(last).softWrapped && last - first + 1 < MAX_PARAGRAPH_ROWS) last++
        // A paragraph beyond the safety cap is displayed in explicit LTR order.
        if ((first > 0 && state.getLine(first - 1).softWrapped) || (last + 1 < state.totalLines && state.getLine(last).softWrapped)) {
            return@withoutReadObservation null
        }
        val lines = (first..last).map(state::getLine)
        if (lines.none { it.cells.needsShaping() }) return@withoutReadObservation null
        val layouts = shapeParagraph(lines)
        val visibleFirst = state.visibleLineIndex(0)
        val visibleLast = state.visibleLineIndex(state.snapshot.rows - 1)
        layouts.forEachIndexed { index, shaped ->
            if (first + index !in visibleFirst..visibleLast) return@forEachIndexed
            val cells = lines[index].cells
            if (!cache.containsKey(cells)) {
                shapeCount++
                admit(cells, shaped)
            }
        }
        layouts[absolute - first]
    }

    @RequiresApi(31)
    private fun shapeParagraph(lines: List<TerminalLine>): List<ShapedLine> {
        val paragraph = StringBuilder()
        val starts = ArrayList<IntArray>(lines.size)
        val ends = ArrayList<IntArray>(lines.size)
        val lineStarts = IntArray(lines.size)
        val lineEnds = IntArray(lines.size)
        lines.forEachIndexed { row, line ->
            lineStarts[row] = paragraph.length
            val cellStarts = IntArray(line.cells.size) { -1 }
            val cellEnds = IntArray(line.cells.size) { -1 }
            for (col in line.cells.indices) {
                if (line.cells.width(col) == 0 || line.cells.charAt(col) == '\u0000') continue
                cellStarts[col] = paragraph.length
                if (line.cells.placeholder(col)) paragraph.append('\uFFFC') else paragraph.append(line.cells.text(col, col + 1))
                cellEnds[col] = paragraph.length
            }
            lineEnds[row] = paragraph.length
            starts.add(cellStarts)
            ends.add(cellEnds)
        }
        val bidi = Bidi(paragraph.toString(), Bidi.DIRECTION_LEFT_TO_RIGHT)
        return lines.indices.map { row ->
            val cells = lines[row].cells
            val logicalToVisual = IntArray(cells.size) { it }
            val visualToLogical = IntArray(cells.size) { it }
            val levels = ByteArray(cells.size)
            val mirrors = IntArray(cells.size)
            data class UnitCell(val col: Int, val width: Int, val visual: Int)
            val units = ArrayList<UnitCell>()
            if (lineStarts[row] < lineEnds[row]) {
                val lineBidi = bidi.createLineBidi(lineStarts[row], lineEnds[row])
                for (col in cells.indices) {
                    val start = starts[row][col]
                    if (start < 0 || cells.width(col) == 0) continue
                    val localStart = start - lineStarts[row]
                    val localEnd = ends[row][col] - lineStarts[row]
                    var visual = Int.MAX_VALUE
                    for (index in localStart until localEnd) visual = minOf(visual, lineBidi.getVisualIndex(index))
                    val level = lineBidi.getLevelAt(localStart)
                    levels[col] = level
                    if (level.toInt() and 1 != 0) {
                        val cp = Character.codePointAt(cells.text(col, col + 1), 0)
                        val mirror = android.icu.lang.UCharacter.getMirror(cp)
                        if (mirror != cp) mirrors[col] = mirror
                    }
                    val columns = cells.width(col).coerceAtLeast(1)
                    for (part in 1 until columns) levels[col + part] = level
                    units.add(UnitCell(col, columns, visual))
                }
            }
            units.sortBy { it.visual }
            var visualCol = 0
            for (unit in units) {
                for (part in 0 until unit.width) {
                    logicalToVisual[unit.col + part] = visualCol + part
                    visualToLogical[visualCol + part] = unit.col + part
                }
                visualCol += unit.width
            }
            // Erased cells are excluded from UAX #9 and occupy the trailing side.
            for (col in cells.indices) {
                if (cells.width(col) == 0 || starts[row][col] >= 0) continue
                logicalToVisual[col] = visualCol
                visualToLogical[visualCol] = col
                visualCol++
            }
            cells.shape(
                this,
                logicalToVisual,
                visualToLogical,
                levels,
                mirrors,
                contextBefore = lines.getOrNull(row - 1)?.cells?.text()?.trimEnd('\u0000') ?: "",
                contextAfter = lines.getOrNull(row + 1)?.cells?.text()?.trimEnd('\u0000') ?: "",
            )
        }
    }

    private fun admit(cells: PackedCells, result: ShapedLine) {
        val bytes = result.bytes.toLong() + cells.shapingRetentionBytes
        // Do not cycle through visible entries when a viewport exceeds the byte budget.
        if (cache.size < viewportRows && bytes <= byteLimit - retainedBytes) {
            cache[cells] = result
            keys.add(cells)
            retainedBytes += bytes.toInt()
        }
    }

    @RequiresApi(31)
    fun shape(
        text: CharArray,
        offsets: IntArray,
        cells: PackedCells,
        bidiLogicalToVisual: IntArray? = null,
        bidiVisualToLogical: IntArray? = null,
        bidiLevels: ByteArray? = null,
        bidiMirrors: IntArray? = null,
    ): ShapedLine {
        val logicalToVisual = bidiLogicalToVisual ?: IntArray(cells.size) { it }
        val visualToLogical = bidiVisualToLogical ?: IntArray(cells.size) { it }
        val levels = bidiLevels ?: ByteArray(cells.size)
        val clusters = arrayOfNulls<ShapedCluster>(cells.size)
        val variants = HashMap<FontVariant, Font>()
        var col = 0
        while (col < cells.size) {
            val start = col
            val script = if (offsets[col] < offsets[col + 1]) script(Character.codePointAt(text, offsets[col], offsets[col + 1])) else 0
            if (script == 0 || cells.placeholder(col)) {
                col++
                continue
            }
            col += cells.width(col).coerceAtLeast(1)
            while (col < cells.size && offsets[col] < offsets[col + 1]) {
                val cp = Character.codePointAt(text, offsets[col], offsets[col + 1])
                if (script(cp) != script && !inherited(cp)) break
                col += cells.width(col).coerceAtLeast(1)
            }
            val end = col.coerceAtMost(cells.size)
            val rtl = if (bidiLevels != null) levels[start].toInt() and 1 != 0 else script < 0
            var contextStart = offsets[start]
            var contextEnd = offsets[end]
            if (bidiLevels != null) {
                while (contextStart > 0) {
                    val cp = Character.codePointBefore(text, contextStart, 0)
                    if (script(cp) != script && !inherited(cp)) break
                    contextStart -= Character.charCount(cp)
                }
                while (contextEnd < text.size) {
                    val cp = Character.codePointAt(text, contextEnd, text.size)
                    if (script(cp) != script && !inherited(cp)) break
                    contextEnd += Character.charCount(cp)
                }
            }
            var first = start
            while (first < end) {
                val style = cells.flags(first) and 9
                shapingPaint.isFakeBoldText = style and 1 != 0
                shapingPaint.textSkewX = if (style and 8 != 0) -0.25f else 0f
                var next = first + cells.width(first).coerceAtLeast(1)
                // Draw a same-style script run as one unit. Splitting at every
                // cursor boundary preserves contextual forms but snaps their
                // advances and overhangs to individual cells, leaving seams in
                // cursive Arabic and Syriac joins.
                while (next < end && cells.flags(next) and 9 == style) {
                    next += cells.width(next).coerceAtLeast(1)
                }
                val visual = if (bidiLevels != null) {
                    (first until next).minOf { logicalToVisual[it] }
                } else {
                    start + end - next
                }
                if (bidiLevels == null) {
                    var logical = first
                    while (logical < next) {
                        val columns = cells.width(logical).coerceAtLeast(1)
                        val position = if (rtl) visual + next - logical - columns else logical
                        for (part in 0 until columns) {
                            logicalToVisual[logical + part] = position + part
                            visualToLogical[position + part] = logical + part
                        }
                        logical += columns
                    }
                }
                val glyphs = TextRunShaper.shapeTextRun(
                    text, offsets[first], offsets[next] - offsets[first], contextStart, contextEnd - contextStart,
                    0f, 0f, rtl, shapingPaint,
                )
                val targetWidth = (next - first) * width
                val scale = if (glyphs.advance > targetWidth && targetWidth > 0f) targetWidth / glyphs.advance else 1f
                val batches = ArrayList<GlyphBatch>()
                var glyph = 0
                while (glyph < glyphs.glyphCount()) {
                    val font = glyphs.getFont(glyph)
                    val weight = if (Build.VERSION.SDK_INT >= 35) glyphs.getWeightOverride(glyph) else Float.MIN_VALUE
                    val italic = if (Build.VERSION.SDK_INT >= 35) glyphs.getItalicOverride(glyph) else Float.MIN_VALUE
                    val fakeBold = Build.VERSION.SDK_INT >= 35 && glyphs.getFakeBold(glyph)
                    val fakeItalic = Build.VERSION.SDK_INT >= 35 && glyphs.getFakeItalic(glyph)
                    var limit = glyph + 1
                    while (limit < glyphs.glyphCount() && glyphs.getFont(limit) == font &&
                        (
                            Build.VERSION.SDK_INT < 35 ||
                                (
                                    glyphs.getWeightOverride(limit) == weight && glyphs.getItalicOverride(limit) == italic &&
                                        glyphs.getFakeBold(limit) == fakeBold && glyphs.getFakeItalic(limit) == fakeItalic
                                    )
                            )
                    ) {
                        limit++
                    }
                    val ids = IntArray(limit - glyph)
                    val positions = FloatArray(ids.size * 2)
                    for (i in ids.indices) {
                        ids[i] = glyphs.getGlyphId(glyph + i)
                        positions[i * 2] = glyphs.getGlyphX(glyph + i)
                        positions[i * 2 + 1] = glyphs.getGlyphY(glyph + i)
                    }
                    val drawingFont = if (weight == Float.MIN_VALUE && italic == Float.MIN_VALUE) {
                        font
                    } else {
                        variants.getOrPut(FontVariant(font, weight, italic)) {
                            val axes = font.axes.orEmpty().filter {
                                !(it.tag == "wght" && weight != Float.MIN_VALUE) && !(it.tag == "ital" && italic != Float.MIN_VALUE)
                            }.toMutableList()
                            if (weight != Float.MIN_VALUE) axes.add(FontVariationAxis("wght", weight))
                            if (italic != Float.MIN_VALUE) axes.add(FontVariationAxis("ital", italic))
                            Font.Builder(font).setFontVariationSettings(axes.toTypedArray()).build()
                        }
                    }
                    batches.add(GlyphBatch(ids, positions, drawingFont, fakeBold, fakeItalic))
                    glyph = limit
                }
                val cluster = ShapedCluster(visual, next - first, scale, batches.toTypedArray())
                for (i in first until next) clusters[i] = cluster
                first = next
            }
        }
        return ShapedLine(logicalToVisual, visualToLogical, levels, bidiMirrors ?: IntArray(cells.size), clusters)
    }

    companion object {
        private const val MAX_PARAGRAPH_ROWS = 500

        // Signed script identifiers: negative scripts use contained RTL ordering.
        // ASCII, CJK and emoji never enter platform script classification or shaping.
        fun script(cp: Int): Int = when (cp) {
            in 0x0600..0x06FF, in 0x0750..0x077F, in 0x0870..0x08FF, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF, in 0x10EC0..0x10EFF -> -1
            in 0x0700..0x074F, in 0x0860..0x086F -> -2
            in 0x0780..0x07BF -> -3
            in 0x07C0..0x07FF -> -4
            in 0x0900..0x0DFF -> 1 + (cp - 0x0900) / 128
            in 0x0E00..0x0E7F -> 11
            in 0x0E80..0x0EFF -> 12
            in 0x0F00..0x0FFF -> 13
            in 0x1000..0x109F, in 0xAA60..0xAA7F, in 0xA9E0..0xA9FF -> 14
            in 0x1780..0x17FF -> 15
            else -> 0
        }.let { script ->
            // Digits and punctuation retain their existing columns, including Arabic digits.
            if (script != 0 && (Character.isLetter(cp) || inherited(cp))) script else 0
        }

        private fun inherited(cp: Int): Boolean = cp == 0x200C || cp == 0x200D || when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }
}

internal class ShapedLine(
    private val logicalToVisual: IntArray,
    private val visualToLogical: IntArray,
    private val levels: ByteArray,
    private val mirrors: IntArray,
    private val clusters: Array<ShapedCluster?>,
) {
    private val clip = android.graphics.Rect()
    val bytes: Int = 160 + logicalToVisual.size * 20 + clusters.indices.sumOf {
        val cluster = clusters[it]
        if (cluster != null && (it == 0 || clusters[it - 1] !== cluster)) cluster.bytes else 0
    }
    fun visualColumn(logical: Int): Int = logicalToVisual.getOrElse(logical) { logical }
    fun logicalColumn(visual: Int): Int = visualToLogical.getOrElse(visual) { visual }
    fun resolvedRtl(logical: Int): Boolean = levels.getOrElse(logical) { 0 }.toInt() and 1 != 0
    internal fun mirroredCodePoint(logical: Int): Int = mirrors.getOrElse(logical) { 0 }

    fun prepareDraw(canvas: Canvas) {
        canvas.getClipBounds(clip)
    }

    @RequiresApi(31)
    fun drawCell(canvas: Canvas, col: Int, baseline: Float, paint: Paint, cellWidth: Float, columns: Int): Boolean {
        val cluster = clusters[col]
        val mirror = mirrors.getOrElse(col) { 0 }
        if (cluster == null && mirror == 0) return false
        val saved = canvas.save()
        try {
            val x = visualColumn(col) * cellWidth
            // Clip only horizontally: preserve marks extending above/below the row.
            canvas.clipRect(x, clip.top.toFloat(), x + cellWidth * columns, clip.bottom.toFloat())
            if (cluster == null) {
                val value = String(Character.toChars(mirror))
                val advance = paint.measureText(value)
                canvas.translate(x, baseline)
                if (advance > cellWidth * columns && advance > 0f) canvas.scale(cellWidth * columns / advance, 1f)
                canvas.drawText(value, 0f, 0f, paint)
                return true
            }
            canvas.translate(cluster.visual * cellWidth, baseline)
            if (cluster.scale != 1f) canvas.scale(cluster.scale, 1f)
            for (batch in cluster.batches) {
                if (batch.fakeBold || batch.fakeItalic) {
                    val bold = paint.isFakeBoldText
                    val skew = paint.textSkewX
                    try {
                        if (batch.fakeBold) paint.isFakeBoldText = true
                        if (skew == 0f && batch.fakeItalic) paint.textSkewX = -0.25f
                        canvas.drawGlyphs(batch.ids, 0, batch.positions, 0, batch.ids.size, batch.font, paint)
                    } finally {
                        paint.isFakeBoldText = bold
                        paint.textSkewX = skew
                    }
                } else {
                    canvas.drawGlyphs(batch.ids, 0, batch.positions, 0, batch.ids.size, batch.font, paint)
                }
            }
        } finally {
            canvas.restoreToCount(saved)
        }
        return true
    }
}

internal class ShapedCluster(val visual: Int, val columns: Int, val scale: Float, val batches: Array<GlyphBatch>) {
    val bytes: Int = 48 + batches.sumOf { 96 + it.ids.size * 12 }
}

internal class GlyphBatch(val ids: IntArray, val positions: FloatArray, val font: Font, val fakeBold: Boolean, val fakeItalic: Boolean)

private data class FontVariant(val font: Font, val weight: Float, val italic: Float)
