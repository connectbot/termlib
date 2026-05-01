/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
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
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for ESC[3J (erase saved lines / clear scrollback buffer).
 *
 * ESC[3J is the standard xterm sequence to discard the scrollback buffer.
 * libvterm translates it into a sb_clear callback, which we wire through
 * to Kotlin via [TerminalCallbacks.clearScrollback].
 */
@RunWith(AndroidJUnit4::class)
class ScrollbackClearTest {

    private fun getSnapshot(impl: TerminalEmulatorImpl): TerminalSnapshot {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        impl.processPendingUpdates()
        return impl.snapshot.value
    }

    private fun TerminalEmulator.send(s: String) = writeInput(s.toByteArray())

    @Test
    fun testEsc3JClearsScrollback() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        repeat(10) { emulator.send("line ${it}\r\n") }

        val before = getSnapshot(impl)
        assertTrue("scrollback must be non-empty before clear", before.scrollback.isNotEmpty())

        emulator.send("\u001B[3J")

        val after = getSnapshot(impl)
        assertEquals("scrollback must be empty after ESC[3J", 0, after.scrollback.size)
    }

    @Test
    fun testEsc3JPreservesVisibleScreen() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        repeat(8) { emulator.send("scrolled ${it}\r\n") }
        emulator.send("\u001B[1;1H")
        emulator.send("visible line")

        emulator.send("\u001B[3J")

        val after = getSnapshot(impl)
        assertEquals("scrollback empty", 0, after.scrollback.size)
        assertEquals("visible row 0 intact", "visible line", after.lines[0].text.trimEnd())
    }

    @Test
    fun testScrollbackRepopulatesAfterClear() = runBlocking {
        // Clear the visible screen before ESC[3J so the visible "old" rows are blank.
        // Without this, those rows get pushed into scrollback when the second batch
        // scrolls them off, making the content check ambiguous.
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        repeat(8) { emulator.send("old ${it}\r\n") }
        emulator.send("\u001B[2J\u001B[H") // clear visible screen, cursor home
        emulator.send("\u001B[3J") // clear scrollback

        val afterClear = getSnapshot(impl)
        assertEquals("scrollback empty after clear", 0, afterClear.scrollback.size)

        repeat(8) { emulator.send("new ${it}\r\n") }

        val afterNew = getSnapshot(impl)
        assertTrue("new scrollback lines added", afterNew.scrollback.isNotEmpty())
        assertTrue(
            "old lines must not survive the clear",
            afterNew.scrollback.none { it.text.trimEnd().startsWith("old") },
        )
    }

    @Test
    fun testEsc2JAndEsc3JCombinedClearsEverything() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        repeat(10) { emulator.send("content ${it}\r\n") }

        emulator.send("\u001B[H\u001B[2J\u001B[3J")

        val after = getSnapshot(impl)
        assertEquals("scrollback empty", 0, after.scrollback.size)
        assertTrue(
            "all visible lines blank",
            after.lines.all { it.text.isBlank() },
        )
    }
}
