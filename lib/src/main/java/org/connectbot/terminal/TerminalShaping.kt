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
internal class TerminalShaping(sourcePaint: TerminalTextPaint, private val byteLimit: Int = 1024 * 1024) {
    private val cache = IdentityHashMap<PackedCells, ShapedLine>()
    private val independentRows = IdentityHashMap<PackedCells, Boolean>()
    private val keys = ArrayList<PackedCells>()
    private val shapingPaint = Paint(sourcePaint).apply { fontFeatureSettings = "'liga' 0, 'clig' 0" }
    private var viewportRows = 1
    private var viewport: TerminalScreenState? = null
    private var sequence = -1L
    private var scrollback = -1
    private var width = Float.NaN
    private var contextLines: List<TerminalLine> = emptyList()

    private var rowBytes = 0
    private var layoutBytes = 0
    private val layouts = LinkedHashMap<LayoutKey, RetainedLayout>(16, 0.75f, true)
    private var resolutionBytes = 0
    private val resolutions = LinkedHashMap<ResolutionKey, ResolvedLine>(16, 0.75f, true)
    var paragraphResolveCount = 0
        private set
    private var glyphBytes = 0
    private val glyphRuns = LinkedHashMap<GlyphRunKey, GlyphRun>(16, 0.75f, true)
    val retainedBytes: Int get() = rowBytes + glyphBytes + resolutionBytes + layoutBytes
    var glyphShapeCount = 0
        private set
    val cachedRows: Int get() = cache.size
    var shapeCount = 0
        private set

