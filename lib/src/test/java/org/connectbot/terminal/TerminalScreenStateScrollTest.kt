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

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TerminalScreenState] scroll position behavior.
 *
 * Covers: snapshot updates during scrollback growth, disconnect/reconnect
 * scenarios (scrollback size changes without new content), and edge cases
 * in getVisibleLine when scrollbackPosition and scrollback.size change
 * simultaneously.
 *
 * These tests target scroll-position drift seen when a TUI with an animated
 * status line (progress bars, htop, tmux, long-running LLM/tool UIs, etc.)
 * interleaves in-place updates with scrollback growth — specifically the
 * updateSnapshot() path that must adjust scrollbackPosition by the delta so
 * the scrolled-up viewport keeps the same content on screen.
 */
class TerminalScreenStateScrollTest {

    private fun cells(text: String, cols: Int): List<TerminalLine.Cell> {
        val padded = text.padEnd(cols)
        return padded.map { ch ->
            TerminalLine.Cell(char = ch, fgColor = Color.White, bgColor = Color.Black)
        }
    }

    private fun lineOf(row: Int, text: String, cols: Int): TerminalLine = TerminalLine(row = row, cells = cells(text, cols))

    private fun snapshot(
        cols: Int = 80,
        rows: Int = 24,
        screenLines: List<String> = List(rows) { "" },
        scrollback: List<String> = emptyList(),
        seqNum: Long = 0L,
    ): TerminalSnapshot {
        val lines = screenLines.mapIndexed { i, t -> lineOf(i, t, cols) }
        val sb = scrollback.mapIndexed { i, t -> lineOf(-(i + 1), t, cols) }
        return TerminalSnapshot(
            lines = lines,
            scrollback = sb,
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = true,
            cursorBlink = true,
            cursorShape = CursorShape.BLOCK,
            terminalTitle = "",
            rows = rows,
            cols = cols,
            timestamp = 0L,
            sequenceNumber = seqNum,
        )
    }

    // --- Basic scroll position preservation ---

    @Test
    fun `at bottom stays at bottom when scrollback grows`() {
        val state = TerminalScreenState(snapshot(scrollback = listOf("old1")))
        assertEquals(0, state.scrollbackPosition)
        assertTrue(state.isAtBottom())

        // New line scrolls out — scrollback grows by 1
        state.updateSnapshot(snapshot(scrollback = listOf("old1", "old2"), seqNum = 1))

        assertEquals(0, state.scrollbackPosition)
        assertTrue(state.isAtBottom())
    }

    @Test
    fun `at bottom stays at bottom when scrollback grows by many lines`() {
        val state = TerminalScreenState(snapshot(scrollback = emptyList()))

        // Simulate a burst of output (e.g. reconnect redraw) adding 50 scrollback lines
        val bigScrollback = (1..50).map { "scrollback line $it" }
        state.updateSnapshot(snapshot(scrollback = bigScrollback, seqNum = 1))

        assertEquals(0, state.scrollbackPosition)
        assertTrue(state.isAtBottom())
    }

    @Test
    fun `scrolled-up position preserved when scrollback grows`() {
        val state = TerminalScreenState(snapshot(scrollback = listOf("sb1", "sb2", "sb3")))
        state.scrollBy(2) // Scroll up 2 lines
        assertEquals(2, state.scrollbackPosition)

        // New line pushed to scrollback
        state.updateSnapshot(snapshot(scrollback = listOf("sb1", "sb2", "sb3", "sb4"), seqNum = 1))

        // Position should adjust by delta (+1) to keep same content visible
        assertEquals(3, state.scrollbackPosition)
    }

    @Test
    fun `scrolled-up position clamped when scrollback shrinks`() {
        val state = TerminalScreenState(snapshot(scrollback = listOf("sb1", "sb2", "sb3")))
        state.scrollBy(3) // Scroll to top
        assertEquals(3, state.scrollbackPosition)

        // Scrollback shrinks (e.g. resize popping lines back)
        state.updateSnapshot(snapshot(scrollback = listOf("sb1"), seqNum = 1))

        // Position clamped to new scrollback size
        assertEquals(1, state.scrollbackPosition)
    }

    // --- Rapid scrollback growth (animated TUI, e.g. tmux scroll region) ---

