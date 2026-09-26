/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class TerminalMemoryTest {
    @Test
    fun snapshotsSurviveSparseUpdatesAndRotation() {
        val terminal = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80) as TerminalEmulatorImpl
        terminal.writeInput("\u001B[Hhello 🎉\u0301".toByteArray())
        terminal.processPendingUpdates()
        val old = terminal.snapshot.value
        val text = old.lines[0].text
        val before = terminal.transferBytes
        terminal.writeInput("\u001B[1;2HX".toByteArray())
        terminal.processPendingUpdates()
        assertTrue(terminal.transferBytes - before < 80 * CellData.BYTES)
        assertSame(old.lines[1], terminal.snapshot.value.lines[1])
        assertEquals(text, old.lines[0].text)
        repeat(10) {
            terminal.resize(40, 48)
            terminal.processPendingUpdates()
            assertEquals(48, terminal.snapshot.value.lines[0].cells.size)
            terminal.resize(24, 80)
            terminal.processPendingUpdates()
            assertEquals(80, terminal.snapshot.value.lines[0].cells.size)
        }
        assertEquals(text, old.lines[0].text)
        val unchanged = terminal.snapshot.value
        terminal.resize(24, 80)
        terminal.processPendingUpdates()
        assertSame(unchanged, terminal.snapshot.value)
    }

    @Test
    fun cursorAndIdenticalRedrawReuseRows() {
        val terminal = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80) as TerminalEmulatorImpl
        val input = "\u001B[Hhello".toByteArray()
        terminal.writeInput(input)
        terminal.processPendingUpdates()
        val previous = terminal.snapshot.value.lines
        terminal.writeInput(input)
        terminal.processPendingUpdates()
        assertSame(previous, terminal.snapshot.value.lines)
        val calls = terminal.transferCalls
        terminal.writeInput("\u001B[2;2H".toByteArray())
        terminal.processPendingUpdates()
        assertSame(previous, terminal.snapshot.value.lines)
        assertEquals(calls, terminal.transferCalls)
    }

    @Test
    fun selectionSurvivesNativeWidthReflow() {
        val terminal = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 6) as TerminalEmulatorImpl
        terminal.writeInput("abcdefghi".toByteArray())
        terminal.processPendingUpdates()
        val beforeResize = terminal.snapshot.value
        val selection = SelectionManager()
        selection.startSelection(0, 2, beforeResize.cols, SelectionMode.CHARACTER, beforeResize)
        selection.updateSelection(1, 1)
        val selectedText = selection.getSelectedText(beforeResize)

        terminal.resize(4, 3)
        terminal.processPendingUpdates()
        val afterResize = terminal.snapshot.value
        selection.onSnapshotChanged(beforeResize, afterResize)

        assertEquals(selectedText, selection.getSelectedText(afterResize))
    }

    @Test
    fun selectionSurvivesNativeHeightGrowthAndScrollbackPop() {
        val terminal = TerminalEmulatorFactory.create(initialRows = 2, initialCols = 10) as TerminalEmulatorImpl
        terminal.writeInput("first\r\nsecond\r\nthird".toByteArray())
        terminal.processPendingUpdates()
        val beforeResize = terminal.snapshot.value
        assertTrue(beforeResize.scrollback.isNotEmpty())
        val selection = SelectionManager()
        selection.startSelection(0, 1, beforeResize.cols, SelectionMode.CHARACTER, beforeResize)
        selection.updateSelection(beforeResize.scrollback.size + 1, 2)
        val selectedText = selection.getSelectedText(beforeResize)

        terminal.resize(4, 10)
        terminal.processPendingUpdates()
        val afterResize = terminal.snapshot.value
        selection.onSnapshotChanged(beforeResize, afterResize)

        assertEquals(selectedText, selection.getSelectedText(afterResize))
    }

    @Test
    fun concurrentResizeInputAndCapturePublishConsistentDimensions() {
        val terminal = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80) as TerminalEmulatorImpl
        val failure = AtomicReference<Throwable>()
        val writer = thread {
            try {
                repeat(50) { terminal.writeInput("hello 🎉\r\n".toByteArray()) }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        val resizer = thread {
            try {
                repeat(20) { terminal.resize(if (it % 2 == 0) 40 else 24, if (it % 2 == 0) 48 else 80) }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        repeat(50) {
            terminal.processPendingUpdates()
            val snapshot = terminal.snapshot.value
            assertEquals(snapshot.rows, snapshot.lines.size)
            assertTrue(snapshot.lines.all { it.cells.size == snapshot.cols })
        }
        writer.join(10000)
        resizer.join(10000)
        assertTrue(!writer.isAlive && !resizer.isAlive)
        failure.get()?.let { throw AssertionError(it) }
    }
}
