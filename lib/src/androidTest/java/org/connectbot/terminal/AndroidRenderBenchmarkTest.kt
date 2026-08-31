/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Debug
import android.util.Log
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -Pandroid.testInstrumentationRunnerArguments.renderBenchmark=true. */
@RunWith(AndroidJUnit4::class)
class AndroidRenderBenchmarkTest {
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
