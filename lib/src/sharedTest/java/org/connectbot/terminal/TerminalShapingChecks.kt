/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

abstract class TerminalShapingChecks {
    private fun cells(value: String): PackedCells = PackedCells.from(
        value.map {
            TerminalLine.Cell(it, fgColor = Color.White, bgColor = Color.Black)
        },
    )

    private fun paint() = TerminalTextPaint(Typeface.MONOSPACE, 20f)

    @Test
    fun ordinaryScriptsKeepTheirPixelsAndAvoidShaping() {
        val cells = cells("ASCII é 日表 ┼─")
        val paint = paint()
        assertNull(paint.layout(cells, 12f))
        val reference = TextPaint(paint)
        val actual = render(cells, paint)
        val expected = render(cells, reference)
        assertTrue(actual.sameAs(expected))
        actual.recycle()
        expected.recycle()
    }

    @Test
    fun scriptClassificationSurvivesMeasurementAndFontChanges() {
        for (text in listOf("abc", "سلام")) {
            val cells = cells(text)
            val reference = TextPaint(paint())
            for (size in listOf(20f, 12f, 0f, 20f)) {
                reference.textSize = size
                val bitmap = render(cells, reference)
                bitmap.recycle()
                assertEquals(text == "سلام", cells.needsShaping())
            }
        }
    }

