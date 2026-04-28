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
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for cursor save/restore (DECSC/DECRC), alternate-screen
 * switch (DECSET/DECRST 1049), and scroll region (DECSTBM).
 *
 * These are the escape-sequence paths that vim, tmux, zellij, less, and man
 * rely on. They exercise libvterm's handling rather than our own Kotlin code,
 * but coverage here is useful as a regression guard against future libvterm
 * bumps or screen-management refactors.
 *
 * All tests use [TerminalEmulatorFactory.create] + [TerminalEmulator.writeInput]
 * + a stable snapshot read — the same pattern as [OscSequenceTest] and
 * [ShellIntegrationTest].
 */
@RunWith(AndroidJUnit4::class)
class CursorAndModeEscapeTest {

    private fun getSnapshot(impl: TerminalEmulatorImpl): TerminalSnapshot {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        impl.processPendingUpdates()
        return impl.snapshot.value
    }

    private fun TerminalEmulator.send(s: String) {
        writeInput(s.toByteArray())
    }

    // -----------------------------------------------------------------------
    // DECSC (ESC 7) / DECRC (ESC 8) — save / restore cursor
    // -----------------------------------------------------------------------

    @Test
    fun testDecscDecrcRestoresSavedPosition() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        // Move to row 5 (CUP is 1-based), save, move elsewhere, restore.
        // ESC[6;20H → row 5, col 19 (0-based).
        emulator.send("\u001B[6;20H")
        emulator.send("\u001B7") // DECSC: save
        emulator.send("\u001B[1;1H") // move to (0, 0)
        emulator.send("\u001B8") // DECRC: restore

