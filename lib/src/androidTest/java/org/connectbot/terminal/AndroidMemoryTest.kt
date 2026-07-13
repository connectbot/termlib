/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMemoryTest {
    @Test
    fun rotationReusesScratchAndCanRedrawOnNewSurfaces() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val terminal = TerminalEmulatorFactory.create() as TerminalEmulatorImpl
        val scratch = terminal.cellBuffer()
        val paint = Paint().apply { textSize = 16f }
        val samples = mutableListOf<Pair<Long, Long>>()
        repeat(4) { epoch ->
            instrumentation.runOnMainSync {
                repeat(30) { iteration ->
                    val landscape = iteration % 2 == 0
                    terminal.resize(if (landscape) 24 else 40, if (landscape) 80 else 48)
                    terminal.writeInput("\u001B[Hsteady 🎉e\u0301 state".toByteArray())
                    terminal.processPendingUpdates()
                    val snapshot = terminal.snapshot.value
                    val bitmap = Bitmap.createBitmap(if (landscape) 800 else 480, if (landscape) 480 else 800, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val cells = snapshot.lines[0].cells
                    for (col in 0 until cells.size) {
                        if (cells.width(col) > 0) cells.draw(canvas, col, col * 10f, 20f, paint, cells.width(col) * 10f)
                    }
                    bitmap.recycle()
                    assertSame(scratch, terminal.cellBuffer())
                }
                terminal.resize(24, 80)
                terminal.processPendingUpdates()
            }
            instrumentation.waitForIdleSync()
            Runtime.getRuntime().gc()
            System.runFinalization()
            val runtime = Runtime.getRuntime()
            val javaBytes = runtime.totalMemory() - runtime.freeMemory()
            val nativeBytes = Debug.getNativeHeapAllocatedSize()
            samples.add(javaBytes to nativeBytes)
            Log.i("TermMemoryBenchmark", "rotation epoch=$epoch java=$javaBytes native=$nativeBytes")
        }
        assertEquals(CellData.BUFFER_BYTES, scratch.capacity())
        // Allow ART/runtime cache warmup and GC scheduling noise, but catch retained surfaces.
        val warmed = samples[1]
        val last = samples.last()
        assertTrue("Java heap kept growing: $samples", last.first <= warmed.first + 2 * 1024 * 1024)
        assertTrue("Native heap kept growing: $samples", last.second <= warmed.second + 2 * 1024 * 1024)
    }

    /** Optional capture lives in build/benchmark-assets, never in the published library. */
    @Test
    fun cacafireReplayOnArt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue(assets.list("")!!.contains("cacafire.bin"))
        val data = assets.open("cacafire.bin").use { it.readBytes() }
        val reads = JSONObject(assets.open("cacafire.json").bufferedReader().use { it.readText() }).getJSONArray("reads")
        val frames = mutableListOf<ByteArray>()
        var start = 0
        var end = 0
        var tick = -1
        for (i in 0 until reads.length()) {
            val read = reads.getJSONArray(i)
            val next = (read.getDouble(0) * 60).toInt()
            if (next != tick && end > start) {
                frames.add(data.copyOfRange(start, end))
                start = end
            }
            tick = next
            end += read.getInt(1)
        }
        if (end > start) frames.add(data.copyOfRange(start, end))
        val terminal = TerminalEmulatorFactory.create() as TerminalEmulatorImpl
        instrumentation.runOnMainSync {
            fun replay() {
                frames.forEach {
                    terminal.writeInput(it)
                    terminal.processPendingUpdates()
                }
            }
            repeat(2) { replay() }
            val times = mutableListOf<Long>()
            val allocations = mutableListOf<Long>()
            repeat(5) {
                val before = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
                val startTime = System.nanoTime()
                replay()
                times.add(System.nanoTime() - startTime)
                allocations.add(Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - before)
            }
            Log.i("TermMemoryBenchmark", "cacafire frames=${frames.size} ns/replay=${times.sorted()[2]} bytes/replay=${allocations.sorted()[2]}")
        }
    }
}
