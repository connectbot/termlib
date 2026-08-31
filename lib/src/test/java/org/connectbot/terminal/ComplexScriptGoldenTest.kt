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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComplexScriptGoldenTest {
    @Test
    fun everyShapedScriptMatchesItsGoldenImage() {
        assertEquals(
            ((-4)..-1).toSet() + (1..15),
            SPECIMENS.map { TerminalShaping.script(it.sample.codePointAt(0)) }.toSet(),
        )
        for (specimen in SPECIMENS) {
            val lines = specimenLines(specimen)
            capture(specimen.fileName, lines)
        }
    }

    @Test
    fun mixedScriptAndCellBoundaryEdgesMatchGoldenImage() {
        capture(
            "complex-script-boundaries.png",
            listOf(
                "Latin/CJK Aسلام日क्षिZ",
                "RTL neighbors: AسلامZ AܫܠܡܐZ AދިވެހިZ AߒߞߏZ",
                "Indic neighbors: Aक्षिZ Aর্কZ AਪੰਜਾਬੀZ AગુજરાતીZ",
                "More Indic: Aଓଡ଼ିଆZ Aதமிழ்Z AతెలుగుZ Aಕನ್ನಡZ",
                "South Indic: AമലയാളംZ AසිංහලZ",
                "SE Asian: AภาษาไทยZ AພາສາລາວZ Aမြန်မာZ Aខ្មែរZ",
                "Tibetan: Aབོད་ཡིགZ  Combining: e\u0301 بِسْمِ क्षि",
                "Wide + shaped: 表سلام語 क्षि日 ខ្មែរ表",
                "Box adjacency: ┌─سلام─┬──ខ្មែរ──┐",
                "               │ ܫܠܡܐ │ မြန်မာ │",
                "               └──────┴────────┘",
                "\u001B[31mس\u001B[32mل\u001B[34mا\u001B[33mم\u001B[0m colors | \u001B[1mक्षि\u001B[0m bold | \u001B[3mខ្មែរ\u001B[0m italic",
                "\u001B[4mภาษาไทย\u001B[0m underline | \u001B[7mမြန်မာ\u001B[0m reverse",
                "Lam-alef: لا لَا  ZWNJ: ل‌ا  ZWJ: ل‍ا",
            ),
        )
    }

    private fun specimenLines(specimen: Specimen): List<String> {
        val sample = specimen.sample
        val split = sample.offsetByCodePoints(0, sample.codePointCount(0, sample.length) / 2)
        val boxInterior = terminalColumns(sample) + 2
        return listOf(
            "${specimen.label}  U+${sample.codePointAt(0).toString(16).uppercase()}",
            "Plain: A${sample}Z 你好${sample}早安",
            "Boxes: ┌${"─".repeat(boxInterior)}┐",
            "       │ $sample │",
            "       └${"─".repeat(boxInterior)}┘",
            "Styles: \u001B[1m${sample}\u001B[0m  \u001B[3m${sample}\u001B[0m  \u001B[4m${sample}\u001B[0m",
            "Colors: \u001B[31m${sample.substring(0, split)}\u001B[36m${sample.substring(split)}\u001B[0m beside Latin/CJK",
            "Mixed: ${mixedNeighbor(specimen)}",
        )
    }

    /** Use the terminal parser itself so combining and wide-cell rules stay identical. */
    private fun terminalColumns(text: String): Int {
        val terminal = TerminalEmulatorFactory.create(initialRows = 1, initialCols = COLS) as TerminalEmulatorImpl
        terminal.writeInput(text.toByteArray(Charsets.UTF_8))
        terminal.processPendingUpdates()
        val cells = terminal.snapshot.value.lines.single().cells
        return (cells.indices.lastOrNull { !cells.blank(it) } ?: -1) + 1
    }

    private fun mixedNeighbor(specimen: Specimen): String = when (specimen.fileName) {
        "shaping-arabic.png", "shaping-syriac.png", "shaping-thaana.png", "shaping-nko.png" ->
            "Devanagari क्षि | ${specimen.sample} | Khmer ខ្មែរ"

        else -> "Arabic سلام | ${specimen.sample} | CJK 表語"
    }

    private fun capture(fileName: String, lines: List<String>) {
        val bitmap = render(lines)
        try {
            captureRoboImage(
                filePath = "src/test/roborazzi/$fileName",
                roborazziComposeOptions = RoborazziComposeOptions.Builder()
                    .addOption(RoborazziComposeSizeOption(bitmap.width, bitmap.height))
                    .build(),
            ) {
                Image(bitmap.asImageBitmap(), contentDescription = fileName)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun render(lines: List<String>): Bitmap {
        val terminal = TerminalEmulatorFactory.create(initialRows = lines.size, initialCols = COLS) as TerminalEmulatorImpl
        terminal.writeInput(lines.joinToString("\r\n").toByteArray(Charsets.UTF_8))
        terminal.processPendingUpdates()
        val paint = TerminalTextPaint(Typeface.MONOSPACE, TEXT_SIZE)
        val width = paint.measureText("M")
        val height = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
        val baseline = ceil(-paint.fontMetrics.ascent)
        val bitmap = Bitmap.createBitmap(ceil(COLS * width).toInt(), (height * lines.size).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLACK)
        val snapshot = terminal.snapshot.value
        val state = TerminalScreenState(snapshot)
        paint.viewport(state)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap.asImageBitmap()),
            Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
        ) {
            for (backgrounds in listOf(true, false)) {
                snapshot.lines.forEachIndexed { row, line ->
                    drawLine(
                        line = line,
                        aboveLine = snapshot.lines.getOrNull(row - 1),
                        belowLine = snapshot.lines.getOrNull(row + 1),
                        row = row,
                        charWidth = width,
                        charHeight = height,
                        charBaseline = baseline,
                        textPaint = paint,
                        underlinePaint = Paint(),
                        defaultFg = Color.White,
                        defaultBg = Color.Black,
                        selectionManager = null,
                        backgroundsOnly = backgrounds,
                    )
                }
            }
        }
        return bitmap
    }

    private data class Specimen(val fileName: String, val label: String, val sample: String)

    private companion object {
        const val COLS = 80
        const val TEXT_SIZE = 20f

        val SPECIMENS = listOf(
            Specimen("shaping-arabic.png", "Arabic", "سلام بِسْمِ لا"),
            Specimen("shaping-syriac.png", "Syriac", "ܫܠܡܐ ܥܠܡܐ"),
            Specimen("shaping-thaana.png", "Thaana", "ދިވެހިބަސް"),
            Specimen("shaping-nko.png", "NKo", "ߒߞߏ ߞߊ߲ߜߍ"),
            Specimen("shaping-devanagari.png", "Devanagari", "क्षि हिन्दी"),
            Specimen("shaping-bengali.png", "Bengali", "বাংলা র্ক"),
            Specimen("shaping-gurmukhi.png", "Gurmukhi", "ਪੰਜਾਬੀ"),
            Specimen("shaping-gujarati.png", "Gujarati", "ગુજરાતી"),
            Specimen("shaping-odia.png", "Odia", "ଓଡ଼ିଆ"),
            Specimen("shaping-tamil.png", "Tamil", "தமிழ்"),
            Specimen("shaping-telugu.png", "Telugu", "తెలుగు"),
            Specimen("shaping-kannada.png", "Kannada", "ಕನ್ನಡ"),
            Specimen("shaping-malayalam.png", "Malayalam", "മലയാളം"),
            Specimen("shaping-sinhala.png", "Sinhala", "සිංහල"),
            Specimen("shaping-thai.png", "Thai", "ภาษาไทย"),
            Specimen("shaping-lao.png", "Lao", "ພາສາລາວ"),
            Specimen("shaping-tibetan.png", "Tibetan", "བོད་ཡིག"),
            Specimen("shaping-myanmar.png", "Myanmar", "မြန်မာစာ"),
            Specimen("shaping-khmer.png", "Khmer", "ភាសាខ្មែរ"),
        )
    }
}