        val s = getSnapshot(impl)
        assertEquals("restored row", 5, s.cursorRow)
        assertEquals("restored col", 19, s.cursorCol)
    }

    @Test
    fun testDecrcWithoutDecscGoesToOrigin() = runBlocking {
        // With no prior save, DECRC is defined by xterm/VT to return to (0, 0)
        // with attributes at defaults. We assert the (0, 0) half — the easiest
        // to observe.
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("\u001B[6;20H") // move away from origin
        emulator.send("\u001B8") // DECRC with no saved state

        val s = getSnapshot(impl)
        assertEquals("no-save DECRC row", 0, s.cursorRow)
        assertEquals("no-save DECRC col", 0, s.cursorCol)
    }

    @Test
    fun testDecscDecrcSurvivesInterveningNewline() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("\u001B[3;10H") // (2, 9)
        emulator.send("\u001B7") // save
        emulator.send("line\r\nmore\r\nstuff\r\n") // scribble, moving cursor
        emulator.send("\u001B8") // restore

        val s = getSnapshot(impl)
        assertEquals(2, s.cursorRow)
        assertEquals(9, s.cursorCol)
    }

    @Test
    fun testDecscDecrcRepeatedSaveUsesMostRecent() = runBlocking {
        // Each DECSC overwrites the saved slot (VT100 semantics: one save register).
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("\u001B[2;5H\u001B7") // save (1, 4)
        emulator.send("\u001B[6;20H\u001B7") // save (5, 19) — replaces
        emulator.send("\u001B[1;1H") // move to (0, 0)
        emulator.send("\u001B8") // restore

        val s = getSnapshot(impl)
        assertEquals(5, s.cursorRow)
        assertEquals(19, s.cursorCol)
    }

    @Test
    fun testDecscDecrcMidScreenWrite() = runBlocking {
        // Common real-world pattern: save, seek away to write status info,
        // restore and keep typing where the user was.
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("\u001B[5;5H") // (4, 4)
        emulator.send("abc") // types 'abc' → cursor now (4, 7)
        emulator.send("\u001B7") // save (4, 7)
        emulator.send("\u001B[1;1H") // jump to (0, 0)
        emulator.send("status") // write elsewhere
        emulator.send("\u001B8") // restore
        emulator.send("XYZ") // resume typing

        val s = getSnapshot(impl)
        assertEquals("text on row 4", "    abcXYZ", s.lines[4].text.trimEnd())
        assertEquals("status on row 0", "status", s.lines[0].text.trimEnd())
    }

    // -----------------------------------------------------------------------
    // DECSET/DECRST 1049 — alternate screen buffer (vim, less, man)
    // -----------------------------------------------------------------------

    @Test
    fun testAltScreenEnterPreservesMainScreen() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("main screen line 1\r\n")
        emulator.send("main screen line 2\r\n")

        // Enter alt screen: ESC[?1049h
        emulator.send("\u001B[?1049h")
        // Clear alt screen + write different content
        emulator.send("\u001B[2J\u001B[H")
        emulator.send("alt screen content")

        val altSnapshot = getSnapshot(impl)
        assertEquals("alt screen shows alt content", "alt screen content", altSnapshot.lines[0].text.trimEnd())
        // Main screen should not be in alt-screen's visible lines
        assertNotEquals("main content not leaked into alt", "main screen line 1", altSnapshot.lines[0].text.trimEnd())
    }

    @Test
    fun testAltScreenExitRestoresMainScreen() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("main line 1\r\n")
        emulator.send("main line 2\r\n")
        emulator.send("main line 3\r\n")

        // Enter alt, scribble, exit.
        emulator.send("\u001B[?1049h")
        emulator.send("\u001B[2J\u001B[H")
        emulator.send("alt contents that should vanish")
        emulator.send("\u001B[?1049l")

        val after = getSnapshot(impl)
        assertEquals("row 0 restored", "main line 1", after.lines[0].text.trimEnd())
        assertEquals("row 1 restored", "main line 2", after.lines[1].text.trimEnd())
        assertEquals("row 2 restored", "main line 3", after.lines[2].text.trimEnd())
    }

    @Test
    fun testAltScreenDoesNotPollutePrimaryScrollback() = runBlocking {
        // The whole point of 1049 over 47 is that alt-screen writes never
        // touch the main screen's scrollback. Scroll a bunch while in alt,
        // exit, confirm main scrollback is unchanged.
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("\u001B[?1049h")
        repeat(20) { emulator.send("alt line $it\r\n") }
        emulator.send("\u001B[?1049l")

        val after = getSnapshot(impl)
        val allText = (after.scrollback + after.lines).joinToString("\n") { it.text.trimEnd() }
        // Allow anything — just assert alt-screen content didn't escape to scrollback
        assertEquals(
            "alt-screen lines must not appear in main scrollback after exit",
            0,
            after.scrollback.count { it.text.contains("alt line") },
        )
    }

    // -----------------------------------------------------------------------
    // DECSTBM — scroll region (tmux status bar)
    // -----------------------------------------------------------------------

    @Test
    fun testScrollRegionTopMarginPreservesLinesAbove() = runBlocking {
        // Set scroll region from row 3 downward; lines above must be untouched
        // when scrolling occurs inside the region.
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        // Fill rows 0-2 with unique text
        emulator.send("status row 0\r\n")
        emulator.send("status row 1\r\n")
        emulator.send("status row 2\r\n")

        // Set scroll region rows 4..10 (1-based), move into it, spam lines
        emulator.send("\u001B[4;10r")
        emulator.send("\u001B[4;1H") // move to top of scroll region
        repeat(20) { emulator.send("scroll line $it\r\n") }

        val after = getSnapshot(impl)
        assertEquals("row 0 preserved", "status row 0", after.lines[0].text.trimEnd())
        assertEquals("row 1 preserved", "status row 1", after.lines[1].text.trimEnd())
        assertEquals("row 2 preserved", "status row 2", after.lines[2].text.trimEnd())
    }

    @Test
    fun testScrollRegionBottomMarginPreservesLinesBelow() = runBlocking {
        // Mirror of the above — lines below the bottom margin should survive.
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        // Fill rows 8-9 with unique text; they'll be below the scroll region.
        emulator.send("\u001B[9;1H")
        emulator.send("footer A\r\n")
        emulator.send("footer B")

        // Scroll region 1..7 (1-based). Rows 7 and 8 (0-based) stay put.
        emulator.send("\u001B[1;7r")
        emulator.send("\u001B[1;1H")
        repeat(15) { emulator.send("scrollable $it\r\n") }

        val after = getSnapshot(impl)
        assertEquals("footer A preserved on row 8", "footer A", after.lines[8].text.trimEnd())
        assertEquals("footer B preserved on row 9", "footer B", after.lines[9].text.trimEnd())
    }

    @Test
    fun testScrollRegionResetExpandsToFullScreen() = runBlocking {
        // ESC[r with no params resets to the full screen. After the reset, a
        // scroll anywhere should be able to push the previously-protected
        // rows.
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("protected row 0\r\n")
        emulator.send("\u001B[3;10r") // region rows 3..10
        // Reset to full screen
        emulator.send("\u001B[r")
        // Full-screen scroll: write past the bottom
        emulator.send("\u001B[10;1H") // last row
        repeat(3) { emulator.send("\n") }

        val after = getSnapshot(impl)
        assertEquals(
            "after reset + full-screen scroll, row 0 should no longer be 'protected row 0'",
            false,
            after.lines[0].text.trimEnd() == "protected row 0",
        )
    }
}
