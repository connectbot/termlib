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

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SelectionControllerTest {
    private lateinit var selectionManager: SelectionManager
    private lateinit var selectionController: SelectionController
    private var copiedText: String = ""

    @Before
    fun setup() {
        selectionManager = SelectionManager()

        // Create a simple implementation of SelectionController for testing
        selectionController = object : SelectionController {
            override val isSelectionActive: Boolean
                get() = selectionManager.mode != SelectionMode.NONE

            override fun startSelection(mode: SelectionMode) {
                if (selectionManager.mode == SelectionMode.NONE) {
                    // Start at a default position for testing
                    selectionManager.startSelection(10, 20, mode)
                }
            }

            override fun toggleSelection() {
                if (selectionManager.mode == SelectionMode.NONE) {
                    startSelection()
                } else {
                    clearSelection()
                }
            }

            override fun moveSelectionUp() {
                selectionManager.moveSelectionUp(25)
            }

            override fun moveSelectionDown() {
                selectionManager.moveSelectionDown(25)
            }

            override fun moveSelectionLeft() {
                selectionManager.moveSelectionLeft(80)
            }

            override fun moveSelectionRight() {
                selectionManager.moveSelectionRight(80)
            }

            override fun toggleSelectionMode() {
                selectionManager.toggleMode(80)
            }

            override fun finishSelection() {
                selectionManager.endSelection()
            }

            override fun copySelection(): String {
                val snapshot = createTestSnapshot()
                val text = selectionManager.getSelectedText(snapshot, 0)
                if (text.isNotEmpty()) {
                    copiedText = text
                    selectionManager.clearSelection()
                }
                return text
            }

            override fun clearSelection() {
                selectionManager.clearSelection()
            }
        }
    }

    @Test
    fun testInitialState() {
        assertFalse(selectionController.isSelectionActive)
    }

    @Test
    fun testStartSelection() {
        selectionController.startSelection()

        assertTrue(selectionController.isSelectionActive)
        assertNotNull(selectionManager.selectionRange)
    }

    @Test
    fun testStartSelectionWithMode() {
        selectionController.startSelection(SelectionMode.LINE)

        assertTrue(selectionController.isSelectionActive)
        assertEquals(SelectionMode.LINE, selectionManager.mode)
    }

    @Test
    fun testToggleSelectionOn() {
        assertFalse(selectionController.isSelectionActive)

        selectionController.toggleSelection()

        assertTrue(selectionController.isSelectionActive)
    }

    @Test
    fun testToggleSelectionOff() {
        selectionController.startSelection()
        assertTrue(selectionController.isSelectionActive)

        selectionController.toggleSelection()

        assertFalse(selectionController.isSelectionActive)
    }

    @Test
    fun testMoveSelectionUp() {
        selectionController.startSelection()
        val originalRange = selectionManager.selectionRange!!

        selectionController.moveSelectionUp()

        val newRange = selectionManager.selectionRange!!
        assertEquals(originalRange.endRow - 1, newRange.endRow)
    }

    @Test
    fun testMoveSelectionDown() {
        selectionController.startSelection()
        val originalRange = selectionManager.selectionRange!!

        selectionController.moveSelectionDown()

        val newRange = selectionManager.selectionRange!!
        assertEquals(originalRange.endRow + 1, newRange.endRow)
    }

    @Test
    fun testMoveSelectionLeft() {
        selectionController.startSelection()
        val originalRange = selectionManager.selectionRange!!

        selectionController.moveSelectionLeft()

        val newRange = selectionManager.selectionRange!!
        assertEquals(originalRange.endCol - 1, newRange.endCol)
    }

    @Test
    fun testMoveSelectionRight() {
        selectionController.startSelection()
        val originalRange = selectionManager.selectionRange!!

        selectionController.moveSelectionRight()

        val newRange = selectionManager.selectionRange!!
        assertEquals(originalRange.endCol + 1, newRange.endCol)
    }

    @Test
    fun testToggleSelectionMode() {
        selectionController.startSelection(SelectionMode.BLOCK)
        assertEquals(SelectionMode.BLOCK, selectionManager.mode)

        selectionController.toggleSelectionMode()

        assertEquals(SelectionMode.LINE, selectionManager.mode)
    }

    @Test
    fun testFinishSelection() {
        selectionController.startSelection()
        assertTrue(selectionManager.isSelecting)

        selectionController.finishSelection()

        assertFalse(selectionManager.isSelecting)
        assertTrue(selectionController.isSelectionActive) // Still active, just not extending
    }

    @Test
    fun testCopySelection() {
        selectionController.startSelection()
        selectionController.moveSelectionDown()
        selectionController.moveSelectionRight()
        selectionController.finishSelection()

        val text = selectionController.copySelection()

        assertFalse(text.isEmpty())
        assertFalse(selectionController.isSelectionActive) // Cleared after copy
        assertEquals(text, copiedText) // Verify text was "copied"
    }

    @Test
    fun testCopySelectionWhenNoSelection() {
        val text = selectionController.copySelection()

        assertTrue(text.isEmpty())
    }

    @Test
    fun testClearSelection() {
        selectionController.startSelection()
        assertTrue(selectionController.isSelectionActive)

        selectionController.clearSelection()

        assertFalse(selectionController.isSelectionActive)
        assertNull(selectionManager.selectionRange)
    }

    @Test
    fun testCompleteWorkflow() {
        // Start selection
        assertFalse(selectionController.isSelectionActive)
        selectionController.startSelection()
        assertTrue(selectionController.isSelectionActive)
        assertTrue(selectionManager.isSelecting)

        // Extend selection
        selectionController.moveSelectionDown()
        selectionController.moveSelectionDown()
        selectionController.moveSelectionRight()
        selectionController.moveSelectionRight()
        selectionController.moveSelectionRight()

        val range = selectionManager.selectionRange!!
        assertEquals(10, range.startRow)
        assertEquals(20, range.startCol)
        assertEquals(12, range.endRow)
        assertEquals(23, range.endCol)

        // Finish extending
        selectionController.finishSelection()
        assertFalse(selectionManager.isSelecting)
        assertTrue(selectionController.isSelectionActive)

        // Copy and clear
        val text = selectionController.copySelection()
        assertFalse(text.isEmpty())
        assertFalse(selectionController.isSelectionActive)
    }

    @Test
    fun testNavigateSelectionAfterFinished() {
        selectionController.startSelection()
        selectionController.finishSelection()

        // After finished, moving should move entire selection
        val originalRange = selectionManager.selectionRange!!
        selectionController.moveSelectionDown()

        val newRange = selectionManager.selectionRange!!
        assertEquals(originalRange.startRow + 1, newRange.startRow)
        assertEquals(originalRange.endRow + 1, newRange.endRow)
    }

    @Test
    fun testStartSelectionWhenAlreadyActive() {
        selectionController.startSelection()
        val range1 = selectionManager.selectionRange

        // Starting again should not change the selection
        selectionController.startSelection()
        val range2 = selectionManager.selectionRange

        assertEquals(range1, range2)
    }

    @Test
    fun testMultipleToggleCycles() {
        // Toggle on
        selectionController.toggleSelection()
        assertTrue(selectionController.isSelectionActive)

        // Toggle off
        selectionController.toggleSelection()
        assertFalse(selectionController.isSelectionActive)

        // Toggle on again
        selectionController.toggleSelection()
        assertTrue(selectionController.isSelectionActive)

        // Toggle off again
        selectionController.toggleSelection()
        assertFalse(selectionController.isSelectionActive)
    }

    @Test
    fun testMovementsRespectBoundaries() {
        selectionController.startSelection()

        // Move up many times to hit boundary
        repeat(20) {
            selectionController.moveSelectionUp()
        }

        val range = selectionManager.selectionRange!!
        assertTrue(range.endRow >= 0) // Should not go negative
    }

    // Helper function to create a test snapshot
    private fun createTestSnapshot(): TerminalSnapshot {
        val lines = mutableListOf<TerminalLine>()
        for (row in 0 until 25) {
            val cells = mutableListOf<TerminalLine.Cell>()
            for (col in 0 until 80) {
                cells.add(
                    TerminalLine.Cell(
                        char = if (col < 10) ' ' else ('A' + (col % 26)),
                        fgColor = Color.White,
                        bgColor = Color.Black
                    )
                )
            }
            lines.add(TerminalLine(row, cells))
        }

        return TerminalSnapshot(
            lines = lines,
            scrollback = emptyList(),
            cursorRow = 10,
            cursorCol = 20,
            cursorVisible = true,
            cursorBlink = true,
            cursorShape = CursorShape.BLOCK,
            terminalTitle = "",
            rows = 25,
            cols = 80,
            timestamp = 0L,
            sequenceNumber = 0L
        )
    }
}
