/*
 * ConnectBot Terminal
 * Copyright 2026 Termlib contributors
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
 * Tests for mouse reporting: detecting the tracking mode an application asks
 * for via DECSET, and encoding the reports we send back.
 *
 * The escape sequences here are the ones a full-screen application actually
 * emits. Claude Code's alternate-screen renderer, for example, sends
 * `1000h 1002h 1003h 1006h` on startup and expects SGR wheel reports in return;
 * without them a scroll gesture has nowhere to go, because the application
 * keeps its own scrollback rather than the terminal's.
 */
@RunWith(AndroidJUnit4::class)
class MouseReportingTest {

    /**
     * Collects everything the emulator would write back to the PTY.
     *
     * The emulator posts keyboard output to its Looper rather than delivering it
     * on the calling thread, so both reading and clearing drain pending work
     * first — otherwise a report sent before a [clear] would land after it.
     */
    private class Output {
        private val sb = StringBuilder()

        val text: String
            get() {
                drain()
                return sb.toString()
            }

        fun append(data: ByteArray) {
            sb.append(String(data, Charsets.ISO_8859_1))
        }

        fun clear() {
            drain()
            sb.setLength(0)
        }

        private fun drain() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * The concrete type, not the [TerminalEmulator] interface: motion and bare
     * button presses are internal to the library, so only the implementation
     * exposes them.
     */
    private fun emulator(out: Output): TerminalEmulatorImpl = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        onKeyboardInput = { out.append(it) },
    ) as TerminalEmulatorImpl

    private fun TerminalEmulator.send(s: String) = writeInput(s.toByteArray())

    private companion object {
        /** DECSET 1000 and 1006: click tracking, SGR encoding. */
        const val CLICK_TRACKING_SGR = "\u001B[?1000h\u001B[?1006h"

        /** DECSET 1003 and 1006: all-motion tracking, SGR encoding. */
        const val MOVE_TRACKING_SGR = "\u001B[?1003h\u001B[?1006h"
    }

    // -----------------------------------------------------------------------
    // Tracking mode detection (VTERM_PROP_MOUSE)
    // -----------------------------------------------------------------------

    @Test
    fun testTrackingDefaultsToNone() = runBlocking {
        assertEquals(MouseTracking.NONE, emulator(Output()).mouseTracking)
    }

    @Test
    fun testDecsetSelectsTrackingMode() = runBlocking {
        val term = emulator(Output())

        term.send("\u001B[?1000h")
        assertEquals("DECSET 1000", MouseTracking.CLICK, term.mouseTracking)

        term.send("\u001B[?1002h")
        assertEquals("DECSET 1002", MouseTracking.DRAG, term.mouseTracking)

        term.send("\u001B[?1003h")
        assertEquals("DECSET 1003", MouseTracking.MOVE, term.mouseTracking)

        term.send("\u001B[?1003l")
        assertEquals("DECRST 1003", MouseTracking.NONE, term.mouseTracking)
    }

    @Test
    fun testClaudeCodeStartupSequenceEnablesTracking() = runBlocking {
        // The exact sequence Claude Code's flicker-free renderer emits.
        val term = emulator(Output())
        term.send("\u001B[?1000h\u001B[?1002h\u001B[?1003h\u001B[?1006h")

        assertEquals(MouseTracking.MOVE, term.mouseTracking)
        assertTrue(term.mouseTracking.isEnabled)
    }

    @Test
    fun testHardResetClearsTracking() = runBlocking {
        // RIS is how a user unwedges a terminal after a full-screen application
        // dies without restoring modes. It stops libvterm reporting the mouse, so
        // our mirror of the mode has to follow: believing an application still
        // wants the mouse routes gestures nowhere and leaves the terminal with no
        // scrolling at all.
        val term = emulator(Output())
        term.send(MOVE_TRACKING_SGR)

        term.send("\u001Bc")

        assertEquals(MouseTracking.NONE, term.mouseTracking)
    }

    @Test
    fun testSoftResetClearsTracking() = runBlocking {
        // DECSTR, the same story by a different route.
        val term = emulator(Output())
        term.send(MOVE_TRACKING_SGR)

        term.send("\u001B[!p")

        assertEquals(MouseTracking.NONE, term.mouseTracking)
    }

    @Test
    fun testNoReportsAfterReset() = runBlocking {
        // The mode flag and the reporting have to agree: whatever mouseTracking
        // says, a reset terminal emits nothing.
        val out = Output()
        val term = emulator(out)
        term.send(MOVE_TRACKING_SGR)
        term.send("\u001Bc")
        out.clear()

        term.scrollWheel(WheelDirection.UP, row = 3, col = 5)
        term.mouseButton(MouseButton.LEFT, row = 3, col = 5, pressed = true)
        term.mouseMove(row = 4, col = 6)

        assertEquals("", out.text)
    }

