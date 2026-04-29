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

import org.connectbot.terminal.CellRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalWasmTest {
    private fun makeCallbacks(
        onDamage: (Int, Int, Int, Int) -> Int = { _, _, _, _ -> 0 },
        onOutput: (ByteArray) -> Unit = {},
    ) = object : WasmCallbacks {
        override fun damage(
            startRow: Int,
            endRow: Int,
            startCol: Int,
            endCol: Int,
        ) = onDamage(startRow, endRow, startCol, endCol)

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

        override fun moveCursor(
            row: Int,
            col: Int,
            oldRow: Int,
            oldCol: Int,
            visible: Boolean,
        ) = 0

        override fun setTermProp(
            prop: Int,
            type: Int,
            iVal: Int,
            str: String?,
        ) = 0

        override fun bell() = 0

        override fun pushScrollbackLine(
            cells: List<WasmScreenCell>,
            softWrapped: Boolean,
        ) = 0

        override fun popScrollbackLine(cols: Int): List<WasmScreenCell>? = null

        override fun onKeyboardOutput(data: ByteArray) = onOutput(data)

        override fun onOscSequence(
            command: Int,
            payload: String,
            cursorRow: Int,
            cursorCol: Int,
        ) = 0
    }

    @Test
    fun writeInputTriggersDamageCallback() {
        var damaged = false
        TerminalWasm(
            24,
            80,
            makeCallbacks(onDamage = { _, _, _, _ ->
                damaged = true
                0
            }),
        ).use {
            it.writeInput("Hello".toByteArray())
        }
        assertTrue("damage callback must be invoked after writeInput", damaged)
    }

    @Test
    fun cellRunContainsWrittenText() {
        TerminalWasm(24, 80, makeCallbacks()).use { term ->
            term.writeInput("Hello".toByteArray())
            val run = CellRun()
            val count = term.getCellRun(0, 0, run)
            assertTrue("run must not be empty", count > 0)
            val text = String(run.chars, 0, run.runLength)
            assertTrue("rendered text must start with 'Hello'", text.startsWith("Hello"))
        }
    }

    @Test
    fun resizeDoesNotCrash() {
        TerminalWasm(24, 80, makeCallbacks()).use {
            assertEquals(0, it.resize(40, 120))
        }
    }

    @Test
    fun dispatchKeyProducesOutput() {
        val output = mutableListOf<Byte>()
        TerminalWasm(24, 80, makeCallbacks(onOutput = { data -> output.addAll(data.toList()) })).use {
            it.dispatchKey(0, 13)
        }
        assertTrue("keyboard output must be non-empty after key dispatch", output.isNotEmpty())
    }

    @Test
    fun oscSequenceIsDeliveredToCallback() {
        var receivedCommand = -1
        var receivedPayload = ""
        val cbs =
            object : WasmCallbacks by makeCallbacks() {
                override fun onOscSequence(
                    command: Int,
                    payload: String,
                    cursorRow: Int,
                    cursorCol: Int,
                ): Int {
                    receivedCommand = command
                    receivedPayload = payload
                    return 1
                }
            }
        TerminalWasm(24, 80, cbs).use {
            it.writeInput("\u001B]133;A\u001B\\".toByteArray())
        }
        assertEquals(133, receivedCommand)
        assertEquals("A", receivedPayload)
    }
}
