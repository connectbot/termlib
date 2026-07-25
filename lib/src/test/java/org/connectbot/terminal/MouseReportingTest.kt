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

    private fun emulator(out: Output): TerminalEmulator = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        onKeyboardInput = { out.append(it) },
    )

    private fun TerminalEmulator.send(s: String) = writeInput(s.toByteArray())

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

    // -----------------------------------------------------------------------
    // Wheel reporting
    // -----------------------------------------------------------------------

    @Test
    fun testNoReportsWhileTrackingDisabled() = runBlocking {
        val out = Output()
        val term = emulator(out)

        term.scrollWheel(WheelDirection.UP, row = 3, col = 5)
        term.mouseButton(MouseButton.LEFT, pressed = true, row = 3, col = 5)
        term.mouseMove(row = 4, col = 6)

        assertEquals("", out.text)
    }

    @Test
    fun testSgrWheelEncoding() = runBlocking {
        val out = Output()
        val term = emulator(out)

        // SGR (1006) is what every modern application selects, because X10
        // cannot address columns beyond 223.
        term.send("\u001B[?1000h\u001B[?1006h")
        out.clear()

        term.scrollWheel(WheelDirection.UP, row = 9, col = 19)

        // Button 64 = wheel up; coordinates are 1-based in the report.
        assertEquals("\u001B[<64;20;10M", out.text)
    }

    @Test
    fun testSgrWheelDownAndHorizontal() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send("\u001B[?1000h\u001B[?1006h")

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
        term.send("\u001B[?1000h\u001B[?1006h")
        out.clear()

        term.scrollWheel(WheelDirection.DOWN, row = 0, col = 0, steps = 3)

        assertEquals("\u001B[<65;1;1M".repeat(3), out.text)
    }

    @Test
    fun testNonPositiveStepsSendNothing() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send("\u001B[?1000h\u001B[?1006h")
        out.clear()

        term.scrollWheel(WheelDirection.DOWN, row = 0, col = 0, steps = 0)
        term.scrollWheel(WheelDirection.DOWN, row = 0, col = 0, steps = -2)

        assertEquals("", out.text)
    }

    @Test
    fun testWheelModifiersAreEncoded() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send("\u001B[?1000h\u001B[?1006h")
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
        term.send("\u001B[?1000h\u001B[?1006h")
        out.clear()

        term.mouseButton(MouseButton.LEFT, pressed = true, row = 2, col = 7)
        term.mouseButton(MouseButton.LEFT, pressed = false, row = 2, col = 7)

        // Press ends in 'M', release in 'm'; left button is code 0.
        assertEquals("\u001B[<0;8;3M\u001B[<0;8;3m", out.text)
    }

    @Test
    fun testMiddleAndRightButtonCodes() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send("\u001B[?1000h\u001B[?1006h")

        out.clear()
        term.mouseButton(MouseButton.MIDDLE, pressed = true, row = 0, col = 0)
        assertEquals("middle", "\u001B[<1;1;1M", out.text)

        out.clear()
        term.mouseButton(MouseButton.RIGHT, pressed = true, row = 0, col = 0)
        assertEquals("right", "\u001B[<2;1;1M", out.text)
    }

    @Test
    fun testClickTrackingDoesNotReportMotion() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send("\u001B[?1000h\u001B[?1006h")
        out.clear()

        term.mouseMove(row = 5, col = 5)
        term.mouseMove(row = 6, col = 7)

        assertEquals("", out.text)
    }

    @Test
    fun testMoveTrackingReportsMotion() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send("\u001B[?1003h\u001B[?1006h")
        out.clear()

        term.mouseMove(row = 5, col = 9)

        // 32 is the motion bit; with no button held libvterm reports button 4.
        assertEquals("\u001B[<35;10;6M", out.text)
    }

    @Test
    fun testRepeatedMoveToSameCellIsSuppressed() = runBlocking {
        val out = Output()
        val term = emulator(out)
        term.send("\u001B[?1003h\u001B[?1006h")
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
        term.send("\u001B[?1003h\u001B[?1006h")

        // Park the pointer where the gesture is happening, then scroll there.
        term.mouseMove(row = 4, col = 4)
        out.clear()

        term.scrollWheel(WheelDirection.UP, row = 4, col = 4, steps = 2)

        // Only the two wheel reports — the implicit move is a no-op because the
        // pointer is already on that cell.
        assertEquals("\u001B[<64;5;5M".repeat(2), out.text)
    }
}