    // -----------------------------------------------------------------------
    // Wheel reporting
    // -----------------------------------------------------------------------

    @Test
    fun testNoReportsWhileTrackingDisabled() = runBlocking {
        val out = Output()
        val term = emulator(out)

        term.scrollWheel(WheelDirection.UP, row = 3, col = 5)
        term.mouseButton(MouseButton.LEFT, row = 3, col = 5, pressed = true)
        term.mouseMove(row = 4, col = 6)

        assertEquals("", out.text)
    }

    @Test
    fun testSgrWheelEncoding() = runBlocking {
        val out = Output()
        val term = emulator(out)

        // SGR (1006) is what every modern application selects, because X10
        // cannot address columns beyond 223.
        term.send(CLICK_TRACKING_SGR)
        out.clear()

        term.scrollWheel(WheelDirection.UP, row = 9, col = 19)

        // Button 64 = wheel up; coordinates are 1-based in the report.
        assertEquals("\u001B[<64;20;10M", out.text)
    }

    @Test
    fun testSgrWheelDownAndHorizontal() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)

        out.clear()
        term.scrollWheel(WheelDirection.DOWN, row = 0, col = 0)
        assertEquals("wheel down", "\u001B[<65;1;1M", out.text)

        out.clear()
        term.scrollWheel(WheelDirection.LEFT, row = 0, col = 0)
        assertEquals("wheel left", "\u001B[<66;1;1M", out.text)

        out.clear()
        term.scrollWheel(WheelDirection.RIGHT, row = 0, col = 0)
        assertEquals("wheel right", "\u001B[<67;1;1M", out.text)
    }

    @Test
    fun testMultipleStepsSendOneReportEach() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)
        out.clear()

        term.scrollWheel(WheelDirection.DOWN, row = 0, col = 0, steps = 3)

        assertEquals("\u001B[<65;1;1M".repeat(3), out.text)
    }

    @Test
    fun testNonPositiveStepsSendNothing() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)
        out.clear()

        term.scrollWheel(WheelDirection.DOWN, row = 0, col = 0, steps = 0)
        term.scrollWheel(WheelDirection.DOWN, row = 0, col = 0, steps = -2)

        assertEquals("", out.text)
    }

    @Test
    fun testWheelModifiersAreEncoded() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)
        out.clear()

        // Modifier bits are shifted left by 2 in the report: shift=4, alt=8,
        // ctrl=16. Ctrl+wheel-up is 64|16 = 80.
        term.scrollWheel(WheelDirection.UP, row = 0, col = 0, modifiers = 4)

        assertEquals("\u001B[<80;1;1M", out.text)
    }

    @Test
    fun testX10WheelEncodingWhenSgrNotRequested() = runBlocking {
        val out = Output()
        val term = emulator(out)

        // Tracking without an encoding request leaves the legacy X10 encoding.
        term.send("\u001B[?1000h")
        out.clear()

        term.scrollWheel(WheelDirection.UP, row = 0, col = 0)

        // CSI M, then (code|mods)+0x20, col+0x21, row+0x21.
        assertEquals("\u001B[M`!!", out.text)
    }

    // -----------------------------------------------------------------------
    // Buttons and motion
    // -----------------------------------------------------------------------

    @Test
    fun testButtonPressAndReleaseEncoding() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)
        out.clear()

        term.mouseButton(MouseButton.LEFT, row = 2, col = 7, pressed = true)
        term.mouseButton(MouseButton.LEFT, row = 2, col = 7, pressed = false)

        // Press ends in 'M', release in 'm'; left button is code 0.
        assertEquals("\u001B[<0;8;3M\u001B[<0;8;3m", out.text)
    }

    @Test
    fun testMiddleAndRightButtonCodes() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)

        out.clear()
        term.mouseButton(MouseButton.MIDDLE, row = 0, col = 0, pressed = true)
        assertEquals("middle", "\u001B[<1;1;1M", out.text)

        out.clear()
        term.mouseButton(MouseButton.RIGHT, row = 0, col = 0, pressed = true)
        assertEquals("right", "\u001B[<2;1;1M", out.text)
    }

    @Test
    fun testClickTrackingDoesNotReportMotion() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)
        out.clear()

        term.mouseMove(row = 5, col = 5)
        term.mouseMove(row = 6, col = 7)

        assertEquals("", out.text)
    }

    @Test
    fun testMoveTrackingReportsMotion() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(MOVE_TRACKING_SGR)
        out.clear()

        term.mouseMove(row = 5, col = 9)

        // 32 is the motion bit; with no button held libvterm reports button 4.
        assertEquals("\u001B[<35;10;6M", out.text)
    }

    @Test
    fun testRepeatedMoveToSameCellIsSuppressed() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(MOVE_TRACKING_SGR)
        out.clear()

        term.mouseMove(row = 5, col = 9)
        val afterFirst = out.text
        term.mouseMove(row = 5, col = 9)

        assertEquals("second move suppressed", afterFirst, out.text)
    }

    @Test
    fun testWheelDoesNotEmitMotionUnderMoveTracking() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(MOVE_TRACKING_SGR)

        // Park the pointer where the gesture is happening, then scroll there.
        term.mouseMove(row = 4, col = 4)
        out.clear()

        term.scrollWheel(WheelDirection.UP, row = 4, col = 4, steps = 2)

        // Only the two wheel reports — the implicit move is a no-op because the
        // pointer is already on that cell.
        assertEquals("\u001B[<64;5;5M".repeat(2), out.text)
    }

    // -----------------------------------------------------------------------
    // Clicks
    // -----------------------------------------------------------------------

    @Test
    fun testClickEmitsPressAndRelease() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)
        out.clear()

        term.mouseClick(MouseButton.LEFT, row = 2, col = 7)

        // Exactly what a press followed by its release encodes to, and nothing
        // an application could mistake for a button still being held.
        assertEquals("\u001B[<0;8;3M\u001B[<0;8;3m", out.text)
    }

    @Test
    fun testClickReleasesEveryButton() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)

        for (button in MouseButton.entries) {
            out.clear()
            term.mouseClick(button, row = 0, col = 0)

            val reports = out.text
            assertTrue(
                "$button click should end in a release, got: " + reports.replace("\u001B", "ESC"),
                reports.endsWith("m"),
            )
        }
    }

    @Test
    fun testClickIsSilentWhileTrackingDisabled() = runBlocking {
        val out = Output()
        val term = emulator(out)

        term.mouseClick(MouseButton.LEFT, row = 2, col = 7)

        assertEquals("", out.text)
    }

    // -----------------------------------------------------------------------
    // Coordinates outside the screen
    // -----------------------------------------------------------------------

    @Test
    fun testCoordinatesAreClampedToTheScreen() = runBlocking {
        // libvterm's X10 encoder clamps only the high end, so a negative
        // coordinate would otherwise put a control byte on the wire. Clamping
        // happens natively, against the size the terminal actually has.
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)

        out.clear()
        term.mouseClick(MouseButton.LEFT, row = -5, col = -9)
        assertEquals("clamped to the first cell", "\u001B[<0;1;1M\u001B[<0;1;1m", out.text)

        out.clear()
        term.mouseClick(MouseButton.LEFT, row = 9999, col = 9999)
        // 24x80 terminal, so the last cell is row 23, col 79, 1-based in SGR.
        assertEquals("clamped to the last cell", "\u001B[<0;80;24M\u001B[<0;80;24m", out.text)
    }

    @Test
    fun testWheelCoordinatesAreClampedToTheScreen() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)
        out.clear()

        term.scrollWheel(WheelDirection.UP, row = Int.MIN_VALUE, col = Int.MIN_VALUE)

        assertEquals("\u001B[<64;1;1M", out.text)
    }

    @Test
    fun testWheelBurstIsBoundedNatively() = runBlocking {
        // The native loop emits a report per step while holding the terminal
        // lock, so an absurd step count must not translate into an absurd number
        // of reports - whatever a caller asks for.
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)
        out.clear()

        term.scrollWheel(WheelDirection.DOWN, row = 0, col = 0, steps = Int.MAX_VALUE)

        val reports = Regex(Regex.escape("\u001B[<65;1;1M")).findAll(out.text).count()
        assertTrue("bounded burst, got $reports reports", reports in 1..64)
    }

    // -----------------------------------------------------------------------
    // Reset clears the rest of the mouse state, not just the mode
    // -----------------------------------------------------------------------

    @Test
    fun testResetClearsReportEncoding() = runBlocking {
        // A reset that leaves the SGR encoding selected would answer a later
        // plain DECSET 1000 in a protocol that application never asked for.
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)

        term.send("\u001Bc")
        term.send("\u001B[?1000h")
        out.clear()

        term.scrollWheel(WheelDirection.UP, row = 0, col = 0)

        // X10 again: CSI M, then (code|mods)+0x20, col+0x21, row+0x21.
        assertEquals("\u001B[M`!!", out.text)
    }

    @Test
    fun testResetClearsHeldButtons() = runBlocking {
        // A press whose release never came leaves libvterm believing a button is
        // down, which makes DRAG tracking report motion with nothing held.
        val out = Output()
        val term = emulator(out)
        term.send(CLICK_TRACKING_SGR)
        term.mouseButton(MouseButton.LEFT, row = 0, col = 0, pressed = true)

        term.send("\u001Bc")
        term.send("\u001B[?1002h\u001B[?1006h")
        out.clear()

        term.mouseMove(row = 5, col = 5)

        assertEquals("no button is held, so a drag reports nothing", "", out.text)
    }
}
