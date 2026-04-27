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
package org.connectbot.terminal.wasm

import com.dylibso.chicory.runtime.ByteArrayMemory
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasi.WasiPreview1
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.ValType
import org.connectbot.terminal.CellRun
import org.connectbot.terminal.TerminalBackend
import org.connectbot.terminal.wasm.generated.LibVterm

/**
 * Callbacks invoked by [TerminalWasm] when terminal state changes.
 */
interface WasmCallbacks {
    fun damage(
        startRow: Int,
        endRow: Int,
        startCol: Int,
        endCol: Int,
    ): Int

    fun moverect(
        dstStartRow: Int,
        dstEndRow: Int,
        dstStartCol: Int,
        dstEndCol: Int,
        srcStartRow: Int,
        srcEndRow: Int,
        srcStartCol: Int,
        srcEndCol: Int,
    ): Int

    fun moveCursor(
        row: Int,
        col: Int,
        oldRow: Int,
        oldCol: Int,
        visible: Boolean,
    ): Int

    /**
     * @param type  1=bool, 2=int, 3=string, 4=color(rgb packed as 0xRRGGBB)
     * @param iVal  bool/int/color value (type 1, 2, 4)
     * @param str   string value (type 3), null for other types
     */
    fun setTermProp(
        prop: Int,
        type: Int,
        iVal: Int,
        str: String?,
    ): Int

    fun bell(): Int

    fun pushScrollbackLine(
        cells: List<WasmScreenCell>,
        softWrapped: Boolean,
    ): Int

    fun popScrollbackLine(cols: Int): List<WasmScreenCell>?

    fun onKeyboardOutput(data: ByteArray)

    fun onOscSequence(
        command: Int,
        payload: String,
        cursorRow: Int,
        cursorCol: Int,
    ): Int
}

/**
 * A single terminal cell exchanged through the Wasm boundary.
 *
 * Layout (36 bytes, matches PackedCell in vterm_wasm.c):
 *   0-23   chars[0..5]  uint32 each (Unicode codepoints, 0 = absent)
 *   24     fgRed
 *   25     fgGreen
 *   26     fgBlue
 *   27     bgRed
 *   28     bgGreen
 *   29     bgBlue
 *   30     attrs  bit0=bold, bit1=italic, bit2=reverse, bit3=strike, bit4=blink
 *   31     underline (0-4)
 *   32     width (1 or 2)
 *   33-35  padding
 */
data class WasmScreenCell(
    val chars: IntArray,
    val fgRed: Int,
    val fgGreen: Int,
    val fgBlue: Int,
    val bgRed: Int,
    val bgGreen: Int,
    val bgBlue: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val reverse: Boolean = false,
    val strike: Boolean = false,
    val blink: Boolean = false,
    val underline: Int = 0,
    val width: Int = 1,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WasmScreenCell) return false
        return chars.contentEquals(other.chars) &&
            fgRed == other.fgRed && fgGreen == other.fgGreen && fgBlue == other.fgBlue &&
            bgRed == other.bgRed && bgGreen == other.bgGreen && bgBlue == other.bgBlue &&
            bold == other.bold && italic == other.italic && reverse == other.reverse &&
            strike == other.strike && blink == other.blink &&
            underline == other.underline && width == other.width
    }

    override fun hashCode(): Int = chars.contentHashCode()
}

private const val PACKED_CELL_SIZE = 36
private const val MAX_CHARS_PER_CELL = 6
private const val IMPORT_MODULE = "vterm_cb"

