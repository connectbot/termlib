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
    private var advanceScratch = FloatArray(0)

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
        advanceScratch = FloatArray(0)
    }

    // Cache maintenance must not subscribe every retained row's display list to
    // the entire snapshot. The caller already observes its own immutable line.
    fun viewport(state: TerminalScreenState) = Snapshot.withoutReadObservation {
        viewport = state
        viewportRows = state.snapshot.rows
        if (sequence == state.snapshot.sequenceNumber && scrollback == state.scrollbackPosition) return@withoutReadObservation
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
    fun shape(text: CharArray, offsets: IntArray, cells: PackedCells): ShapedLine {
        val logicalToVisual = IntArray(cells.size) { it }
        val visualToLogical = IntArray(cells.size) { it }
        val clusters = arrayOfNulls<ShapedCluster>(cells.size)
        val variants = HashMap<FontVariant, Font>()
        // At most 32 KiB of reusable scratch; exceptional rows use temporary storage.
        val advances = if (text.size <= 8192) {
            if (advanceScratch.size < text.size) advanceScratch = FloatArray(text.size)
            advanceScratch
        } else {
            FloatArray(text.size)
        }
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
            val rtl = script < 0
            var styleStart = start
            while (styleStart < end) {
                val style = cells.flags(styleStart) and 9
                var styleEnd = styleStart + 1
                while (styleEnd < end && cells.flags(styleEnd) and 9 == style) styleEnd++
                shapingPaint.isFakeBoldText = style and 1 != 0
                shapingPaint.textSkewX = if (style and 8 != 0) -0.25f else 0f
                shapingPaint.getTextRunAdvances(
                    text,
                    offsets[styleStart],
                    offsets[styleEnd] - offsets[styleStart],
                    offsets[start],
                    offsets[end] - offsets[start],
                    rtl,
                    advances,
                    offsets[styleStart],
                )
                styleStart = styleEnd
            }
            var first = start
            while (first < end) {
                val style = cells.flags(first) and 9
                shapingPaint.isFakeBoldText = style and 1 != 0
                shapingPaint.textSkewX = if (style and 8 != 0) -0.25f else 0f
                var next = first + cells.width(first).coerceAtLeast(1)
                while (next < end && cells.flags(next) and 9 == style &&
                    (
                        advances[offsets[next]] == 0f || (script == -1 && lamAlef(text, offsets[first], offsets[next])) ||
                            shapingPaint.getTextRunCursor(
                                text,
                                offsets[start],
                                offsets[end] - offsets[start],
                                rtl,
                                offsets[next],
                                Paint.CURSOR_AT_OR_AFTER,
                            ) != offsets[next]
                        )
                ) {
                    next += cells.width(next).coerceAtLeast(1)
                }
                val visual = if (rtl) start + end - next else first
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
                val glyphs = TextRunShaper.shapeTextRun(
                    text, offsets[first], offsets[next] - offsets[first], offsets[start], offsets[end] - offsets[start],
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
        return ShapedLine(logicalToVisual, visualToLogical, clusters)
    }

    companion object {
        // Some Arabic fonts implement lam–alef with two advancing glyphs and expose
        // a cursor between them. They still need one footprint to preserve joining.
        private fun lamAlef(text: CharArray, start: Int, boundary: Int): Boolean {
            val alef = Character.codePointAt(text, boundary, text.size)
            if (android.icu.lang.UCharacter.getIntPropertyValue(alef, android.icu.lang.UProperty.JOINING_GROUP) != android.icu.lang.UCharacter.JoiningGroup.ALEF) return false
            var previous = boundary
            while (previous > start) {
                val cp = Character.codePointBefore(text, previous, start)
                previous -= Character.charCount(cp)
                if (cp == 0x200C) return false
                if (inherited(cp)) continue
                return android.icu.lang.UCharacter.getIntPropertyValue(cp, android.icu.lang.UProperty.JOINING_GROUP) == android.icu.lang.UCharacter.JoiningGroup.LAM
            }
            return false
        }

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
    private val clusters: Array<ShapedCluster?>,
) {
    private val clip = android.graphics.Rect()
    val bytes: Int = 160 + logicalToVisual.size * 16 + clusters.indices.sumOf {
        val cluster = clusters[it]
        if (cluster != null && (it == 0 || clusters[it - 1] !== cluster)) cluster.bytes else 0
    }
    fun visualColumn(logical: Int): Int = logicalToVisual.getOrElse(logical) { logical }
    fun logicalColumn(visual: Int): Int = visualToLogical.getOrElse(visual) { visual }

    fun prepareDraw(canvas: Canvas) {
        canvas.getClipBounds(clip)
    }

    @RequiresApi(31)
    fun drawCell(canvas: Canvas, col: Int, baseline: Float, paint: Paint, cellWidth: Float, columns: Int): Boolean {
        val cluster = clusters[col] ?: return false
        val saved = canvas.save()
        try {
            val x = visualColumn(col) * cellWidth
            // Clip only horizontally: preserve marks extending above/below the row.
            canvas.clipRect(x, clip.top.toFloat(), x + cellWidth * columns, clip.bottom.toFloat())
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
