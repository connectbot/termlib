/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.Typeface
import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -Pandroid.testInstrumentationRunnerArguments.renderBenchmark=true. */
@RunWith(AndroidJUnit4::class)
class AndroidRenderBenchmarkTest {
    @Test
    fun nativeCellFetchOverhead() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("renderBenchmark") == "true")
        TerminalNative(NativeCallbackStub()).use { terminal ->
            terminal.resize(24, 80)
            terminal.writeInput(("سلام क्षि မြန်မာ ".repeat(4) + "\r\n").repeat(20).toByteArray())
            val buffer = CellData.buffer()
            val iterations = 2_000
            for (records in listOf(0, 1, 80, 768, 0)) {
                val requests = (records + 79) / 80
                for (row in 0 until requests) {
                    val header = row * 16
                    buffer.putInt(header, row)
                    buffer.putInt(header + 4, 0)
                    buffer.putInt(header + 8, minOf(80, records - row * 80))
                }
                fun sample(): Long {
                    var result = 0
                    val start = System.nanoTime()
                    repeat(iterations) { result += terminal.getCells(buffer, requests) }
                    val elapsed = System.nanoTime() - start
                    check(result == records * iterations)
                    return elapsed / iterations
                }
                repeat(3) { sample() }
                val samples = List(7) { sample() }.sorted()
                val result = "native-getCells records=$records requests=$requests median-ns/call=${samples[3]}"
                Log.i("TermRenderBenchmark", result)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("benchmark", result) })
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun wrappedParagraphLayout() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("renderBenchmark") == "true")
        val visibleRows = 24
        val totalRows = 120
        val columns = 80
        val letters = "بتثجحخدذرزسشصضطظ"
        val paragraph = List(totalRows) { row ->
            val text = "سلام".repeat(19) + letters[row / letters.length] + letters[row % letters.length] + "00"
            val cells = PackedCells.from(text.map { TerminalLine.Cell(it, fgColor = Color.White, bgColor = Color.Black) })
            TerminalLine(row, cells, softWrapped = row < totalRows - 1)
        }
        val cropped = TerminalScreenState(
            TerminalSnapshot.empty(visibleRows, columns).copy(
                lines = paragraph.takeLast(visibleRows),
                scrollback = paragraph.dropLast(visibleRows),
            ),
        )
        val full = TerminalScreenState(TerminalSnapshot.empty(totalRows, columns).copy(lines = paragraph))
        val engine = TerminalShaping(TerminalTextPaint(Typeface.MONOSPACE, 20f), byteLimit = 4 * 1024 * 1024)
        data class Sample(val nanos: Long, val allocatedBytes: Long)

        val croppedSamples = mutableListOf<Sample>()
        val fullSamples = mutableListOf<Sample>()
        fun measure(state: TerminalScreenState, row: Int, expectedLayouts: Int): Sample {
            engine.clear()
            val count = engine.shapeCount
            val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            val start = System.nanoTime()
            val layout = engine.layout(state, row, 12f)
            val elapsed = System.nanoTime() - start
            val bytes = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocated
            check(layout != null && engine.shapeCount - count == expectedLayouts)
            return Sample(elapsed, bytes)
        }
        repeat(3) {
            measure(cropped, 0, visibleRows)
            measure(full, totalRows - visibleRows, totalRows)
        }
        repeat(5) {
            croppedSamples.add(measure(cropped, 0, visibleRows))
            fullSamples.add(measure(full, totalRows - visibleRows, totalRows))
            fullSamples.add(measure(full, totalRows - visibleRows, totalRows))
            croppedSamples.add(measure(cropped, 0, visibleRows))
        }
        fun median(samples: List<Sample>, value: (Sample) -> Long): Long = samples.map(value).sorted()[samples.size / 2]

        val result = "wrapped-paragraph rows=$totalRows cropped=$visibleRows " +
            "cropped-ns=${median(croppedSamples, Sample::nanos)} full-ns=${median(fullSamples, Sample::nanos)} " +
            "cropped-bytes=${median(croppedSamples, Sample::allocatedBytes)} full-bytes=${median(fullSamples, Sample::allocatedBytes)}"
        Log.i("TermRenderBenchmark", result)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("benchmark", result) })
    }

    /** Compare box and block workloads against ASCII through the production renderer. */
    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun boxAndBlockRenderingOverhead() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("renderBenchmark") == "true")
        val rows = args.getString("renderRows")?.toInt() ?: 24
        val cols = args.getString("renderCols")?.toInt() ?: 80
        val workloads = linkedMapOf(
            "ascii" to listOf("Terminal text 123 ", "Updated text 456 "),
            "boxes" to listOf((0x2500..0x259F).map(Int::toChar).joinToString(""), (0x2500..0x259F).reversed().map(Int::toChar).joinToString("")),
            "shades" to listOf("░▒▓", "▓░▒"),
            "mixed" to listOf("┌─┼┐ Terminal ░▒▓ 123 ", "╔═╬╗ Updated ▓░▒ 456 "),
        )
        val terminal = TerminalEmulatorFactory.create(initialRows = rows, initialCols = cols) as TerminalEmulatorImpl
        val snapshots = workloads.mapValues { (_, texts) ->
            texts.map { text ->
                val content = buildString {
                    append("\u001b[2J")
                    val repeated = text.repeat((rows * cols) / text.length + 1)
                    for (row in 0 until rows) {
                        append("\u001b[${row + 1};1H")
                        append(repeated.substring(row * cols, (row + 1) * cols))
                    }
                }
                terminal.writeInput(content.toByteArray())
                terminal.processPendingUpdates()
                terminal.snapshot.value
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (hardware in listOf(false, true)) {
                for (changing in listOf(false, true)) {
                    val results = linkedMapOf<String, Long>()
                    for ((name, frames) in snapshots) {
                        val state = TerminalScreenState(frames[0])
                        val paint = TerminalTextPaint(Typeface.MONOSPACE, 20f)
                        val underline = Paint()
                        val scope = CanvasDrawScope()
                        val bitmap = Bitmap.createBitmap(cols * 12, rows * 24, Bitmap.Config.ARGB_8888)
                        val softwareCanvas = androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap())
                        val node = RenderNode("render-comparison")
                        node.setPosition(0, 0, bitmap.width, bitmap.height)
                        var tick = 0
                        fun frame() {
                            state.updateSnapshot(frames[if (changing) tick++ % frames.size else 0])
                            paint.viewport(state)
                            val canvas = if (hardware) androidx.compose.ui.graphics.Canvas(node.beginRecording()) else softwareCanvas
                            try {
                                scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
                                    drawRect(Color.Black)
                                    for (row in 0 until rows) {
                                        val line = state.getVisibleLine(row)
                                        drawLine(
                                            line, row, 12f, 24f, 20f, paint, underline, Color.White, Color.Black, null,
                                            aboveLine = if (row > 0) state.getVisibleLine(row - 1) else null,
                                            belowLine = if (row + 1 < rows) state.getVisibleLine(row + 1) else null,
                                            shapedLine = paint.layout(state, row, 12f),
                                        )
                                    }
                                }
                            } finally {
                                if (hardware) node.endRecording()
                            }
                        }
                        repeat(30) { frame() }
                        val samples = List(9) {
                            val start = System.nanoTime()
                            repeat(20) { frame() }
                            (System.nanoTime() - start) / 20
                        }.sorted()
                        results[name] = samples[4]
                        node.discardDisplayList()
                        bitmap.recycle()
                        paint.clearShaping()
                    }
                    for ((name, nanos) in results) {
                        val ratio = nanos.toDouble() / results.getValue("ascii")
                        val result = "${rows}x$cols ${if (hardware) "recording" else "software"} ${if (changing) "changing" else "static"} $name ns/frame=$nanos ratio=$ratio"
                        Log.i("TermRenderBenchmark", result)
                        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("benchmark", result) })
                    }
                }
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun shadedBlocksOnRecordingCanvas() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("renderBenchmark") == "true")
        val revision = InstrumentationRegistry.getArguments().getString("revision") ?: "unknown"
        val rows = InstrumentationRegistry.getArguments().getString("shadeRows")?.toInt() ?: 48
        val cols = InstrumentationRegistry.getArguments().getString("shadeCols")?.toInt() ?: 80
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val renderer = TerminalBoxDrawing()
            val node = RenderNode("terminal-shades")
            node.setPosition(0, 0, cols * 12, rows * 20)
            fun frame() {
                val canvas = node.beginRecording(cols * 12, rows * 20)
                try {
                    for (row in 0 until rows) {
                        for (col in 0 until cols) {
                            renderer.draw(canvas, "░▒▓"[(row + col) % 3], col * 12f, row * 20f, 12f, 20f, -1, 20f)
                        }
                    }
                } finally {
                    node.endRecording()
                }
            }
            repeat(5) { frame() }
            val samples = List(15) {
                val start = System.nanoTime()
                frame()
                System.nanoTime() - start
            }.sorted()
            val result = "$revision ${rows}x$cols shades hardware-recording median-ns/frame=${samples[7]} max-ns/frame=${samples.last()}"
            Log.i("TermRenderBenchmark", result)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("benchmark", result) })
            node.discardDisplayList()
        }
    }

    @Test
    fun repeatedCanvasRendering() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("renderBenchmark") == "true")
        val terminal = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80) as TerminalEmulatorImpl
        for ((name, text) in mapOf(
            "ascii" to "Terminal text 123 ",
            "unicode" to "表語 🎉 é ⚠️ ",
            "arabic" to "سلام بِسْمِ لا ",
            "indic" to "क्षि র্ক தமிழ் ",
        )) {
            terminal.writeInput(("\u001B[2J\u001B[H" + text.repeat(150)).toByteArray())
            terminal.processPendingUpdates()
            val rows = terminal.snapshot.value.lines
            val bitmap = Bitmap.createBitmap(960, 576, Bitmap.Config.ARGB_8888)
            val canvas = androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap())
            val paint = TerminalTextPaint(Typeface.MONOSPACE, 20f)
            // Keep this harness buildable against the pre-shaping baseline too.
            val state = TerminalScreenState(terminal.snapshot.value)
            paint.javaClass.methods.firstOrNull { it.name == "viewport" }?.invoke(paint, state)
            val underline = Paint()
            val scope = CanvasDrawScope()
            fun frame() {
                scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(960f, 576f)) {
                    drawRect(Color.Black)
                    for (row in rows.indices) {
                        drawLine(rows[row], row, 12f, 24f, 20f, paint, underline, Color.White, Color.Black, null)
                    }
                }
            }
            repeat(30) { frame() }
            val allocated = LongArray(5)
            val samples = List(5) { sample ->
                val before = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
                val start = System.nanoTime()
                repeat(100) { frame() }
                val elapsed = (System.nanoTime() - start) / 100
                allocated[sample] = (Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - before) / 100
                elapsed
            }
            Log.i("TermRenderBenchmark", "$name ns/frame=${samples.sorted()[2]} bytes/frame=${allocated.sorted()[2]}")
            bitmap.recycle()
        }
    }
}