    @Test
    fun `rapid scrollback growth while at bottom keeps position at zero`() {
        val state = TerminalScreenState(snapshot(scrollback = emptyList()))

        // Simulate 100 rapid snapshot updates, each adding one scrollback line
        for (i in 1..100) {
            val sb = (1..i).map { "line $it" }
            state.updateSnapshot(snapshot(scrollback = sb, seqNum = i.toLong()))
            assertEquals("After update $i", 0, state.scrollbackPosition)
        }
    }

    @Test
    fun `rapid scrollback growth while scrolled up tracks delta correctly`() {
        val state = TerminalScreenState(snapshot(scrollback = (1..10).map { "line $it" }))
        state.scrollBy(5) // Scroll up 5 lines
        assertEquals(5, state.scrollbackPosition)

        // 10 rapid updates, each adding one scrollback line
        for (i in 1..10) {
            val sb = (1..10 + i).map { "line $it" }
            state.updateSnapshot(snapshot(scrollback = sb, seqNum = i.toLong()))
            assertEquals("After update $i", 5 + i, state.scrollbackPosition)
        }
    }

    // --- Disconnect/reconnect: scrollback reset scenarios ---

    @Test
    fun `scrollback cleared on reconnect resets scroll position`() {
        // Before disconnect: user has scrollback and is scrolled up
        val state = TerminalScreenState(
            snapshot(scrollback = (1..20).map { "old $it" }),
        )
        state.scrollBy(10)
        assertEquals(10, state.scrollbackPosition)

        // Reconnect: scrollback is cleared (new emulator or screen reset)
        state.updateSnapshot(snapshot(scrollback = emptyList(), seqNum = 1))

        // Position should be clamped to 0 (no scrollback to scroll into)
        assertEquals(0, state.scrollbackPosition)
        assertTrue(state.isAtBottom())
    }

    @Test
    fun `scrollback replaced on reconnect adjusts position`() {
        // Before disconnect: 20 scrollback lines, scrolled up 5
        val state = TerminalScreenState(
            snapshot(scrollback = (1..20).map { "old $it" }),
        )
        state.scrollBy(5)
        assertEquals(5, state.scrollbackPosition)

        // Reconnect with different scrollback (tmux replay)
        val newSb = (1..8).map { "new $it" }
        state.updateSnapshot(snapshot(scrollback = newSb, seqNum = 1))

        // Delta = 8 - 20 = -12, new position = 5 + (-12) = -7, clamped to 0
        assertEquals(0, state.scrollbackPosition)
    }

    // --- getVisibleLine edge cases ---

    @Test
    fun `getVisibleLine at bottom returns screen lines`() {
        val screen = listOf("screen line 0", "screen line 1", "screen line 2")
        val state = TerminalScreenState(
            snapshot(rows = 3, cols = 80, screenLines = screen, scrollback = listOf("sb1", "sb2")),
        )

        assertEquals(0, state.scrollbackPosition)
        assertEquals("screen line 0", state.getVisibleLine(0).text.trimEnd())
        assertEquals("screen line 1", state.getVisibleLine(1).text.trimEnd())
        assertEquals("screen line 2", state.getVisibleLine(2).text.trimEnd())
    }

    @Test
    fun `getVisibleLine scrolled up shows scrollback content`() {
        val screen = listOf("screen 0", "screen 1", "screen 2")
        val sb = listOf("sb0", "sb1", "sb2")
        val state = TerminalScreenState(
            snapshot(rows = 3, cols = 80, screenLines = screen, scrollback = sb),
        )

        state.scrollBy(2) // Scroll up 2 lines

        // Visible viewport: sb1, sb2, screen 0
        assertEquals("sb1", state.getVisibleLine(0).text.trimEnd())
        assertEquals("sb2", state.getVisibleLine(1).text.trimEnd())
        assertEquals("screen 0", state.getVisibleLine(2).text.trimEnd())
    }

