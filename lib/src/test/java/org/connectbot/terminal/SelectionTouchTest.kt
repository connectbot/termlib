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

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTouchTest {
    @Test fun handleReachesEdgesBeforeFingerHitsCaseWithoutJumpingOnGrab() {
        val down = Offset(180f, 300f)
        val anchor = Offset(200f, 270f)
        fun drag(point: Offset) = selectionHandleDragPosition(point, down, anchor, 400f, 600f, 48f)
        assertEquals(anchor, drag(down))
        assertEquals(Offset.Zero, drag(Offset(48f, 48f)))
        assertEquals(Offset(400f, 600f), drag(Offset(352f, 552f)))
        assertTrue((drag(down + Offset(0.1f, 0.1f)) - anchor).getDistance() < 1f)
    }

    @Test fun handleGrabbedNearEdgeStillMovesContinuously() {
        val down = Offset(20f, 20f)
        val anchor = Offset(5f, 5f)
        assertEquals(anchor, selectionHandleDragPosition(down, down, anchor, 400f, 600f, 48f))
        val moved = selectionHandleDragPosition(Offset(19f, 19f), down, anchor, 400f, 600f, 48f)
        assertTrue(moved.x > 0f && moved.x < anchor.x)
        assertTrue(moved.y > 0f && moved.y < anchor.y)
    }

    @Test fun oneHandleDragCanReverseAcrossBothEdges() {
        val down = Offset(180f, 300f)
        val anchor = Offset(250f, 280f)
        val positions = listOf(352f, 300f, 180f, 100f, 48f, 100f, 180f, 300f, 352f)
        val targets = positions.map {
            selectionHandleDragPosition(Offset(it, down.y), down, anchor, 400f, 600f, 48f)
        }
        assertEquals(400f, targets.first().x, 0.001f)
        assertEquals(0f, targets[4].x, 0.001f)
        assertEquals(400f, targets.last().x, 0.001f)
        assertEquals(anchor, targets[2])
        assertEquals(anchor, targets[6])
        assertTrue(targets.take(5).zipWithNext().all { (a, b) -> a.x >= b.x })
        assertTrue(targets.drop(4).zipWithNext().all { (a, b) -> a.x <= b.x })
        assertTrue(targets.all { it.y == anchor.y })
    }

    @Test fun widerCaseClearanceAppliesToDirectSelection() {
        assertEquals(Offset.Zero, edgeReachPosition(Offset(48f, 48f), 400f, 600f, 48f, 192f))
        assertEquals(Offset(400f, 600f), edgeReachPosition(Offset(352f, 552f), 400f, 600f, 48f, 192f))
    }

    @Test fun edgeReachIsSymmetricAndLeavesMiddleUntouched() {
        assertEquals(0f, edgeReachAxis(24f, 400f, 24f, 96f), 0.001f)
        assertEquals(400f, edgeReachAxis(376f, 400f, 24f, 96f), 0.001f)
        assertEquals(96f, edgeReachAxis(96f, 400f, 24f, 96f), 0.001f)
        assertEquals(200f, edgeReachAxis(200f, 400f, 24f, 96f), 0.001f)
        assertEquals(304f, edgeReachAxis(304f, 400f, 24f, 96f), 0.001f)
        for (x in 0..400 step 5) {
            assertEquals(400f, edgeReachAxis(x.toFloat(), 400f, 24f, 96f) + edgeReachAxis(400f - x, 400f, 24f, 96f), 0.001f)
        }
    }

    @Test fun edgeReachCoversAllFourEdgesAndSmallViews() {
        val topLeft = edgeReachPosition(Offset(20f, 20f), 400f, 600f, 24f, 96f)
        val bottomRight = edgeReachPosition(Offset(380f, 580f), 400f, 600f, 24f, 96f)
        assertEquals(Offset.Zero, topLeft)
        assertEquals(Offset(400f, 600f), bottomRight)
        assertEquals(Offset(50f, 50f), edgeReachPosition(Offset(50f, 50f), 100f, 100f, 24f, 96f))
        assertEquals(0f, edgeReachAxis(24f, 100f, 24f, 96f), 0.001f)
        assertEquals(100f, edgeReachAxis(76f, 100f, 24f, 96f), 0.001f)
        assertEquals(0f, edgeReachAxis(5f, 0f, 24f, 96f), 0.001f)
    }

    @Test fun releaseFilterRestoresSettledSelectionAfterBriefWobble() {
        val filter = SelectionReleaseFilter<Int>()
        filter.record(10, 0)
        filter.record(20, 600)
        filter.record(21, 1000)
        assertEquals(20, filter.resultAtRelease(1010))
    }

    @Test fun releaseFilterKeepsIntentionalOrUnsettledMovement() {
        val deliberate = SelectionReleaseFilter<Int>()
        deliberate.record(10, 0)
        deliberate.record(20, 600)
        assertEquals(20, deliberate.resultAtRelease(800))
        val unsettled = SelectionReleaseFilter<Int>()
        unsettled.record(10, 0)
        unsettled.record(20, 100)
        assertEquals(20, unsettled.resultAtRelease(110))
        assertEquals(null, SelectionReleaseFilter<Int>().resultAtRelease(100))
    }

    @Test fun magnifierTargetIsCenteredAndOutlineChangesAtCellBoundary() {
        val scale = 2.5f
        val size = 200f
        val before = Offset(19.9f, 39.9f)
        val beforeSource = magnifierSourceTopLeft(before, size, scale)
        assertEquals(100f, (before.x - beforeSource.x) * scale, 0.001f)
        assertEquals(100f, (before.y - beforeSource.y) * scale, 0.001f)
        val beforeCell = magnifiedCellBounds(before, 10f, 20f, beforeSource, scale)
        assertTrue(beforeCell.contains(Offset(100f, 100f)))
        val after = Offset(20f, 40f)
        val afterSource = magnifierSourceTopLeft(after, size, scale)
        val afterCell = magnifiedCellBounds(after, 10f, 20f, afterSource, scale)
        assertEquals(100f, afterCell.left, 0.001f)
        assertEquals(100f, afterCell.top, 0.001f)
    }
}
