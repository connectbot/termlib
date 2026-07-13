/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
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
        for ((name, text) in mapOf("ascii" to "Terminal text 123 ", "unicode" to "表語 🎉 é ⚠️ ")) {
            terminal.writeInput(("\u001B[2J\u001B[H" + text.repeat(150)).toByteArray())
            terminal.processPendingUpdates()
            val rows = terminal.snapshot.value.lines
            val bitmap = Bitmap.createBitmap(960, 576, Bitmap.Config.ARGB_8888)
            val canvas = androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap())
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize = 20f
            }
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
            val samples = List(5) {
                val start = System.nanoTime()
                repeat(100) { frame() }
                (System.nanoTime() - start) / 100
            }
            Log.i("TermRenderBenchmark", "$name ns/frame=${samples.sorted()[2]}")
            bitmap.recycle()
        }
    }
}