    @Test
    fun `getVisibleLine at top of scrollback`() {
        val screen = listOf("screen 0", "screen 1", "screen 2")
        val sb = listOf("sb0", "sb1", "sb2")
        val state = TerminalScreenState(
            snapshot(rows = 3, cols = 80, screenLines = screen, scrollback = sb),
        )

        state.scrollToTop() // scrollbackPosition = 3

        // Visible viewport: sb0, sb1, sb2
        assertEquals("sb0", state.getVisibleLine(0).text.trimEnd())
        assertEquals("sb1", state.getVisibleLine(1).text.trimEnd())
        assertEquals("sb2", state.getVisibleLine(2).text.trimEnd())
    }

    @Test
    fun `getVisibleLine clamps to valid range`() {
        val screen = listOf("screen 0", "screen 1")
        val sb = listOf("sb0")
        val state = TerminalScreenState(
            snapshot(rows = 2, cols = 80, screenLines = screen, scrollback = sb),
        )

        // Scroll to position that would read before scrollback start
        state.scrollBy(10) // Clamped to 1

        // row 0: index = 1 - 1 + 0 = 0 → sb0
        assertEquals("sb0", state.getVisibleLine(0).text.trimEnd())
        // row 1: index = 1 - 1 + 1 = 1 → screen 0
        assertEquals("screen 0", state.getVisibleLine(1).text.trimEnd())
    }

    // --- Scroll position after updateSnapshot with simultaneous resize ---

    @Test
    fun `resize shrinking rows while scrolled up preserves relative position`() {
        // 24-row terminal with 50 scrollback lines, scrolled up 10
        val state = TerminalScreenState(
            snapshot(rows = 24, scrollback = (1..50).map { "line $it" }),
        )
        state.scrollBy(10)
        assertEquals(10, state.scrollbackPosition)

        // Resize to 20 rows pushes 4 lines to scrollback (50 → 54)
        // Simulate: lines grow by 4 in scrollback
        state.updateSnapshot(
            snapshot(rows = 20, scrollback = (1..54).map { "line $it" }, seqNum = 1),
        )

        // Delta = 54 - 50 = 4, new position = 10 + 4 = 14
        assertEquals(14, state.scrollbackPosition)
    }

    @Test
    fun `resize growing rows while scrolled up pops from scrollback`() {
        // 20-row terminal with 30 scrollback lines, scrolled up 5
        val state = TerminalScreenState(
            snapshot(rows = 20, scrollback = (1..30).map { "line $it" }),
        )
        state.scrollBy(5)
        assertEquals(5, state.scrollbackPosition)

        // Resize to 24 rows pops 4 lines from scrollback (30 → 26)
        state.updateSnapshot(
            snapshot(rows = 24, scrollback = (1..26).map { "line $it" }, seqNum = 1),
        )

        // Delta = 26 - 30 = -4, new position = 5 + (-4) = 1
        assertEquals(1, state.scrollbackPosition)
    }

    @Test
    fun `resize growing rows while barely scrolled up clamps to zero`() {
        // 20-row terminal with 10 scrollback, scrolled up 2
        val state = TerminalScreenState(
            snapshot(rows = 20, scrollback = (1..10).map { "line $it" }),
        )
        state.scrollBy(2)
        assertEquals(2, state.scrollbackPosition)

        // Resize pops 5 lines from scrollback (10 → 5)
        state.updateSnapshot(
            snapshot(rows = 25, scrollback = (1..5).map { "line $it" }, seqNum = 1),
        )

        // Delta = 5 - 10 = -5, new position = 2 + (-5) = -3, clamped to 0
        assertEquals(0, state.scrollbackPosition)
    }

    // --- Scroll region changes (tmux with status bar) ---

    @Test
    fun `scrollback growth from partial scroll region keeps position at bottom`() {
        // Simulates tmux: scroll region is rows 0-22, status bar at row 23
        // Scrollback grows as content scrolls within the scroll region
        val state = TerminalScreenState(snapshot(rows = 24, scrollback = emptyList()))

        // 20 scroll operations (each pushes one line to scrollback)
        for (i in 1..20) {
            val sb = (1..i).map { "tmux output $it" }
            state.updateSnapshot(snapshot(rows = 24, scrollback = sb, seqNum = i.toLong()))
            assertEquals(
                "Position should stay at bottom after scroll $i",
                0,
                state.scrollbackPosition,
            )
        }
    }

    // --- scrollBy and scrollToBottom interaction ---

