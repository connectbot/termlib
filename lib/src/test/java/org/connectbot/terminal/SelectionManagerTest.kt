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

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SelectionManagerTest {
    private lateinit var selectionManager: SelectionManager

    @Before
    fun setup() {
        selectionManager = SelectionManager()
    }

    @Test
    fun testInitialState() {
        assertEquals(SelectionMode.NONE, selectionManager.mode)
        assertNull(selectionManager.selectionRange)
        assertFalse(selectionManager.isSelecting)
    }

    @Test
    fun testStartSelection() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)

        assertEquals(SelectionMode.BLOCK, selectionManager.mode)
        assertTrue(selectionManager.isSelecting)
        assertNotNull(selectionManager.selectionRange)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow)
        assertEquals(10, range.startCol)
        assertEquals(5, range.endRow)
        assertEquals(10, range.endCol)
    }

    @Test
    fun testMoveSelectionUpWhileSelecting() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)

        selectionManager.moveSelectionUp(20)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow) // Start unchanged
        assertEquals(10, range.startCol)
        assertEquals(4, range.endRow) // End moved up
        assertEquals(10, range.endCol)
    }

    @Test
    fun testMoveSelectionUpAtBoundary() {
        selectionManager.startSelection(0, 10, SelectionMode.BLOCK)

        selectionManager.moveSelectionUp(20)

        val range = selectionManager.selectionRange!!
        assertEquals(0, range.endRow) // Clamped at 0
    }

    @Test
    fun testMoveSelectionDownWhileSelecting() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)

        selectionManager.moveSelectionDown(20)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow) // Start unchanged
        assertEquals(10, range.startCol)
        assertEquals(6, range.endRow) // End moved down
        assertEquals(10, range.endCol)
    }

    @Test
    fun testMoveSelectionDownAtBoundary() {
        selectionManager.startSelection(19, 10, SelectionMode.BLOCK)

        selectionManager.moveSelectionDown(20)

        val range = selectionManager.selectionRange!!
        assertEquals(19, range.endRow) // Clamped at maxRow - 1
    }

    @Test
    fun testMoveSelectionLeftWhileSelecting() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)

        selectionManager.moveSelectionLeft(80)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow)
        assertEquals(10, range.startCol) // Start unchanged
        assertEquals(5, range.endRow)
        assertEquals(9, range.endCol) // End moved left
    }

    @Test
    fun testMoveSelectionLeftAtBoundary() {
        selectionManager.startSelection(5, 0, SelectionMode.BLOCK)

        selectionManager.moveSelectionLeft(80)

        val range = selectionManager.selectionRange!!
        assertEquals(0, range.endCol) // Clamped at 0
    }

    @Test
    fun testMoveSelectionRightWhileSelecting() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)

        selectionManager.moveSelectionRight(80)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow)
        assertEquals(10, range.startCol) // Start unchanged
        assertEquals(5, range.endRow)
        assertEquals(11, range.endCol) // End moved right
    }

    @Test
    fun testMoveSelectionRightAtBoundary() {
        selectionManager.startSelection(5, 79, SelectionMode.BLOCK)

        selectionManager.moveSelectionRight(80)

        val range = selectionManager.selectionRange!!
        assertEquals(79, range.endCol) // Clamped at maxCol - 1
    }

    @Test
    fun testMoveSelectionAfterFinished() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)
        selectionManager.endSelection()

        assertFalse(selectionManager.isSelecting)

        // Move down after finishing - both start and end should move
        selectionManager.moveSelectionDown(20)

        val range = selectionManager.selectionRange!!
        assertEquals(6, range.startRow) // Both moved down
        assertEquals(6, range.endRow)
    }

    @Test
    fun testMoveSelectionUpAfterFinished() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)
        selectionManager.endSelection()

        selectionManager.moveSelectionUp(20)

        val range = selectionManager.selectionRange!!
        assertEquals(4, range.startRow) // Both moved up
        assertEquals(4, range.endRow)
    }

    @Test
    fun testMoveSelectionLeftAfterFinished() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)
        selectionManager.endSelection()

        selectionManager.moveSelectionLeft(80)

        val range = selectionManager.selectionRange!!
        assertEquals(9, range.startCol) // Both moved left
        assertEquals(9, range.endCol)
    }

    @Test
    fun testMoveSelectionRightAfterFinished() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)
        selectionManager.endSelection()

        selectionManager.moveSelectionRight(80)

        val range = selectionManager.selectionRange!!
        assertEquals(11, range.startCol) // Both moved right
        assertEquals(11, range.endCol)
    }

    @Test
    fun testMultipleMovesWhileSelecting() {
        selectionManager.startSelection(10, 40, SelectionMode.BLOCK)

        // Move to create a rectangular selection
        selectionManager.moveSelectionDown(25)
        selectionManager.moveSelectionDown(25)
        selectionManager.moveSelectionRight(80)
        selectionManager.moveSelectionRight(80)
        selectionManager.moveSelectionRight(80)

        val range = selectionManager.selectionRange!!
        assertEquals(10, range.startRow)
        assertEquals(40, range.startCol)
        assertEquals(12, range.endRow) // Moved down 2
        assertEquals(43, range.endCol) // Moved right 3
    }

    @Test
    fun testClearSelectionResetsState() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)
        selectionManager.moveSelectionDown(20)
        selectionManager.moveSelectionRight(80)

        selectionManager.clearSelection()

        assertEquals(SelectionMode.NONE, selectionManager.mode)
        assertNull(selectionManager.selectionRange)
        assertFalse(selectionManager.isSelecting)
    }

    @Test
    fun testUpdateSelectionStart() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)
        selectionManager.endSelection()

        selectionManager.updateSelectionStart(3, 8)

        val range = selectionManager.selectionRange!!
        assertEquals(3, range.startRow)
        assertEquals(8, range.startCol)
        assertEquals(5, range.endRow) // End unchanged
        assertEquals(10, range.endCol)
    }

    @Test
    fun testUpdateSelectionEnd() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)
        selectionManager.endSelection()

        selectionManager.updateSelectionEnd(7, 12)

        val range = selectionManager.selectionRange!!
        assertEquals(5, range.startRow) // Start unchanged
        assertEquals(10, range.startCol)
        assertEquals(7, range.endRow)
        assertEquals(12, range.endCol)
    }

    @Test
    fun testToggleModeBlockToLine() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)
        selectionManager.endSelection()

        selectionManager.toggleMode(80)

        assertEquals(SelectionMode.LINE, selectionManager.mode)
        val range = selectionManager.selectionRange!!
        assertEquals(0, range.startCol) // Line mode uses full width
        assertEquals(79, range.endCol)
    }

    @Test
    fun testToggleModeLineToBlock() {
        selectionManager.startSelection(5, 10, SelectionMode.LINE)
        selectionManager.toggleMode(80)

        assertEquals(SelectionMode.BLOCK, selectionManager.mode)
    }

    @Test
    fun testIsCellSelectedInBlockMode() {
        selectionManager.startSelection(5, 10, SelectionMode.BLOCK)
        selectionManager.updateSelection(7, 15)
        selectionManager.endSelection()

        // Inside selection
        // Row 5: from col 10 onwards
        assertTrue(selectionManager.isCellSelected(5, 10))
        assertTrue(selectionManager.isCellSelected(5, 15))
        assertTrue(selectionManager.isCellSelected(5, 20))

        // Row 6: entire row (middle row)
        assertTrue(selectionManager.isCellSelected(6, 0))
        assertTrue(selectionManager.isCellSelected(6, 9))
        assertTrue(selectionManager.isCellSelected(6, 12))
        assertTrue(selectionManager.isCellSelected(6, 16))

        // Row 7: up to col 15
        assertTrue(selectionManager.isCellSelected(7, 0))
        assertTrue(selectionManager.isCellSelected(7, 10))
        assertTrue(selectionManager.isCellSelected(7, 15))

        // Outside selection
        assertFalse(selectionManager.isCellSelected(4, 10)) // Row before
        assertFalse(selectionManager.isCellSelected(8, 10)) // Row after
        assertFalse(selectionManager.isCellSelected(5, 9))  // Before startCol on first row
        assertFalse(selectionManager.isCellSelected(7, 16)) // After endCol on last row
    }

    @Test
    fun testIsCellSelectedInLineMode() {
        selectionManager.startSelection(5, 0, SelectionMode.LINE)
        selectionManager.updateSelection(7, 79)
        selectionManager.endSelection()

        // Entire rows 5, 6, 7 should be selected
        assertTrue(selectionManager.isCellSelected(5, 0))
        assertTrue(selectionManager.isCellSelected(5, 40))
        assertTrue(selectionManager.isCellSelected(6, 20))
        assertTrue(selectionManager.isCellSelected(7, 79))

        // Row 4 and 8 should not be selected
        assertFalse(selectionManager.isCellSelected(4, 0))
        assertFalse(selectionManager.isCellSelected(8, 0))
    }

    @Test
    fun testMoveWithoutStartingSelectionDoesNothing() {
        // Should not crash when moving without selection
        selectionManager.moveSelectionUp(20)
        selectionManager.moveSelectionDown(20)
        selectionManager.moveSelectionLeft(80)
        selectionManager.moveSelectionRight(80)

        assertNull(selectionManager.selectionRange)
    }

    @Test
    fun testComplexNavigationScenario() {
        // Start selection
        selectionManager.startSelection(10, 20, SelectionMode.BLOCK)
        assertTrue(selectionManager.isSelecting)

        // Extend selection by moving
        selectionManager.moveSelectionDown(25)
        selectionManager.moveSelectionDown(25)
        selectionManager.moveSelectionRight(80)
        selectionManager.moveSelectionRight(80)

        var range = selectionManager.selectionRange!!
        assertEquals(10, range.startRow)
        assertEquals(20, range.startCol)
        assertEquals(12, range.endRow)
        assertEquals(22, range.endCol)

        // Finish selection
        selectionManager.endSelection()
        assertFalse(selectionManager.isSelecting)

        // Now move the entire selection
        selectionManager.moveSelectionDown(25)
        selectionManager.moveSelectionRight(80)

        range = selectionManager.selectionRange!!
        assertEquals(11, range.startRow)
        assertEquals(21, range.startCol)
        assertEquals(13, range.endRow)
        assertEquals(23, range.endCol)

        // Clear it
        selectionManager.clearSelection()
        assertEquals(SelectionMode.NONE, selectionManager.mode)
    }
}