    @Test
    fun identicalVisibleRowsKeepIndependentCacheLookups() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val rows = List(8) { TerminalLine(it, cells("سلام")) }
        val state = TerminalScreenState(TerminalSnapshot.empty(8, 4).copy(lines = rows))
        val engine = TerminalShaping(paint())
        engine.viewport(state)
        repeat(3) { rows.forEach { engine.layout(it.cells, 12f) } }
        assertEquals(8, engine.cachedRows)
        assertEquals(1, engine.shapeCount)
    }

    @Test
    fun changingAsciiBesideComplexTextReusesShaping() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val first = cells("00000000 " + "سلام ".repeat(12))
        val second = cells("12345678 " + "سلام ".repeat(12))
        val engine = TerminalShaping(paint())
        val original = engine.layout(first, 12f)
        assertSame(original, engine.layout(second, 12f))
        assertEquals(1, engine.shapeCount)
        for (col in 0..8) assertEquals(col, original.visualColumn(col))
        assertNotSame(original, engine.layout(cells("12345678 " + "مرحبا".repeat(12)), 12f))
    }

    @Test
    fun cacheMaintenanceDoesNotSubscribeUnchangedRowsToTheWholeSnapshot() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val cells = cells("سلام")
        val state = TerminalScreenState(TerminalSnapshot.empty(1, 4).copy(lines = listOf(TerminalLine(0, cells))))
        val engine = TerminalShaping(paint())
        val recolored = PackedCells.from(cells.map { it.copy(fgColor = Color.Red) })
        var reads = 0
        androidx.compose.runtime.snapshots.Snapshot.observe(readObserver = { reads++ }, writeObserver = null) {
            engine.viewport(state)
            engine.layout(cells, 12f)
            engine.layout(recolored, 12f)
        }
        assertEquals(0, reads)
    }

    @Test
    fun rtlStaysInsideScriptSpansAndMappingRoundTrips() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val cells = cells("Aسلام Bمرحبا C日")
        val line = paint().layout(cells, 12f)!!
        for (col in cells.indices) assertEquals(col, line.logicalColumn(line.visualColumn(col)))
        for (col in listOf(0, 5, 6, 12, 13, 14)) assertEquals(col, line.visualColumn(col))
        assertEquals(4, line.visualColumn(1))
        assertEquals(1, line.visualColumn(4))
        assertEquals(11, line.visualColumn(7))
    }

    @Test
    fun paragraphBidiReordersArabicWordsFromLogicalInput() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val value = "Aنص حكيم له سر قاطعZ"
        val cells = cells(value)
        val state = TerminalScreenState(
            TerminalSnapshot.empty(1, cells.size).copy(lines = listOf(TerminalLine(0, cells))),
        )
        val layout = paint().layout(state, 0, 12f)!!
        for (col in cells.indices) assertEquals(col, layout.logicalColumn(layout.visualColumn(col)))
        // The last logical Arabic word is the leftmost one visually; Latin anchors stay put.
        assertEquals(0, layout.visualColumn(0))
        assertEquals(cells.lastIndex, layout.visualColumn(cells.lastIndex))
        assertTrue(layout.visualColumn(value.indexOf('ق')) < layout.visualColumn(value.indexOf('ن')))
        assertTrue(layout.resolvedRtl(value.indexOf('ن')))
        assertFalse(layout.resolvedRtl(0))
    }

    @Test
    fun paragraphBidiMirrorsPairedPunctuationWithoutChangingTheModel() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val value = "Aنص (حكيم)Z"
        val cells = cells(value)
        val state = TerminalScreenState(
            TerminalSnapshot.empty(1, cells.size).copy(lines = listOf(TerminalLine(0, cells))),
        )
        val layout = paint().layout(state, 0, 12f)!!
        val mirrored = value.indices.filter { layout.mirroredCodePoint(it) != 0 }
        assertEquals(listOf(value.indexOf('('), value.indexOf(')')), mirrored)
        assertEquals(value, state.snapshot.lines.single().text)
    }

    @Test
    fun paragraphBidiUsesSoftWrapContextAndHardBreaks() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val terminal = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 8) as TerminalEmulatorImpl
        terminal.writeInput("نص حكيم له سر\r\nABC".toByteArray())
        terminal.processPendingUpdates()
        val snapshot = terminal.snapshot.value
        assertTrue(snapshot.lines[0].softWrapped)
        assertFalse(snapshot.lines[1].softWrapped)
        val state = TerminalScreenState(snapshot)
        val paint = paint()
        for (row in 0..1) {
            val layout = paint.layout(state, row, 12f)!!
            for (col in snapshot.lines[row].cells.indices) assertEquals(col, layout.logicalColumn(layout.visualColumn(col)))
        }
        assertNull(paint.layout(state, 2, 12f))
    }

    @Test
    fun levelOneDropsBidiFormattingControls() {
        val terminal = TerminalEmulatorFactory.create(initialRows = 1, initialCols = 20) as TerminalEmulatorImpl
        terminal.writeInput("A\u200f\u202eB\u202c\u2067C\u2069Z".toByteArray())
        terminal.processPendingUpdates()
        assertEquals("ABCZ", terminal.snapshot.value.lines.single().text.trimEnd())
    }

    @Test
    fun cacheScalesPast64RowsAndReusesColorChanges() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        for (count in listOf(64, 128, 256)) {
            val paint = paint()
            val engine = TerminalShaping(paint)
            engine.resize(count)
            // Different geometry prevents identical rows sharing the same cached layout.
            val rows = List(count) { cells("${(0x0620 + it / 16).toChar()}${(0x0620 + it % 16).toChar()}") }
            val layouts = rows.map { engine.layout(it, 12f) }
            assertEquals(count, engine.cachedRows)
            repeat(3) {
                rows.forEachIndexed { index, row -> assertSame(layouts[index], engine.layout(row, 12f)) }
            }
            assertEquals(count, engine.shapeCount)
            assertTrue(engine.retainedBytes <= 256 * 1024)
            val recolored = PackedCells.from(rows[0].map { it.copy(fgColor = Color.Red) })
            assertSame(layouts[0], engine.layout(recolored, 12f))
            assertEquals(count, engine.shapeCount)
            engine.clear()
            assertEquals(0, engine.retainedBytes)
            assertEquals(0, engine.cachedRows)
        }
    }

    @Test
    fun overBudgetRowsDoNotEvictAdmittedVisibleRows() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val engine = TerminalShaping(paint(), 2048)
        engine.resize(256)
        val small = cells("ب")
        val first = engine.layout(small, 12f)
        repeat(10) {
            engine.layout(cells("سلام".repeat(80)), 12f)
            assertSame(first, engine.layout(small, 12f))
            assertTrue(engine.retainedBytes <= 2048)
        }
    }

    @Test
    fun selectionUsesVisualPositionsButCopiesLogicalText() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val line = TerminalLine(0, cells("Aبتم Z"))
        val snapshot = TerminalSnapshot.empty(2, line.cells.size).copy(lines = listOf(line, TerminalLine.empty(1, line.cells.size)))
        val state = TerminalScreenState(snapshot)
        val paint = paint()
        val selection = SelectionManager()
        selection.startSelection(0, 1, line.cells.size)
        selection.moveVisually(-1, 0, state, paint, 12f)
        assertEquals(2, selection.selectionRange!!.endCol)
        assertEquals("بت", selection.getSelectedText(snapshot, 0))
        selection.moveVisually(0, 1, state, paint, 12f)
        assertEquals(2, selection.selectionRange!!.endCol)
        assertEquals(1, selection.selectionRange!!.endRow)
    }

    @Test
    fun scriptFixturesRenderAndStayWithinTheirFootprint() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        for (text in listOf("سلام", "بِسْمِ", "لا", "क्षि", "র্ক", "தமிழ்", "ภาษาไทย", "မြန်မာ", "ខ្មែរ")) {
            val cells = cells("  $text  ")
            val paint = paint()
            val layout = paint.layout(cells, 12f)!!
            for (col in cells.indices) assertEquals(col, layout.logicalColumn(layout.visualColumn(col)))
            val bitmap = render(cells, paint)
            var ink = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) != android.graphics.Color.BLACK) {
                        assertTrue("$text escaped its span at $x", x >= 24 && x < (cells.size - 2) * 12)
                        ink++
                    }
                }
            }
            assertTrue("No glyphs for $text", ink > 0)
            bitmap.recycle()
        }
    }

    @Test
    fun requiredLigaturesAndConjunctsMatchPlatformRunRendering() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        for (text in listOf("لا", "क्षि", "র্ক")) {
            val paint = paint()
            val actual = render(cells(text), paint)
            val expected = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
            expected.eraseColor(android.graphics.Color.BLACK)
            val reference = TextPaint(paint).apply {
                color = android.graphics.Color.WHITE
                fontFeatureSettings = "'liga' 0, 'clig' 0"
            }
            val chars = text.toCharArray()
            val rtl = text == "لا"
            val advance = reference.getTextRunAdvances(chars, 0, chars.size, 0, chars.size, rtl, null, 0)
            val canvas = android.graphics.Canvas(expected)
            if (advance > actual.width) canvas.scale(actual.width / advance, 1f)
            canvas.drawTextRun(chars, 0, chars.size, 0, chars.size, 0f, 28f, rtl, reference)
            if (!actual.sameAs(expected)) {
                val glyphs = android.graphics.text.TextRunShaper.shapeTextRun(chars, 0, chars.size, 0, chars.size, 0f, 0f, rtl, reference)
                val details = (0 until glyphs.glyphCount()).joinToString { i ->
                    "id=${glyphs.getGlyphId(i)} x=${glyphs.getGlyphX(i)} font=${glyphs.getFont(i)}" +
                        if (Build.VERSION.SDK_INT >= 35) " weight=${glyphs.getWeightOverride(i)} italic=${glyphs.getItalicOverride(i)} bold=${glyphs.getFakeBold(i)}" else ""
                }
                fail("Required cluster differs from platform: $text advance=$advance $details")
            }
            actual.recycle()
            expected.recycle()
        }
    }

    @Test
    fun joiningChangesFormsWithoutMovingNeighborsOrBreakingAtColorChanges() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val original = cells("AببZ")
        val paint = paint()
        val joined = render(original, paint)
        val isolated = render(original, TextPaint(paint))
        assertFalse("Arabic must use neighboring context", joined.sameAs(isolated))
        for (y in 0 until joined.height) {
            for (x in 0 until joined.width) {
                if (x < 12 || x >= 36) assertEquals(isolated.getPixel(x, y), joined.getPixel(x, y))
            }
        }
        val recolored = PackedCells.from(original.mapIndexed { i, cell -> if (i == 1) cell.copy(fgColor = Color.Red) else cell })
        val colored = render(recolored, paint)
        for (y in 0 until joined.height) {
            for (x in 0 until joined.width) {
                // Skia applies color-dependent gamma to antialiased edges; compare ink geometry.
                assertEquals(joined.getPixel(x, y) != android.graphics.Color.BLACK, colored.getPixel(x, y) != android.graphics.Color.BLACK)
            }
        }
        joined.recycle()
        isolated.recycle()
        colored.recycle()
    }

    @Test
    fun nativeSnapshotsKeepCombiningMarksAndWideNeighbors() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val terminal = TerminalEmulatorFactory.create(initialRows = 2, initialCols = 40) as TerminalEmulatorImpl
        terminal.writeInput("日 بِسْمِ क्षि 🎉 Z".toByteArray())
        terminal.processPendingUpdates()
        val line = terminal.snapshot.value.lines[0]
        val before = line.text
        val paint = paint()
        val layout = paint.layout(line.cells, 12f)!!
        for (col in line.cells.indices) assertEquals(col, layout.logicalColumn(layout.visualColumn(col)))
        assertEquals(0, layout.visualColumn(0))
        assertEquals(1, layout.visualColumn(1))
        val image = render(line.cells, paint)
        image.recycle()
        assertEquals(before, line.cells.text())
    }

    @Test
    fun viewportEvictsScrollbackAndGeometryChangesInvalidate() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val first = TerminalLine(0, cells("سلام"))
        val state = TerminalScreenState(TerminalSnapshot.empty(1, 4).copy(lines = listOf(first)))
        val engine = TerminalShaping(paint())
        engine.viewport(state)
        val old = engine.layout(first.cells, 12f)
        val replacement = TerminalLine(0, cells("بتبت"))
        state.updateSnapshot(state.snapshot.copy(lines = listOf(replacement), sequenceNumber = 1))
        engine.viewport(state)
        assertEquals(0, engine.cachedRows)
        assertNotSame(old, engine.layout(replacement.cells, 12f))
        val beforeResize = engine.layout(replacement.cells, 12f)
        assertNotSame(beforeResize, engine.layout(replacement.cells, 8f))
    }

    private fun render(cells: PackedCells, paint: TextPaint): Bitmap {
        val bitmap = Bitmap.createBitmap(cells.size * 12, 40, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap()), Size(bitmap.width.toFloat(), 40f)) {
            drawLine(TerminalLine(0, cells), 0, 12f, 40f, 28f, paint, Paint(), Color.White, Color.Black, null)
        }
        return bitmap
    }
}