    @Test
    fun `scrollToBottom resets after scrollBy`() {
        val state = TerminalScreenState(
            snapshot(scrollback = (1..10).map { "line $it" }),
        )
        state.scrollBy(5)
        assertEquals(5, state.scrollbackPosition)

        state.scrollToBottom()
        assertEquals(0, state.scrollbackPosition)
        assertTrue(state.isAtBottom())
    }

    @Test
    fun `scrollBy negative from scrolled position moves toward bottom`() {
        val state = TerminalScreenState(
            snapshot(scrollback = (1..10).map { "line $it" }),
        )
        state.scrollBy(8)
        assertEquals(8, state.scrollbackPosition)

        state.scrollBy(-3) // Scroll down 3
        assertEquals(5, state.scrollbackPosition)
    }

    @Test
    fun `scrollBy negative past bottom clamps to zero`() {
        val state = TerminalScreenState(
            snapshot(scrollback = (1..10).map { "line $it" }),
        )
        state.scrollBy(3)
        assertEquals(3, state.scrollbackPosition)

        state.scrollBy(-10) // Scroll way past bottom
        assertEquals(0, state.scrollbackPosition)
    }

    @Test
    fun `scrollBy positive past top clamps to scrollback size`() {
        val state = TerminalScreenState(
            snapshot(scrollback = (1..10).map { "line $it" }),
        )

        state.scrollBy(100) // Way past top
        assertEquals(10, state.scrollbackPosition)
    }

    // --- Sequence of snapshot updates simulating an animated status line ---

    @Test
    fun `animated status line - in-place updates without scrollback growth`() {
        // A TUI that redraws its status line in-place (cursor movement + line
        // clear) should produce no scrollback growth — position stays at bottom.
        val state = TerminalScreenState(snapshot(scrollback = (1..5).map { "line $it" }))

        // 50 rapid snapshot updates with SAME scrollback size (in-place cursor updates)
        for (i in 1..50) {
            val screen = List(24) { row ->
                if (row == 0) "thinking... frame $i" else ""
            }
            state.updateSnapshot(
                snapshot(screenLines = screen, scrollback = (1..5).map { "line $it" }, seqNum = i.toLong()),
            )
            assertEquals(
                "Frame $i: position should stay at bottom for in-place update",
                0,
                state.scrollbackPosition,
            )
        }
    }

    @Test
    fun `animated status line with occasional scroll output`() {
        // Animated TUI with occasional scroll output (e.g. a tool printing
        // progress on one line and emitting occasional log lines above)
        val state = TerminalScreenState(snapshot(scrollback = emptyList()))
        var sbSize = 0

        for (i in 1..100) {
            // Every 10th frame, output causes a scroll (scrollback grows)
            if (i % 10 == 0) sbSize++

            val sb = (1..sbSize).map { "output $it" }
            state.updateSnapshot(
                snapshot(scrollback = if (sbSize > 0) sb else emptyList(), seqNum = i.toLong()),
            )
            assertEquals(
                "Frame $i: position should stay at bottom",
                0,
                state.scrollbackPosition,
            )
        }
    }

    // --- Edge case: empty scrollback transitions ---

    @Test
    fun `transition from no scrollback to scrollback`() {
        val state = TerminalScreenState(snapshot(scrollback = emptyList()))
        assertEquals(0, state.scrollbackPosition)

        state.updateSnapshot(snapshot(scrollback = listOf("first line"), seqNum = 1))
        assertEquals(0, state.scrollbackPosition)
    }

    @Test
    fun `transition from scrollback to no scrollback while at bottom`() {
        val state = TerminalScreenState(
            snapshot(scrollback = listOf("sb1", "sb2", "sb3")),
        )
        assertEquals(0, state.scrollbackPosition)

        // Clear screen or screen reset clears scrollback
        state.updateSnapshot(snapshot(scrollback = emptyList(), seqNum = 1))
        assertEquals(0, state.scrollbackPosition)
    }

    @Test
    fun `transition from scrollback to no scrollback while scrolled up`() {
        val state = TerminalScreenState(
            snapshot(scrollback = listOf("sb1", "sb2", "sb3")),
        )
        state.scrollBy(2)
        assertEquals(2, state.scrollbackPosition)

        // Clear screen resets scrollback
        state.updateSnapshot(snapshot(scrollback = emptyList(), seqNum = 1))

        // Position clamped: 2 + (0 - 3) = -1, clamped to 0
        assertEquals(0, state.scrollbackPosition)
    }

