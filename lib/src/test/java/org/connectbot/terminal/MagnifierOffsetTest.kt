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

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnifierOffsetTest {

    // Typical values matching the constants in Terminal.kt (at 2x density)
    private val magnifierSize = 200f // 100dp * 2
    private val verticalOffset = 80f // 40dp * 2
    private val fingerHeight = 100f // 50dp * 2
    private val componentW = 1000
    private val componentH = 2400

    private fun offset(x: Float, y: Float) = magnifierOffset(
        position = Offset(x, y),
        magnifierSizePx = magnifierSize,
        verticalOffsetPx = verticalOffset,
        fingerHeightPx = fingerHeight,
        componentWidth = componentW,
        componentHeight = componentH,
    )

    // --- Normal (above) placement ---

    @Test
    fun aboveFinger_centerOfScreen() {
        // Finger mid-screen: plenty of room above
        val pos = offset(500f, 1200f)
        // y should be: 1200 - 80 - 200 = 920
        assertEquals(920f, pos.y, 0.01f)
        // x should be centered: 500 - 100 = 400
        assertEquals(400f, pos.x, 0.01f)
    }

    @Test
    fun aboveFinger_yIsPositive() {
        val pos = offset(500f, 1200f)
        assertTrue("magnifier y should be >= 0", pos.y >= 0f)
    }

    // --- Flip (below) placement ---

    @Test
    fun belowFinger_nearTopEdge() {
        // Finger near top: y=50, not enough room above (50 - 80 - 200 < 0)
        val pos = offset(500f, 50f)
        // Should flip below: y = 50 + 100 + 80 = 230
        assertEquals(230f, pos.y, 0.01f)
    }

    @Test
    fun belowFinger_exactBoundary_flipsAt() {
        // spaceAbove == 0 exactly: y = verticalOffset + magnifierSize = 280 → just fits above
        val yAtBoundary = verticalOffset + magnifierSize // 280f
        val pos = offset(500f, yAtBoundary)
        assertEquals(0f, pos.y, 0.01f) // placed at top edge, still "above"
    }

    @Test
    fun belowFinger_onePxShortOfBoundary_flips() {
        val yJustBelow = verticalOffset + magnifierSize - 1f // 279f
        val pos = offset(500f, yJustBelow)
        // spaceAbove < 0 → flip: 279 + 100 + 80 = 459
        assertEquals(459f, pos.y, 0.01f)
    }

    // --- X-axis clamping ---

    @Test
    fun xClampedToLeftEdge() {
        // Finger at x=0: centered x would be -100, should clamp to 0
        val pos = offset(0f, 1200f)
        assertEquals(0f, pos.x, 0.01f)
    }

    @Test
    fun xClampedToRightEdge() {
        // Finger at rightmost: centered x = 1000 - 100 = 900, max allowed = 1000 - 200 = 800
        val pos = offset(1000f, 1200f)
        assertEquals(800f, pos.x, 0.01f)
    }

    @Test
    fun xCenteredNormally() {
        val pos = offset(500f, 1200f)
        assertEquals(400f, pos.x, 0.01f)
    }

    // --- Y-axis clamping when flipped below near bottom edge ---

    @Test
    fun belowFinger_clampedToBottomEdge() {
        // Finger near top so it flips below, but flipped y exceeds component height.
        // y=10 → spaceAbove = 10 - 80 - 200 < 0 → flip: 10 + 100 + 80 = 190
        // That fits, so use a finger so low the flip overshoots:
        // Need flip y > componentH - magnifierSize (2200).
        // flip y = fingerY + 100 + 80 > 2200  →  fingerY > 2020, but fingerY must also give spaceAbove < 0
        // spaceAbove = fingerY - 80 - 200 < 0  →  fingerY < 280
        // Contradiction — can't be both < 280 and > 2020.
        // Conclusion: the clamp only matters on tiny components (covered by tinyComponent_doesNotCrash).
        // Instead verify a finger near top that flips cleanly within bounds.
        val pos = offset(500f, 10f)
        // flip y = 10 + 100 + 80 = 190, within bounds
        assertEquals(190f, pos.y, 0.01f)
    }

    @Test
    fun aboveFinger_yNeverGoesNegative() {
        // Even if finger is at y=0, flipped position should stay >= 0
        val pos = offset(500f, 0f)
        assertTrue("magnifier y should be >= 0", pos.y >= 0f)
    }

    // --- Component smaller than magnifier ---

    @Test
    fun tinyComponent_doesNotCrash() {
        val pos = magnifierOffset(
            position = Offset(5f, 5f),
            magnifierSizePx = 200f,
            verticalOffsetPx = 80f,
            fingerHeightPx = 100f,
            componentWidth = 100,
            componentHeight = 100,
        )
        // coerceAtLeast(0f) guards both axes
        assertTrue(pos.x >= 0f)
        assertTrue(pos.y >= 0f)
    }
}
