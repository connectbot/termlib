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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleDragTest {

    // Initial selection: start=(row=2, col=10), end=(row=2, col=40)

    private fun drag(
        isMovingStart: Boolean,
        newRow: Int,
        newCol: Int,
        startRow: Int = 2,
        startCol: Int = 10,
        endRow: Int = 2,
        endCol: Int = 40,
    ) = applyHandleDrag(startRow, startCol, endRow, endCol, isMovingStart, newRow, newCol)

    // --- No crossing ---

    @Test
    fun movingStart_noCross_updatesStartOnly() {
        val r = drag(isMovingStart = true, newRow = 2, newCol = 5)
        assertEquals(2, r.startRow)
        assertEquals(5, r.startCol)
        assertEquals(2, r.endRow)
        assertEquals(40, r.endCol) // anchor unchanged
        assertTrue(r.isMovingStart)
    }

    @Test
    fun movingEnd_noCross_updatesEndOnly() {
        val r = drag(isMovingStart = false, newRow = 2, newCol = 50)
        assertEquals(2, r.startRow)
        assertEquals(10, r.startCol) // anchor unchanged
        assertEquals(2, r.endRow)
        assertEquals(50, r.endCol)
        assertFalse(r.isMovingStart)
    }

    @Test
    fun movingStart_samePosition_noFlip() {
        val r = drag(isMovingStart = true, newRow = 2, newCol = 10)
        assertTrue(r.isMovingStart)
        assertEquals(10, r.startCol)
        assertEquals(40, r.endCol)
    }

    // --- Crossing on the same row ---

    @Test
    fun movingStart_pastEnd_sameRow_flipsOwnership() {
        // Start at col 10, dragged to col 50 (past end at col 40)
        val r = drag(isMovingStart = true, newRow = 2, newCol = 50)
        assertFalse("should now be moving end", r.isMovingStart)
    }

    @Test
    fun movingStart_pastEnd_sameRow_anchorPreserved() {
        // Dragging start(col=10) past end(col=40) to col=50.
        // After flip: anchor (old end=col40) becomes the new start; finger becomes new end.
        val r = drag(isMovingStart = true, newRow = 2, newCol = 50)
        assertEquals("anchor (old end) should become new start col", 40, r.startCol)
        assertEquals("anchor row unchanged", 2, r.startRow)
    }

    @Test
    fun movingStart_pastEnd_sameRow_fingerBecomesEnd() {
        val r = drag(isMovingStart = true, newRow = 2, newCol = 50)
        // anchor (old end=col40) → new start; finger (col50) → new end
        assertEquals(40, r.startCol)
        assertEquals(50, r.endCol)
    }

    @Test
    fun movingEnd_pastStart_sameRow_flipsOwnership() {
        // End at col 40, dragged to col 5 (past start at col 10)
        val r = drag(isMovingStart = false, newRow = 2, newCol = 5)
        assertTrue("should now be moving start", r.isMovingStart)
    }

    @Test
    fun movingEnd_pastStart_sameRow_anchorPreserved() {
        // Dragging end(col=40) past start(col=10) to col=5.
        // After flip: anchor (old start=col10) becomes new end; finger becomes new start.
        val r = drag(isMovingStart = false, newRow = 2, newCol = 5)
        assertEquals("anchor (old start) should become new end col", 10, r.endCol)
        assertEquals(2, r.endRow)
    }

    @Test
    fun movingEnd_pastStart_sameRow_fingerBecomesStart() {
        val r = drag(isMovingStart = false, newRow = 2, newCol = 5)
        // anchor (old start=col10) → new end; finger (col5) → new start
        assertEquals(5, r.startCol)
        assertEquals(10, r.endCol)
    }

    // --- Crossing on a different row ---

    @Test
    fun movingStart_toRowBelowEnd_flips() {
        // Start row=2, end row=2 — drag start to row=3 (below end)
        val r = drag(isMovingStart = true, newRow = 3, newCol = 20)
        assertFalse(r.isMovingStart)
        // Old end (anchor) preserved as new start
        assertEquals(2, r.startRow)
        assertEquals(40, r.startCol)
        // Finger becomes new end
        assertEquals(3, r.endRow)
        assertEquals(20, r.endCol)
    }

    @Test
    fun movingEnd_toRowAboveStart_flips() {
        // start=(row=4,col=10), end=(row=6,col=40) — drag end to row=3
        val r = applyHandleDrag(
            startRow = 4,
            startCol = 10,
            endRow = 6,
            endCol = 40,
            isMovingStart = false,
            newRow = 3,
            newCol = 20,
        )
        assertTrue(r.isMovingStart)
        // Old start (anchor) preserved as new end
        assertEquals(4, r.endRow)
        assertEquals(10, r.endCol)
        // Finger becomes new start
        assertEquals(3, r.startRow)
        assertEquals(20, r.startCol)
    }

    // --- Exact boundary: same row, same col as anchor (not yet crossed) ---

    @Test
    fun movingStart_exactlyAtEnd_noFlip() {
        // Dragging start to exactly where end is — not past it, no flip
        val r = drag(isMovingStart = true, newRow = 2, newCol = 40)
        assertTrue(r.isMovingStart)
    }

    @Test
    fun movingEnd_exactlyAtStart_noFlip() {
        val r = drag(isMovingStart = false, newRow = 2, newCol = 10)
        assertFalse(r.isMovingStart)
    }

    // --- The original bug: stationary handle must not inherit the dragged column ---

    @Test
    fun bug_stationaryHandleDoesNotInheritDraggedColumn() {
        // Reproduces the reported bug:
        // Start=(row=3, col=20), End=(row=5, col=40).
        // User drags the end handle (isMovingStart=false) leftward past the start on the same row.
        // Old code: after crossing, start would jump to col=15 (the dragged col).
        // Fix: start stays at col=20 (its pre-cross position).
        val r = applyHandleDrag(
            startRow = 3,
            startCol = 20,
            endRow = 3,
            endCol = 40,
            isMovingStart = false,
            newRow = 3,
            newCol = 15, // dragged end left past start col=20 → crossing
        )
        assertTrue(r.isMovingStart)
        // The old start handle (anchor) must preserve col=20, not jump to col=15 or col=40
        assertEquals("anchor column must not change", 20, r.endCol)
        assertEquals(3, r.endRow)
        // The finger position becomes the new start
        assertEquals(15, r.startCol)
        assertEquals(3, r.startRow)
    }
}