    // --- Accidental touch drift scenario ---

    @Test
    fun `small scroll drift grows with scrollback updates`() {
        // Simulate: user accidentally touches screen, scrollbackPosition set to 1
        val state = TerminalScreenState(
            snapshot(scrollback = (1..10).map { "line $it" }),
        )
        state.scrollBy(1) // Accidental touch
        assertEquals(1, state.scrollbackPosition)

        // Scrollback grows 5 times — position drifts further from bottom
        for (i in 1..5) {
            val sb = (1..10 + i).map { "line $it" }
            state.updateSnapshot(snapshot(scrollback = sb, seqNum = i.toLong()))
            assertEquals(
                "After growth $i, position should drift",
                1 + i,
                state.scrollbackPosition,
            )
        }

        // Position drifted to 6 — this is the bug scenario.
        // The auto-scroll fix in Terminal.kt would catch this at position 1–3,
        // before it drifts further. Here we verify the drift mechanics.
        assertEquals(6, state.scrollbackPosition)
    }

    @Test
    fun `scrollToBottom recovers from drift`() {
        val state = TerminalScreenState(
            snapshot(scrollback = (1..10).map { "line $it" }),
        )
        state.scrollBy(1)
        // Let it drift
        state.updateSnapshot(snapshot(scrollback = (1..15).map { "line $it" }, seqNum = 1))
        assertEquals(6, state.scrollbackPosition)

        // Manual recovery (keyboard input or auto-scroll fix)
        state.scrollToBottom()
        assertEquals(0, state.scrollbackPosition)

        // Subsequent updates should stay at bottom
        state.updateSnapshot(snapshot(scrollback = (1..20).map { "line $it" }, seqNum = 2))
        assertEquals(0, state.scrollbackPosition)
    }

    // --- getVisibleLine consistency after updateSnapshot ---

    @Test
    fun `getVisibleLine returns correct content after scrollback growth`() {
        val screen = listOf("current A", "current B", "current C")
        val state = TerminalScreenState(
            snapshot(rows = 3, cols = 80, screenLines = screen, scrollback = listOf("old1")),
        )

        // New content scrolls, adding to scrollback
        val newScreen = listOf("current B", "current C", "new line")
        state.updateSnapshot(
            snapshot(
                rows = 3,
                cols = 80,
                screenLines = newScreen,
                scrollback = listOf("old1", "current A"),
                seqNum = 1,
            ),
        )

        // At bottom: should show the new screen lines
        assertEquals("current B", state.getVisibleLine(0).text.trimEnd())
        assertEquals("current C", state.getVisibleLine(1).text.trimEnd())
        assertEquals("new line", state.getVisibleLine(2).text.trimEnd())
    }

    @Test
    fun `getVisibleLine shows consistent content while scrolled up during growth`() {
        val state = TerminalScreenState(
            snapshot(
                rows = 3,
                cols = 80,
                screenLines = listOf("screen A", "screen B", "screen C"),
                scrollback = listOf("sb1", "sb2", "sb3"),
            ),
        )
        state.scrollBy(1) // Scroll up 1 line

        // Visible: sb3, screen A, screen B
        assertEquals("sb3", state.getVisibleLine(0).text.trimEnd())
        assertEquals("screen A", state.getVisibleLine(1).text.trimEnd())
        assertEquals("screen B", state.getVisibleLine(2).text.trimEnd())

        // Scrollback grows by 1
        state.updateSnapshot(
            snapshot(
                rows = 3,
                cols = 80,
                screenLines = listOf("screen B", "screen C", "new"),
                scrollback = listOf("sb1", "sb2", "sb3", "screen A"),
                seqNum = 1,
            ),
        )

        // scrollbackPosition adjusted: 1 + (4 - 3) = 2
        assertEquals(2, state.scrollbackPosition)

        // Should show same RELATIVE content: sb3, screen A, screen B
        assertEquals("sb3", state.getVisibleLine(0).text.trimEnd())
        assertEquals("screen A", state.getVisibleLine(1).text.trimEnd())
        assertEquals("screen B", state.getVisibleLine(2).text.trimEnd())
    }
}
