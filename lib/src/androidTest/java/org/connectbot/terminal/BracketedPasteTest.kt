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
package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BracketedPasteTest {
    private fun newEmulator(sink: StringBuilder): TerminalEmulator =
        TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            onKeyboardInput = { bytes -> sink.append(bytes.toString(Charsets.UTF_8)) }
        )

    @Test
    fun pasteWithoutBracketedModeSendsTextRaw() {
        val out = StringBuilder()
        val term = newEmulator(out)

        term.paste("hello")

        assertEquals("hello", out.toString())
    }

    @Test
    fun pasteWrapsWithMarkersWhenBracketedModeEnabled() {
        val out = StringBuilder()
        val term = newEmulator(out)

        // Remote enables DEC mode 2004 (bracketed paste)
        term.writeInput("\u001B[?2004h".toByteArray(Charsets.UTF_8))

        term.paste("hello\nworld")

        assertEquals("\u001B[200~hello\nworld\u001B[201~", out.toString())
    }

    @Test
    fun pasteRevertsToRawAfterBracketedModeDisabled() {
        val out = StringBuilder()
        val term = newEmulator(out)

        term.writeInput("\u001B[?2004h".toByteArray(Charsets.UTF_8))
        term.paste("first")
        val afterEnabled = out.toString()

        out.clear()
        term.writeInput("\u001B[?2004l".toByteArray(Charsets.UTF_8))
        term.paste("second")

        assertEquals("\u001B[200~first\u001B[201~", afterEnabled)
        assertEquals("second", out.toString())
    }

    @Test
    fun pasteEncodesNonAsciiCodePointsAsUtf8() {
        val out = StringBuilder()
        val term = newEmulator(out)

        term.writeInput("\u001B[?2004h".toByteArray(Charsets.UTF_8))
        term.paste("café 🚀")

        assertEquals("\u001B[200~café 🚀\u001B[201~", out.toString())
    }
}
