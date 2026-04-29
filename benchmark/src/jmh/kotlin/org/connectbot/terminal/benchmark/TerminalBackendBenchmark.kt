/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal.benchmark

import org.connectbot.terminal.CellRun
import org.connectbot.terminal.CursorPosition
import org.connectbot.terminal.ScreenCell
import org.connectbot.terminal.TermRect
import org.connectbot.terminal.TerminalBackend
import org.connectbot.terminal.TerminalCallbacks
import org.connectbot.terminal.TerminalNative
import org.connectbot.terminal.TerminalProperty
import org.connectbot.terminal.wasm.TerminalWasm
import org.connectbot.terminal.wasm.WasmCallbacks
import org.connectbot.terminal.wasm.WasmScreenCell
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

private const val ROWS = 24
private const val COLS = 80

/** Generates [screenfulls] pages of mixed ASCII + ANSI SGR escape sequences. */
private fun buildVtInput(screenfulls: Int): ByteArray {
    val sb = StringBuilder()
    val proto = "The quick brown fox jumps over the lazy dog. "
    val line = proto.repeat((COLS / proto.length) + 1).substring(0, COLS - 2)
    repeat(screenfulls * ROWS) { row ->
        if (row % 2 == 0) sb.append("\u001B[1m") else sb.append("\u001B[0m")
        sb.append(line)
        sb.append("\r\n")
    }
    return sb.toString().toByteArray(Charsets.UTF_8)
}

private val noopNativeCallbacks = object : TerminalCallbacks {
    override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int) = 0
    override fun damageBatch(rects: IntArray, count: Int) = Unit
    override fun moverect(dest: TermRect, src: TermRect) = 0
    override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean) = 0
    override fun setTermProp(prop: Int, value: TerminalProperty) = 0
    override fun bell() = 0
    override fun pushScrollbackLine(cols: Int, cells: Array<ScreenCell>, softWrapped: Boolean) = 0
    override fun popScrollbackLine(cols: Int, cells: Array<ScreenCell>) = 0
    override fun onKeyboardInput(data: ByteArray) = 0
    override fun onOscSequence(command: Int, payload: String, cursorRow: Int, cursorCol: Int) = 0
}

private val noopWasmCallbacks = object : WasmCallbacks {
    override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int) = 0
    override fun moverect(
        dstStartRow: Int,
        dstEndRow: Int,
        dstStartCol: Int,
        dstEndCol: Int,
        srcStartRow: Int,
        srcEndRow: Int,
        srcStartCol: Int,
        srcEndCol: Int,
    ) = 0
    override fun moveCursor(row: Int, col: Int, oldRow: Int, oldCol: Int, visible: Boolean) = 0
    override fun setTermProp(prop: Int, type: Int, iVal: Int, str: String?) = 0
    override fun bell() = 0
    override fun pushScrollbackLine(cells: List<WasmScreenCell>, softWrapped: Boolean) = 0
    override fun popScrollbackLine(cols: Int): List<WasmScreenCell>? = null
    override fun onKeyboardOutput(data: ByteArray) = Unit
    override fun onOscSequence(command: Int, payload: String, cursorRow: Int, cursorCol: Int) = 0
}

// ---------------------------------------------------------------------------
// Shared backend state — one instance per trial, identified by @Param
// ---------------------------------------------------------------------------

@State(Scope.Benchmark)
open class BackendState {
    @Param("native", "wasm")
    lateinit var backend: String

    lateinit var term: TerminalBackend

    /** Pre-built input that fills exactly one screenful of text. */
    val oneScreenInput: ByteArray = buildVtInput(1)

    /** Large burst: 100 screenfuls. */
    val largeInput: ByteArray = buildVtInput(100)

    val run = CellRun()

    @Setup(Level.Trial)
    fun setUp() {
        term = when (backend) {
            "native" -> TerminalNative(noopNativeCallbacks)
            "wasm" -> TerminalWasm(ROWS, COLS, noopWasmCallbacks)
            else -> error("Unknown backend: $backend")
        }
        // Pre-populate the screen so cell-scan benchmarks have real content.
        term.writeInput(oneScreenInput)
    }

    @TearDown(Level.Trial)
    fun tearDown() = term.close()
}

// ---------------------------------------------------------------------------
// Benchmarks
// ---------------------------------------------------------------------------

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
open class WriteInputBenchmark {

    @Benchmark
    fun writeOneScreen(state: BackendState): Int = state.term.writeInput(state.oneScreenInput)

    @Benchmark
    fun writeLargeBurst(state: BackendState): Int = state.term.writeInput(state.largeInput)
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
open class CellScanBenchmark {

    /** Scans every cell run via getCellRun (one Wasm call per run). */
    @Benchmark
    fun scanAllCells(state: BackendState): Int {
        val run = state.run
        var total = 0
        for (row in 0 until ROWS) {
            var col = 0
            while (col < COLS) {
                val n = state.term.getCellRun(row, col, run)
                if (n <= 0) break
                total += n
                col += n
            }
        }
        return total
    }

    /** Scans every cell run via scanRow (one Wasm call per row). */
    @Benchmark
    fun scanAllCellsRowBatch(state: BackendState): Int {
        val run = state.run
        var total = 0
        for (row in 0 until ROWS) {
            state.term.scanRow(row, COLS, run) { total += it.runLength }
        }
        return total
    }

    /** Scans every cell run via scanAllRows (one Wasm call for the whole screen). */
    @Benchmark
    fun scanAllCellsScreenDump(state: BackendState): Int {
        val run = state.run
        var total = 0
        state.term.scanAllRows(ROWS, COLS, run, block = { total += it.runLength })
        return total
    }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
open class ResizeBenchmark {

    @Benchmark
    fun resizeToggle(state: BackendState): Int {
        state.term.resize(48, 132)
        return state.term.resize(ROWS, COLS)
    }
}
