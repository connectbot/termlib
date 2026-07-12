/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.management.ManagementFactory

/** Opt in with TERMLIB_BENCHMARK=1; compare medians on the same host/JVM. */
@RunWith(AndroidJUnit4::class)
class BoundaryBenchmarkTest {
    @Test
    fun renderingScrollingAndOsc() {
        assumeTrue(System.getenv("TERMLIB_BENCHMARK") == "1")
        val allocations = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val threadId = Thread.currentThread().id
        val workloads = linkedMapOf(
            "ascii" to listOf(("\u001B[H" + "abc def ".repeat(400)).toByteArray()),
            "unicode" to listOf(("\u001B[H" + "é日🎉e\u0301 ".repeat(200)).toByteArray()),
            "scroll" to listOf(("a colorful scrolling line 1234567890\r\n".repeat(8)).toByteArray()),
            "osc" to listOf("\u001B]1337;AddAnnotation=é".toByteArray(), "日🎉\u0007".toByteArray()),
        )
        for ((name, chunks) in workloads) {
            val terminal = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 160) as TerminalEmulatorImpl
            fun iteration() {
                chunks.forEach { terminal.writeInput(it) }
                terminal.processPendingUpdates()
            }
            repeat(100) { iteration() }
            val times = mutableListOf<Long>()
            val bytes = mutableListOf<Long>()
            repeat(5) {
                val allocated = allocations.getThreadAllocatedBytes(threadId)
                val start = System.nanoTime()
                repeat(100) { iteration() }
                times.add((System.nanoTime() - start) / 100)
                bytes.add((allocations.getThreadAllocatedBytes(threadId) - allocated) / 100)
            }
            println("BOUNDARY_BENCH $name ns/op=${times.sorted()[2]} bytes/op=${bytes.sorted()[2]}")
        }
    }

    /**
     * Capture with `python3 benchmark/capture-cacafire.py`, then set
     * TERMLIB_CACAFIRE=/tmp/termlib-cacafire when invoking this test.
     */
    @Test
    fun cacafireReplay() {
        val capture = System.getenv("TERMLIB_CACAFIRE") ?: ""
        assumeTrue(capture.isNotEmpty())
        val data = File("$capture.bin").readBytes()
        val reads = JSONObject(File("$capture.json").readText()).getJSONArray("reads")
        // Snapshot at 60 Hz of recorded time, independently of replay speed.
        val frames = mutableListOf<ByteArray>()
        var start = 0
        var end = 0
        var tick = -1
        for (i in 0 until reads.length()) {
            val read = reads.getJSONArray(i)
            val nextTick = (read.getDouble(0) * 60).toInt()
            if (nextTick != tick && end > start) {
                frames.add(data.copyOfRange(start, end))
                start = end
            }
            tick = nextTick
            end += read.getInt(1)
        }
        if (end > start) frames.add(data.copyOfRange(start, end))
        val terminal = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80) as TerminalEmulatorImpl
        val allocations = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val threadId = Thread.currentThread().id
        fun replay() {
            frames.forEach {
                terminal.writeInput(it)
                terminal.processPendingUpdates()
            }
        }
        repeat(2) { replay() }
        val times = mutableListOf<Long>()
        val bytes = mutableListOf<Long>()
        val transferCalls = terminal.transferCalls
        val transferBytes = terminal.transferBytes
        repeat(5) {
            val allocated = allocations.getThreadAllocatedBytes(threadId)
            val startTime = System.nanoTime()
            replay()
            times.add(System.nanoTime() - startTime)
            bytes.add(allocations.getThreadAllocatedBytes(threadId) - allocated)
        }
        println("BOUNDARY_BENCH cacafire bytes=${data.size} frames=${frames.size} ns/replay=${times.sorted()[2]} bytes/replay=${bytes.sorted()[2]}")
        println("BOUNDARY_TRANSFER cacafire calls/replay=${(terminal.transferCalls - transferCalls) / 5} bytes/replay=${(terminal.transferBytes - transferBytes) / 5}")
    }
}
