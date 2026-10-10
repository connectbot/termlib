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
