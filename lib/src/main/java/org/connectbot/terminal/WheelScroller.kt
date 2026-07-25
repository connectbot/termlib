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

import kotlin.math.abs
import kotlin.math.min

/**
 * Pixels of finger travel per wheel detent, as a multiple of the line height.
 *
 * One detent per line makes the content track the finger for an application
 * that scrolls a single line per detent, which is the common case.
 */
private const val LINES_PER_WHEEL_DETENT = 1f

/**
 * Most detents to report from a single gesture sample.
 *
 * A fast fling can cover dozens of lines between animation frames. Applications
 * commonly throttle or coalesce a burst of wheel reports, so sending every
 * detent from such a frame can scroll *less* far than sending a few — besides
 * putting a pointless amount of traffic on the wire.
 */
private const val MAX_DETENTS_PER_SAMPLE = 8

/**
 * Most detents a single fling may report, as a bound on its decay distance.
 *
 * The local scrollback path is bounded by the scrollback it has; the wheel path
 * has no comparable limit, because the terminal cannot know where the
 * application's own history begins or ends. Without a bound, a hard fling keeps
 * emitting detents long after the application has hit its top, so this caps the
 * decay well above what an ordinary fling covers.
 */
private const val MAX_DETENTS_PER_FLING = 200

/**
 * Turns a continuous vertical scroll gesture into discrete wheel reports for an
 * application that has enabled mouse tracking.
 *
 * Such an application keeps its own scrollback and paints the whole screen, so
 * scrolling the terminal's scrollback would do nothing visible. Reporting the
 * wheel instead lets the application scroll itself.
 *
 * All reports are sent at a fixed anchor cell — the cell the gesture started
 * on. Chasing the finger would make every crossed row emit a motion report to
 * an application tracking in [MouseTracking.MOVE] mode, and the anchor is what
 * the gesture is aimed at anyway.
 *
 * @param emulator The emulator to report to
 * @param lineHeightPx Height of one terminal line in pixels
 * @param anchorRow Row (0-based) the gesture started on
 * @param anchorCol Column (0-based) the gesture started on
 */
internal class WheelScroller(
    private val emulator: TerminalEmulator,
    lineHeightPx: Float,
    private val anchorRow: Int,
    private val anchorCol: Int,
) {
    private val pixelsPerDetent = lineHeightPx * LINES_PER_WHEEL_DETENT

    /**
     * Travel a fling may cover before it should be stopped, as a bound for the
     * decay animation driving [scrollBy].
     */
    val maxFlingTravelPx = pixelsPerDetent * MAX_DETENTS_PER_FLING

    /** Travel not yet worth a whole detent, carried into the next sample. */
    private var residualPx = 0f

    /**
     * Report the detents covered by [deltaPx] of vertical travel.
     *
     * Positive values mean the finger moved down the screen, which reveals
     * earlier output and so reports as wheel up.
     */
    fun scrollBy(deltaPx: Float) {
        if (pixelsPerDetent <= 0f || !deltaPx.isFinite()) return

        residualPx += deltaPx
        val detents = (residualPx / pixelsPerDetent).toInt()
        if (detents == 0) return
        residualPx -= detents * pixelsPerDetent

        emulator.scrollWheel(
            direction = if (detents > 0) WheelDirection.UP else WheelDirection.DOWN,
            row = anchorRow,
            col = anchorCol,
            steps = min(abs(detents), MAX_DETENTS_PER_SAMPLE),
        )
    }
}