class TerminalWasm(
    private val rows: Int,
    private val cols: Int,
    private val callbacks: WasmCallbacks,
) : TerminalBackend {
    private val instance: Instance = buildInstance()
    private val memory get() = instance.memory()

    private val fnInit = instance.export("vterm_wasm_init")
    private val fnFree = instance.export("vterm_wasm_free")
    private val fnWriteInput = instance.export("vterm_wasm_write_input")
    private val fnResize = instance.export("vterm_wasm_resize")
    private val fnDispatchKey = instance.export("vterm_wasm_dispatch_key")
    private val fnDispatchChar = instance.export("vterm_wasm_dispatch_char")
    private val fnGetCellRun = instance.export("vterm_wasm_get_cell_run")
    private val fnSetPalette = instance.export("vterm_wasm_set_palette_colors")
    private val fnSetDefaultColors = instance.export("vterm_wasm_set_default_colors")
    private val fnGetLineCont = instance.export("vterm_wasm_get_line_continuation")
    private val fnSetBoldHB = instance.export("vterm_wasm_set_bold_highbright")
    private val fnAlloc = instance.export("vterm_wasm_alloc")
    private val fnDealloc = instance.export("vterm_wasm_dealloc")
    private val fnCellRunBuf = instance.export("vterm_wasm_cell_run_buf")
    private val fnGetRow = instance.export("vterm_wasm_get_row")
    private val fnGetAllRows = instance.export("vterm_wasm_get_all_rows")
    private val fnScreenBuf = instance.export("vterm_wasm_screen_buf")

    private val cellRunBufPtr: Int = fnCellRunBuf.apply().first().toInt()
    private val screenBufPtr: Int = fnScreenBuf.apply().first().toInt()

    init {
        val rc = fnInit.apply(rows.toLong(), cols.toLong()).first().toInt()
        check(rc == 0) { "vterm_wasm_init failed: $rc" }
    }

    override fun writeInput(
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val ptr = wasmAlloc(length)
        try {
            memory.write(ptr, data, offset, length)
            return fnWriteInput.apply(ptr.toLong(), length.toLong()).first().toInt()
        } finally {
            wasmFree(ptr)
        }
    }

    override fun resize(
        rows: Int,
        cols: Int,
    ): Int = fnResize.apply(rows.toLong(), cols.toLong()).first().toInt()

    override fun dispatchKey(
        modifiers: Int,
        key: Int,
    ): Boolean = fnDispatchKey.apply(modifiers.toLong(), key.toLong()).first().toInt() != 0

    override fun dispatchCharacter(
        modifiers: Int,
        codepoint: Int,
    ): Boolean = fnDispatchChar.apply(modifiers.toLong(), codepoint.toLong()).first().toInt() != 0

    override fun getCellRun(
        row: Int,
        col: Int,
        run: CellRun,
    ): Int {
        val count =
            fnGetCellRun
                .apply(
                    row.toLong(),
                    col.toLong(),
                    cellRunBufPtr.toLong(),
                ).first()
                .toInt()
        if (count <= 0) return 0
        fillCellRun(run, cellRunBufPtr, count)
        return count
    }

    override fun scanRow(
        row: Int,
        cols: Int,
        run: CellRun,
        block: (CellRun) -> Unit,
    ) {
        val totalBytes = cols * PACKED_CELL_SIZE
        fnGetRow.apply(row.toLong(), cellRunBufPtr.toLong())
        val buf = memory.readBytes(cellRunBufPtr, totalBytes)
        var col = 0
        while (col < cols) {
            val startCol = col
            val o0 = col * PACKED_CELL_SIZE
            val refAttrs = buf[o0 + 30].toInt() and 0xFF
            val refFgR = buf[o0 + 24].toInt() and 0xFF
            val refFgG = buf[o0 + 25].toInt() and 0xFF
            val refFgB = buf[o0 + 26].toInt() and 0xFF
            val refBgR = buf[o0 + 27].toInt() and 0xFF
            val refBgG = buf[o0 + 28].toInt() and 0xFF
            val refBgB = buf[o0 + 29].toInt() and 0xFF
            val refUnder = buf[o0 + 31].toInt() and 0xFF
            var runEnd = col + 1
            while (runEnd < cols) {
                val o = runEnd * PACKED_CELL_SIZE
                if ((buf[o + 30].toInt() and 0xFF) != refAttrs ||
                    (buf[o + 24].toInt() and 0xFF) != refFgR ||
                    (buf[o + 25].toInt() and 0xFF) != refFgG ||
                    (buf[o + 26].toInt() and 0xFF) != refFgB ||
                    (buf[o + 27].toInt() and 0xFF) != refBgR ||
                    (buf[o + 28].toInt() and 0xFF) != refBgG ||
                    (buf[o + 29].toInt() and 0xFF) != refBgB ||
                    (buf[o + 31].toInt() and 0xFF) != refUnder
                ) {
                    break
                }
                runEnd++
            }
            val count = runEnd - startCol
            fillCellRunFromBuf(run, buf, startCol, count)
            block(run)
            col = runEnd
        }
    }

    override fun scanAllRows(
        rows: Int,
        cols: Int,
        run: CellRun,
        rowStart: (Int) -> Unit,
        block: (CellRun) -> Unit,
    ) {
        val totalBytes = rows * cols * PACKED_CELL_SIZE
        fnGetAllRows.apply(screenBufPtr.toLong())
        val buf = memory.readBytes(screenBufPtr, totalBytes)
        for (row in 0 until rows) {
            rowStart(row)
            var col = 0
            while (col < cols) {
                val startCol = col
                val o0 = (row * cols + col) * PACKED_CELL_SIZE
                val refAttrs = buf[o0 + 30].toInt() and 0xFF
                val refFgR = buf[o0 + 24].toInt() and 0xFF
                val refFgG = buf[o0 + 25].toInt() and 0xFF
                val refFgB = buf[o0 + 26].toInt() and 0xFF
                val refBgR = buf[o0 + 27].toInt() and 0xFF
                val refBgG = buf[o0 + 28].toInt() and 0xFF
                val refBgB = buf[o0 + 29].toInt() and 0xFF
                val refUnder = buf[o0 + 31].toInt() and 0xFF
                var runEnd = col + 1
                while (runEnd < cols) {
                    val o = (row * cols + runEnd) * PACKED_CELL_SIZE
                    if ((buf[o + 30].toInt() and 0xFF) != refAttrs ||
                        (buf[o + 24].toInt() and 0xFF) != refFgR ||
                        (buf[o + 25].toInt() and 0xFF) != refFgG ||
                        (buf[o + 26].toInt() and 0xFF) != refFgB ||
                        (buf[o + 27].toInt() and 0xFF) != refBgR ||
                        (buf[o + 28].toInt() and 0xFF) != refBgG ||
                        (buf[o + 29].toInt() and 0xFF) != refBgB ||
                        (buf[o + 31].toInt() and 0xFF) != refUnder
                    ) {
                        break
                    }
                    runEnd++
                }
                fillCellRunFromBuf(run, buf, row * cols + startCol, runEnd - startCol)
                block(run)
                col = runEnd
            }
        }
    }

    override fun setPaletteColors(
        colors: IntArray,
        count: Int,
    ): Int {
        val bytes = count * 4
        val ptr = wasmAlloc(bytes)
        try {
            for (i in 0 until count) memory.writeI32(ptr + i * 4, colors[i])
            return fnSetPalette.apply(ptr.toLong(), count.toLong()).first().toInt()
        } finally {
            wasmFree(ptr)
        }
    }

    override fun setDefaultColors(
        foreground: Int,
        background: Int,
    ): Int = fnSetDefaultColors.apply(foreground.toLong(), background.toLong()).first().toInt()

    override fun getLineContinuation(row: Int): Boolean = fnGetLineCont.apply(row.toLong()).first().toInt() != 0

    override fun setBoldHighbright(enabled: Boolean): Int = fnSetBoldHB.apply(if (enabled) 1L else 0L).first().toInt()

    override fun close() {
        fnFree.apply()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun buildInstance(): Instance {
        val wasi = WasiPreview1.builder().build()
        val imports =
            com.dylibso.chicory.runtime.ImportValues
                .builder()
                .withFunctions(buildHostFunctions() + wasi.toHostFunctions().toList())
                .build()
        return Instance
            .builder(LibVterm.load())
            .withMachineFactory(LibVterm::create)
            .withMemoryFactory(::ByteArrayMemory)
            .withImportValues(imports)
            .build()
    }

    private fun buildHostFunctions(): List<HostFunction> = listOf(
        hostFn("damage", listOf(I32, I32, I32, I32), listOf(I32)) { _, args ->
            longArrayOf(
                callbacks
                    .damage(
                        args[0].toInt(),
                        args[1].toInt(),
                        args[2].toInt(),
                        args[3].toInt(),
                    ).toLong(),
            )
        },
        hostFn("moverect", listOf(I32, I32, I32, I32, I32, I32, I32, I32), listOf(I32)) { _, args ->
            longArrayOf(
                callbacks
                    .moverect(
                        args[0].toInt(),
                        args[1].toInt(),
                        args[2].toInt(),
                        args[3].toInt(),
                        args[4].toInt(),
                        args[5].toInt(),
                        args[6].toInt(),
                        args[7].toInt(),
                    ).toLong(),
            )
        },
        hostFn("movecursor", listOf(I32, I32, I32, I32, I32), listOf(I32)) { _, args ->
            longArrayOf(
                callbacks
                    .moveCursor(
                        args[0].toInt(),
                        args[1].toInt(),
                        args[2].toInt(),
                        args[3].toInt(),
                        args[4].toInt() != 0,
                    ).toLong(),
            )
        },
        hostFn("settermprop", listOf(I32, I32, I32, I32, I32), listOf(I32)) { inst, args ->
            val prop = args[0].toInt()
            val type = args[1].toInt()
            val iVal = args[2].toInt()
            val ptr = args[3].toInt()
            val len = args[4].toInt()
            val str = if (type == 3 && ptr != 0 && len > 0) inst.memory().readString(ptr, len) else null
            longArrayOf(callbacks.setTermProp(prop, type, iVal, str).toLong())
        },
        hostFn("bell", emptyList(), listOf(I32)) { _, _ ->
            longArrayOf(callbacks.bell().toLong())
        },
        hostFn("sb_pushline", listOf(I32, I32, I32), listOf(I32)) { _, args ->
            val cols = args[0].toInt()
            val cellsPtr = args[1].toInt()
            val softWrapped = args[2].toInt() != 0
            longArrayOf(callbacks.pushScrollbackLine(readPackedCells(cellsPtr, cols), softWrapped).toLong())
        },
        hostFn("sb_popline", listOf(I32, I32), listOf(I32)) { _, args ->
            val cols = args[0].toInt()
            val cellsPtr = args[1].toInt()
            val cells = callbacks.popScrollbackLine(cols)
            if (cells == null) {
                longArrayOf(0L)
            } else {
                writePackedCells(cellsPtr, cells, cols)
                longArrayOf(1L)
            }
        },
        hostFn("output", listOf(I32, I32), emptyList()) { inst, args ->
            val data = inst.memory().readBytes(args[0].toInt(), args[1].toInt())
            callbacks.onKeyboardOutput(data)
            longArrayOf()
        },
        hostFn("osc", listOf(I32, I32, I32, I32, I32), listOf(I32)) { inst, args ->
            val command = args[0].toInt()
            val ptr = args[1].toInt()
            val len = args[2].toInt()
            val cursorRow = args[3].toInt()
            val cursorCol = args[4].toInt()
            val payload = if (ptr != 0 && len > 0) inst.memory().readString(ptr, len) else ""
            longArrayOf(callbacks.onOscSequence(command, payload, cursorRow, cursorCol).toLong())
        },
    )

    /** Fills a [CellRun] from the packed buffer with a single bulk memory read. */
    private fun fillCellRun(
        run: CellRun,
        ptr: Int,
        count: Int,
    ) {
        val buf = memory.readBytes(ptr, count * PACKED_CELL_SIZE)
        fillCellRunFromBuf(run, buf, 0, count)
    }

    /** Fills a [CellRun] from a pre-fetched [buf], starting at cell index [startCell]. */
    private fun fillCellRunFromBuf(
        run: CellRun,
        buf: ByteArray,
        startCell: Int,
        count: Int,
    ) {
        run.reset()
        val base = startCell * PACKED_CELL_SIZE
        val attrs = buf[base + 30].toInt() and 0xFF
        run.fgRed = buf[base + 24].toInt() and 0xFF
        run.fgGreen = buf[base + 25].toInt() and 0xFF
        run.fgBlue = buf[base + 26].toInt() and 0xFF
        run.bgRed = buf[base + 27].toInt() and 0xFF
        run.bgGreen = buf[base + 28].toInt() and 0xFF
        run.bgBlue = buf[base + 29].toInt() and 0xFF
        run.bold = attrs and 0x01 != 0
        run.italic = attrs and 0x02 != 0
        run.reverse = attrs and 0x04 != 0
        run.strike = attrs and 0x08 != 0
        run.blink = attrs and 0x10 != 0
        run.underline = buf[base + 31].toInt() and 0xFF

        var charPos = 0
        if (run.chars.size < count * 2) run.chars = CharArray(count * 2)
        for (i in 0 until count) {
            val o = base + i * PACKED_CELL_SIZE
            val cp =
                (buf[o].toInt() and 0xFF) or
                    ((buf[o + 1].toInt() and 0xFF) shl 8) or
                    ((buf[o + 2].toInt() and 0xFF) shl 16) or
                    ((buf[o + 3].toInt() and 0xFF) shl 24)
            if (cp == 0) {
                run.chars[charPos++] = ' '
            } else if (cp > 0xFFFF) {
                run.chars[charPos++] = Character.highSurrogate(cp)
                run.chars[charPos++] = Character.lowSurrogate(cp)
            } else {
                run.chars[charPos++] = cp.toChar()
            }
        }
        run.runLength = count
    }

    private fun readPackedCells(
        ptr: Int,
        count: Int,
    ): List<WasmScreenCell> = List(count) { i ->
        val base = ptr + i * PACKED_CELL_SIZE
        val chars = IntArray(MAX_CHARS_PER_CELL) { j -> memory.readInt(base + j * 4) }
        val attrs = memory.read(base + 30).toInt() and 0xFF
        WasmScreenCell(
            chars = chars,
            fgRed = memory.read(base + 24).toInt() and 0xFF,
            fgGreen = memory.read(base + 25).toInt() and 0xFF,
            fgBlue = memory.read(base + 26).toInt() and 0xFF,
            bgRed = memory.read(base + 27).toInt() and 0xFF,
            bgGreen = memory.read(base + 28).toInt() and 0xFF,
            bgBlue = memory.read(base + 29).toInt() and 0xFF,
            bold = attrs and 0x01 != 0,
            italic = attrs and 0x02 != 0,
            reverse = attrs and 0x04 != 0,
            strike = attrs and 0x08 != 0,
            blink = attrs and 0x10 != 0,
            underline = memory.read(base + 31).toInt() and 0xFF,
            width = if ((memory.read(base + 32).toInt() and 0xFF) == 2) 2 else 1,
        )
    }

    private fun writePackedCells(
        ptr: Int,
        cells: List<WasmScreenCell>,
        cols: Int,
    ) {
        val n = minOf(cells.size, cols)
        for (i in 0 until n) {
            val base = ptr + i * PACKED_CELL_SIZE
            val cell = cells[i]
            for (j in 0 until MAX_CHARS_PER_CELL) {
                memory.writeI32(base + j * 4, if (j < cell.chars.size) cell.chars[j] else 0)
            }
            memory.writeByte(base + 24, cell.fgRed.toByte())
            memory.writeByte(base + 25, cell.fgGreen.toByte())
            memory.writeByte(base + 26, cell.fgBlue.toByte())
            memory.writeByte(base + 27, cell.bgRed.toByte())
            memory.writeByte(base + 28, cell.bgGreen.toByte())
            memory.writeByte(base + 29, cell.bgBlue.toByte())
            val attrs =
                (if (cell.bold) 0x01 else 0) or
                    (if (cell.italic) 0x02 else 0) or
                    (if (cell.reverse) 0x04 else 0) or
                    (if (cell.strike) 0x08 else 0) or
                    (if (cell.blink) 0x10 else 0)
            memory.writeByte(base + 30, attrs.toByte())
            memory.writeByte(base + 31, cell.underline.toByte())
            memory.writeByte(base + 32, cell.width.toByte())
            memory.writeByte(base + 33, 0)
            memory.writeByte(base + 34, 0)
            memory.writeByte(base + 35, 0)
        }
        for (i in n until cols) {
            val base = ptr + i * PACKED_CELL_SIZE
            repeat(PACKED_CELL_SIZE) { j -> memory.writeByte(base + j, 0) }
        }
    }

    private fun wasmAlloc(size: Int): Int = fnAlloc.apply(size.toLong()).first().toInt()

    private fun wasmFree(ptr: Int) {
        fnDealloc.apply(ptr.toLong())
    }

    private companion object {
        val I32 = ValType.I32

        fun hostFn(
            name: String,
            params: List<ValType>,
            results: List<ValType>,
            body: (Instance, LongArray) -> LongArray,
        ) = HostFunction(
            IMPORT_MODULE,
            name,
            FunctionType.of(params, results),
        ) { inst, args -> body(inst, args) }
    }
}
