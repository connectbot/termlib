/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziComposeSizeOption
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoxDrawingGoldenTest {
    private fun render(lines: List<String>, size: Float, selectedRow: Int = -1): Bitmap {
        val cols = 64
        val terminal = TerminalEmulatorFactory.create(initialRows = lines.size + 1, initialCols = cols) as TerminalEmulatorImpl
        terminal.writeInput(lines.joinToString("\r\n").toByteArray())
        terminal.processPendingUpdates()
        val paint = TerminalTextPaint(Typeface.MONOSPACE, size)
        val width = paint.measureText("M")
        val height = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
        val baseline = ceil(-paint.fontMetrics.ascent)
        val bitmap = Bitmap.createBitmap(ceil(cols * width).toInt(), (height * lines.size).toInt(), Bitmap.Config.ARGB_8888)
        val selection = SelectionManager().apply {
            if (selectedRow >= 0) {
                startSelection(selectedRow, 0, cols)
                updateSelection(selectedRow, cols - 1)
            }
        }
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            // Same two-pass foreground/background renderer as Terminal and magnifier.
            for (backgrounds in listOf(true, false)) {
                terminal.snapshot.value.lines.take(lines.size).forEachIndexed { row, line ->
                    drawLine(
                        line = line,
                        aboveLine = terminal.snapshot.value.lines.getOrNull(row - 1),
                        belowLine = terminal.snapshot.value.lines.getOrNull(row + 1),
                        row = row,
                        charWidth = width,
                        charHeight = height,
                        charBaseline = baseline,
                        textPaint = paint,
                        underlinePaint = Paint(),
                        defaultFg = Color.White,
                        defaultBg = Color.Black,
                        selectionManager = selection,
                        backgroundsOnly = backgrounds,
                    )
                }
            }
        }
        return bitmap
    }

    @Test
    fun allCharactersAndConnectedBoxes() {
        val lines = buildList {
            add("U+2500-259F: all 160 characters (hex row, +0..+F)")
            add("     0 1 2 3 4 5 6 7 8 9 A B C D E F")
            for (base in 0x2500..0x2590 step 16) {
                add(base.toString(16).uppercase() + " " + (base..base + 15).joinToString(" ") { it.toChar().toString() })
            }
            add("Light / heavy / double / rounded / dashed")
            add("┌───┬───┐ ┏━━━┳━━━┓ ╔═══╦═══╗ ╭───────╮ ┌┄┄┄┄┄┄┄┐")
            add("│   │   │ ┃   ┃   ┃ ║   ║   ║ │       │ ┆       ┆")
            add("├───┼───┤ ┣━━━╋━━━┫ ╠═══╬═══╣ │       │ ├┄┄┄┄┄┄┄┤")
            add("│   │   │ ┃   ┃   ┃ ║   ║   ║ │       │ ┆       ┆")
            add("└───┴───┘ ┗━━━┻━━━┛ ╚═══╩═══╝ ╰───────╯ └┄┄┄┄┄┄┄┘")
            add("Mixed weights / single-double junctions")
            add("┍━━━┯━━━┑ ┎───┰───┒ ╒═══╤═══╕ ╓───╥───╖")
            add("│   │   │ ┃   ┃   ┃ │   │   │ ║   ║   ║")
            add("┝━━━┿━━━┥ ┠───╂───┨ ╞═══╪═══╡ ╟───╫───╢")
            add("│   │   │ ┃   ┃   ┃ │   │   │ ║   ║   ║")
            add("┕━━━┷━━━┙ ┖───┸───┚ ╘═══╧═══╛ ╙───╨───╜")
            add("Diagonals / half-lines / dash counts")
            add("  ╱╲    ╲╱    ╳╳   ╶──╼━━╸   ┌╌╌╌┐ ┏┅┅┅┓ ┌┈┈┈┐")
            add(" ╱  ╲   ╱╲    ╳╳   ╺━━╾──╴   ╎   ╎ ┇   ┇ ┊   ┊")
            add(" ╲  ╱                        └╌╌╌┘ ┗┅┅┅┛ └┈┈┈┘")
            add("  ╲╱")
            add("Full-block O / shades: no blank row boundaries")
            add(" ██████   ░░░░░░░░ ▒▒▒▒▒▒▒▒ ▓▓▓▓▓▓▓▓ ████████")
            repeat(3) { add("██    ██  ░░░░░░░░ ▒▒▒▒▒▒▒▒ ▓▓▓▓▓▓▓▓ ████████") }
            add(" ██████   ░░░░░░░░ ▒▒▒▒▒▒▒▒ ▓▓▓▓▓▓▓▓ ████████")
            add("Fractions / quadrants (top and bottom meet)")
            add("▁▂▃▄▅▆▇█ ▉▊▋▌▍▎▏▐ ▖▗▘▙▚▛▜▝▞▟ ▄▄▄▄ ▟▙")
            add("▁▂▃▄▅▆▇█ ▉▊▋▌▍▎▏▐ ▖▗▘▙▚▛▜▝▞▟ ▀▀▀▀ ▜▛")
            add("Selected:")
            add("┌──┬──┐ ╔══╦══╗ █▀▄▌▐ ░░░▒▒▒▓▓▓")
            add("Reverse:")
            add("\u001B[7m┌──┬──┐ ╔══╦══╗ █▀▄▌▐ ░░░▒▒▒▓▓▓\u001B[0m")
            add("Bold + italic retain geometric shapes:")
            add("\u001B[1;3m┌──┬──┐ ╔══╦══╗ █▀▄▌▐ ░░░▒▒▒▓▓▓\u001B[0m")
        }
        val covered = lines.joinToString("").filter { it in '\u2500'..'\u259F' }.toSet()
        assertEquals(160, covered.size)
        for (size in listOf(13f, 16f, 21f)) {
            val bitmap = render(lines, size, lines.indexOf("Selected:") + 1)
            captureRoboImage(
                filePath = "src/test/roborazzi/box-drawing-${size.toInt()}.png",
                roborazziComposeOptions = RoborazziComposeOptions.Builder()
                    .addOption(RoborazziComposeSizeOption(bitmap.width, bitmap.height)).build(),
            ) {
                Image(bitmap.asImageBitmap(), contentDescription = "Box drawing specimen")
            }
        }
    }

    private fun glyph(ch: Char, width: Int = 12, height: Int = 20): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK)
        TerminalBoxDrawing().draw(android.graphics.Canvas(bitmap), ch, 0f, 0f, width.toFloat(), height.toFloat(), android.graphics.Color.WHITE, 16f)
        return bitmap
    }

    @Test
    fun fullBlocksHaveNoGapsAtFractionalColumnWidths() {
        for (width in listOf(7f, 8.25f, 9.5f)) {
            for (height in listOf(13f, 16f, 21f)) {
                val bitmap = Bitmap.createBitmap(kotlin.math.round(width * 8).toInt(), (height * 5).toInt(), Bitmap.Config.ARGB_8888)
                val renderer = TerminalBoxDrawing()
                for (row in 0..4) {
                    for (col in 0..7) {
                        renderer.draw(android.graphics.Canvas(bitmap), '█', col * width, row * height, width, height, -1, 16f)
                    }
                }
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) assertEquals("gap at $x,$y", -1, bitmap.getPixel(x, y))
            }
        }
    }

    @Test
    fun complementaryRegionsAndShadeCoverage() {
        for (width in listOf(7, 12, 17)) {
            for (height in listOf(13, 20, 25)) {
                for ((a, b) in listOf('▀' to '▄', '▌' to '▐', '▚' to '▞')) {
                    val first = glyph(a, width, height)
                    val second = glyph(b, width, height)
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            assertTrue("complements $a$b at $x,$y", (first.getPixel(x, y) == -1) xor (second.getPixel(x, y) == -1))
                        }
                    }
                }
            }
        }
        val counts = listOf('░', '▒', '▓').map { ch ->
            val bitmap = glyph(ch, 12, 20)
            (0 until 20).sumOf { y -> (0 until 12).count { x -> bitmap.getPixel(x, y) == -1 } }
        }
        assertEquals(listOf(60, 120, 180), counts)
    }

    @Test
    fun connectedBoxesHaveMatchingEdges() {
        for (box in listOf("┌─┐│└─┘", "┏━┓┃┗━┛", "╔═╗║╚═╝")) {
            val corner = glyph(box[0])
            val horizontal = glyph(box[1])
            val vertical = glyph(box[3])
            for (y in 0 until 20) assertEquals(corner.getPixel(11, y), horizontal.getPixel(0, y))
            for (x in 0 until 12) assertEquals(corner.getPixel(x, 19), vertical.getPixel(x, 0))
        }
    }

    @Test
    fun everySupportedCharacterProducesInk() {
        for (cp in 0x2500..0x259F) {
            val bitmap = glyph(cp.toChar())
            assertTrue("U+${cp.toString(16)} is blank", (0 until 20).any { y -> (0 until 12).any { x -> bitmap.getPixel(x, y) == -1 } })
        }
    }

    @Test
    fun quadrupleDashesKeepTheirGapsAtSmallSizes() {
        val bitmap = glyph('┈', 8, 16)
        val occupied = (0 until 8).map { x -> (0 until 16).any { y -> bitmap.getPixel(x, y) == -1 } }
        assertEquals(4, occupied.count { it })
        assertEquals(4, occupied.indices.count { occupied[it] && (it == 0 || !occupied[it - 1]) })
    }

    @Test
    fun adjacentDashedCellsKeepAContinuousPhysicalCadence() {
        val bitmap = Bitmap.createBitmap(24, 20, Bitmap.Config.ARGB_8888)
        val renderer = TerminalBoxDrawing()
        renderer.draw(android.graphics.Canvas(bitmap), '┄', 0f, 0f, 12f, 20f, -1, 16f)
        renderer.draw(android.graphics.Canvas(bitmap), '┄', 12f, 0f, 12f, 20f, -1, 16f)
        val occupied = (0 until 24).map { x -> (0 until 20).any { y -> bitmap.getPixel(x, y) == -1 } }
        assertEquals("110011001100110011001100", occupied.joinToString("") { if (it) "1" else "0" })
    }

    @Test
    fun stipplesStayContinuousAcrossOddSizedCells() {
        for (ch in listOf('░', '▒', '▓')) {
            val bitmap = Bitmap.createBitmap(27, 39, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
            val renderer = TerminalBoxDrawing()
            for (row in 0..2) {
                for (col in 0..2) {
                    renderer.draw(android.graphics.Canvas(bitmap), ch, col * 9f, row * 13f, 9f, 13f, -1, 16f)
                }
            }
            for (y in 0 until 39) {
                for (x in 0 until 27) {
                    val expected = when (ch) {
                        '░' -> x % 2 == 0 && y % 2 == 0
                        '▒' -> (x + y) % 2 == 0
                        else -> x % 2 != 0 || y % 2 != 0
                    }
                    assertEquals("$ch at $x,$y", expected, bitmap.getPixel(x, y) == -1)
                }
            }
        }
    }
}