    fun clear() {
        layouts.clear()
        layoutBytes = 0
        resolutions.clear()
        resolutionBytes = 0
        glyphRuns.clear()
        glyphBytes = 0
        cache.clear()
        independentRows.clear()
        keys.clear()
        rowBytes = 0
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
            independentRows.clear()
            keys.clear()
            rowBytes = 0
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
        independentRows.remove(key)
        rowBytes -= cache.remove(key)!!.bytes + key.shapingRetentionBytes
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
            layouts.clear()
            layoutBytes = 0
            cache.clear()
            independentRows.clear()
            keys.clear()
            rowBytes = 0
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
            layouts.clear()
            layoutBytes = 0
            cache.clear()
            independentRows.clear()
            keys.clear()
            rowBytes = 0
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
        if (first == last) {
            val cells = lines.single().cells
            for (key in keys) {
                if (independentRows.containsKey(key) && cells.sameParagraphShaping(key)) {
                    // Keep one owner for identical independent rows. Charging a
                    // shared layout once per physical row exhausts the history
                    // budget on tall viewports and forces repeated shaping.
                    return@withoutReadObservation cache[key]!!
                }
            }
        }
        val context = buildString {
            for (line in lines) {
                val raw = line.cells.shapingContextText()
                append((raw.length ushr 16).toChar())
                append(raw.length.toChar())
                append(raw)
            }
        }
        val target = state.getLine(absolute).cells
        val targetKey = LayoutKey(context, absolute - first, target)
        this.layouts[targetKey]?.let {
            admit(target, it.line, independent = first == last)
            return@withoutReadObservation it.line
        }
        val layouts = shapeParagraph(lines)
        val visibleFirst = state.visibleLineIndex(0)
        val visibleLast = state.visibleLineIndex(state.snapshot.rows - 1)
        layouts.forEachIndexed { index, shaped ->
            if (first + index !in visibleFirst..visibleLast) return@forEachIndexed
            val cells = lines[index].cells
            retainLayout(LayoutKey(context, index, cells), shaped)
            if (!cache.containsKey(cells)) {
                shapeCount++
                admit(cells, shaped, independent = first == last)
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
        val paragraphText = paragraph.toString()
        var paragraphBidi: Bidi? = null
        return lines.indices.map { row ->
            val cells = lines[row].cells
            val logicalToVisual = IntArray(cells.size) { it }
            val visualToLogical = IntArray(cells.size) { it }
            val levels = ByteArray(cells.size)
            val mirrors = IntArray(cells.size)
            data class UnitCell(val col: Int, val width: Int, val visual: Int)
            val units = ArrayList<UnitCell>()
            if (lineStarts[row] < lineEnds[row]) {
                val resolved = resolveLine(paragraphText, lineStarts[row], lineEnds[row]) {
                    paragraphBidi ?: Bidi(paragraphText, Bidi.DIRECTION_LEFT_TO_RIGHT).also { paragraphBidi = it }
                }
                val lineVisual = resolved.visual
                val lineLevels = resolved.levels
                for (col in cells.indices) {
                    val start = starts[row][col]
                    if (start < 0 || cells.width(col) == 0) continue
                    val localStart = start - lineStarts[row]
                    val localEnd = ends[row][col] - lineStarts[row]
                    var visual = Int.MAX_VALUE
                    for (index in localStart until localEnd) visual = minOf(visual, lineVisual[index])
                    val level = lineLevels[localStart]
                    levels[col] = level
                    mirrors[col] = resolved.mirrors[localStart]
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

    private class LayoutKey(val context: String, val row: Int, val cells: PackedCells) {
        override fun hashCode(): Int = (31 * context.hashCode() + row) * 31 + cells.paragraphShapingHash()
        override fun equals(other: Any?): Boolean = other is LayoutKey && row == other.row && context == other.context && cells.sameParagraphShaping(other.cells)
    }

    private class RetainedLayout(val line: ShapedLine, val bytes: Int)

    private fun retainLayout(key: LayoutKey, line: ShapedLine) {
        if (layouts.containsKey(key)) return
        val bytes = 128L + key.context.length * 2L + key.cells.shapingRetentionBytes + line.bytes
        val limit = minOf(byteLimit * 3 / 4, byteLimit - rowBytes - glyphBytes - resolutionBytes)
        if (bytes <= limit) {
            val entries = layouts.entries.iterator()
            while (layoutBytes + bytes > limit && entries.hasNext()) {
                layoutBytes -= entries.next().value.bytes
                entries.remove()
            }
            layouts[key] = RetainedLayout(line, bytes.toInt())
            layoutBytes += bytes.toInt()
        }
    }

    private data class ResolutionKey(val paragraph: String, val start: Int, val end: Int)

    private class ResolvedLine(val visual: IntArray, val levels: ByteArray, val mirrors: IntArray, val bytes: Long)

    // The key includes the complete hard-newline paragraph and the physical row
    // bounds. A wrapped row cannot borrow ordering from an isolated equal string.
    @RequiresApi(31)
    private fun resolveLine(text: String, start: Int, end: Int, paragraph: () -> Bidi): ResolvedLine {
        val key = ResolutionKey(text, start, end)
        resolutions[key]?.let { return it }
        val bidi = paragraph()
        val visual = IntArray(end - start)
        val levels = ByteArray(visual.size)
        val mirrors = IntArray(visual.size)
        var segmentStart = start
        while (segmentStart < end) {
            // ICU line ranges must stay within a single Unicode paragraph,
            // including separators embedded in a physical terminal row.
            val segmentEnd = minOf(end, bidi.getParagraph(segmentStart).limit)
            val line = bidi.createLineBidi(segmentStart, segmentEnd)
            val offset = segmentStart - start
            for (index in 0 until segmentEnd - segmentStart) {
                visual[offset + index] = offset + line.getVisualIndex(index)
                levels[offset + index] = line.getLevelAt(index)
            }
            segmentStart = segmentEnd
        }
        var offset = 0
        while (offset < visual.size) {
            val cp = Character.codePointAt(text, start + offset)
            if (levels[offset].toInt() and 1 != 0) {
                val mirror = android.icu.lang.UCharacter.getMirror(cp)
                if (mirror != cp) mirrors[offset] = mirror
            }
            offset += Character.charCount(cp)
        }
        paragraphResolveCount++
        val bytes = 192L + text.length * 2L + visual.size * 9L
        val result = ResolvedLine(visual, levels, mirrors, bytes)
        val limit = minOf(byteLimit / 4, byteLimit - rowBytes - glyphBytes - layoutBytes)
        if (bytes <= limit) {
            val entries = resolutions.entries.iterator()
            while (resolutionBytes + bytes > limit && entries.hasNext()) {
                resolutionBytes -= entries.next().value.bytes.toInt()
                entries.remove()
            }
            resolutions[key] = result
            resolutionBytes += bytes.toInt()
        }
        return result
    }

    private fun admit(cells: PackedCells, result: ShapedLine, independent: Boolean = false) {
        val bytes = result.bytes.toLong() + cells.shapingRetentionBytes
        // Do not cycle through visible entries when a viewport exceeds the byte budget.
        if (cache.size < viewportRows && bytes <= byteLimit - retainedBytes) {
            cache[cells] = result
            if (independent) independentRows[cells] = true
            keys.add(cells)
            rowBytes += bytes.toInt()
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
                val run = glyphRun(text, offsets[first], offsets[next] - offsets[first], contextStart, contextEnd, rtl, style, variants)
                val targetWidth = (next - first) * width
                val scale = if (run.advance > targetWidth && targetWidth > 0f) targetWidth / run.advance else 1f
                val cluster = ShapedCluster(visual, next - first, scale, run.batches)
                for (i in first until next) clusters[i] = cluster
                first = next
            }
        }
        return ShapedLine(logicalToVisual, visualToLogical, levels, bidiMirrors ?: IntArray(cells.size), clusters)
    }

    // Keep the complete shaping context and target bounds in the key: the same
    // visible letters can have different joining forms across soft row wraps.
    private data class GlyphRunKey(val context: String, val start: Int, val count: Int, val rtl: Boolean, val style: Int)

    private class GlyphRun(val advance: Float, val batches: Array<GlyphBatch>, val bytes: Int)

    @RequiresApi(31)
    private fun glyphRun(
        text: CharArray,
        start: Int,
        count: Int,
        contextStart: Int,
        contextEnd: Int,
        rtl: Boolean,
        style: Int,
        variants: MutableMap<FontVariant, Font>,
    ): GlyphRun {
        val key = GlyphRunKey(String(text, contextStart, contextEnd - contextStart), start - contextStart, count, rtl, style)
        glyphRuns[key]?.let { return it }
        shapingPaint.isFakeBoldText = style and 1 != 0
        shapingPaint.textSkewX = if (style and 8 != 0) -0.25f else 0f
        val glyphs = TextRunShaper.shapeTextRun(text, start, count, contextStart, contextEnd - contextStart, 0f, 0f, rtl, shapingPaint)
        glyphShapeCount++
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
        val bytes = 160 + key.context.length * 2 + batches.sumOf { 112 + it.ids.size * 20 }
        val result = GlyphRun(glyphs.advance, batches.toTypedArray(), bytes)
        // This cache shares the layout budget, and reserves at least three
        // quarters for visible rows. Oversized runs never evict useful entries.
        val limit = minOf(byteLimit / 4, byteLimit - rowBytes - resolutionBytes - layoutBytes)
        if (bytes <= limit) {
            val entries = glyphRuns.entries.iterator()
            while (glyphBytes + bytes > limit && entries.hasNext()) {
                glyphBytes -= entries.next().value.bytes
                entries.remove()
            }
            glyphRuns[key] = result
            glyphBytes += bytes
        }
        return result
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
    private val mirroredGlyphs = if (mirrors.any { it != 0 }) {
        Array(mirrors.size) { col -> mirrors[col].takeIf { it != 0 }?.let { MirroredGlyph(String(Character.toChars(it))) } }
    } else {
        null
    }
    val bytes: Int = 160 + (mirroredGlyphs?.let { 16 + it.size * 4 + it.sumOf { glyph -> if (glyph == null) 0 else 112 + glyph.text.length * 4 } } ?: 0) + logicalToVisual.size * 20 + clusters.indices.sumOf {
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

    /** Exclusive logical end of the glyph run containing this cell. */
    fun clusterEnd(col: Int): Int {
        val cluster = clusters[col] ?: return col + 1
        var end = col + 1
        while (end < clusters.size && clusters[end] === cluster) end++
        return end
    }

    @RequiresApi(31)
    fun drawCell(canvas: Canvas, col: Int, baseline: Float, paint: Paint, cellWidth: Float, columns: Int, endColumn: Int = col + columns): Boolean {
        val cluster = clusters[col]
        val mirror = mirrors.getOrElse(col) { 0 }
        if (cluster == null && mirror == 0) return false
        val saved = canvas.save()
        try {
            val x = minOf(visualColumn(col), visualColumn(endColumn - 1)) * cellWidth
            // Clip only horizontally: preserve marks extending above/below the row.
            canvas.clipRect(x, clip.top.toFloat(), x + cellWidth * (endColumn - col), clip.bottom.toFloat())
            if (cluster == null) {
                val glyph = mirroredGlyphs!![col]!!
                val advance = if (glyph.advance.isNaN()) {
                    val measured = if (paint is TerminalTextPaint) paint.measureCell(glyph.characters, 0, glyph.characters.size) else paint.measureText(glyph.text)
                    measured.also { glyph.advance = it }
                } else {
                    glyph.advance
                }
                if (advance > cellWidth * columns && advance > 0f) {
                    canvas.translate(x, baseline)
                    canvas.scale(cellWidth * columns / advance, 1f)
                    canvas.drawText(glyph.text, 0f, 0f, paint)
                } else {
                    canvas.drawText(glyph.text, x, baseline, paint)
                }
                return true
            }
            val absolutePositions = cluster.scale == 1f
            if (!absolutePositions) {
                canvas.translate(cluster.visual * cellWidth, baseline)
                canvas.scale(cluster.scale, 1f)
            }
            for (batch in cluster.batches) {
                val positions = if (absolutePositions) batch.at(cluster.visual * cellWidth, baseline) else batch.positions
                if (batch.fakeBold || batch.fakeItalic) {
                    val bold = paint.isFakeBoldText
                    val skew = paint.textSkewX
                    try {
                        if (batch.fakeBold) paint.isFakeBoldText = true
                        if (skew == 0f && batch.fakeItalic) paint.textSkewX = -0.25f
                        canvas.drawGlyphs(batch.ids, 0, positions, 0, batch.ids.size, batch.font, paint)
                    } finally {
                        paint.isFakeBoldText = bold
                        paint.textSkewX = skew
                    }
                } else {
                    canvas.drawGlyphs(batch.ids, 0, positions, 0, batch.ids.size, batch.font, paint)
                }
            }
        } finally {
            canvas.restoreToCount(saved)
        }
        return true
    }
}

internal class ShapedCluster(val visual: Int, val columns: Int, val scale: Float, val batches: Array<GlyphBatch>) {
    val bytes: Int = 48 + batches.sumOf { 112 + it.ids.size * 20 }
}

internal class GlyphBatch(val ids: IntArray, val positions: FloatArray, val font: Font, val fakeBold: Boolean, val fakeItalic: Boolean) {
    private val drawingPositions = FloatArray(positions.size)
    private var origin = Float.NaN
    private var baseline = Float.NaN

    fun at(x: Float, y: Float): FloatArray {
        if (origin != x || baseline != y) {
            for (glyph in ids.indices) {
                drawingPositions[glyph * 2] = positions[glyph * 2] + x
                drawingPositions[glyph * 2 + 1] = positions[glyph * 2 + 1] + y
            }
            origin = x
            baseline = y
        }
        return drawingPositions
    }
}

private data class FontVariant(val font: Font, val weight: Float, val italic: Float)

private class MirroredGlyph(val text: String) {
    val characters = text.toCharArray()
    var advance = Float.NaN
}
