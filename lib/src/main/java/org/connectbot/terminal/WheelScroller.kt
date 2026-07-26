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

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
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
 * Most detents a single fling may report.
 *
 * The local scrollback path is bounded by the scrollback it has; the wheel path
 * has no comparable limit, because the terminal cannot know where the
 * application's own history begins or ends. Without a bound, a hard fling keeps
 * emitting detents long after the application has hit its top, so this caps a
 * fling well above what an ordinary one covers.
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

    /** Travel not yet worth a whole detent, carried into the next sample. */
    private var residualPx = 0f

    /**
     * Report the detents covered by [deltaPx] of vertical travel while a finger
     * is down.
     *
     * Positive values mean the finger moved down the screen, which reveals
     * earlier output and so reports as wheel up.
     *
     * Travel beyond the per-sample limit is discarded rather than queued. While
     * the finger is down the gesture is a position, not a distance: a backlog
     * would keep reporting after the finger has stopped, which reads as the
     * content sliding out from under it.
     */
    fun scrollBy(deltaPx: Float) {
        report(deltaPx, limit = MAX_DETENTS_PER_SAMPLE, carryExcess = false)
    }

    /**
     * Report the detents covered by a fling, decaying [initialVelocityPx] on
     * [decaySpec] — the same curve the local scrollback path flings on, so the
     * gesture feels the same whoever ends up handling it.
     *
     * The decay lives here rather than in the gesture handler because it is
     * inseparable from the conversion: the detent budget that stops a hard fling
     * is spent by the reports, not by the distance travelled. Keeping both in
     * one place also means the fling can be driven by a test frame clock instead
     * of only by a real one.
     *
     * Unlike [scrollBy] this carries travel past the per-sample limit into the
     * following frames rather than dropping it. A fling is a distance, and the
     * decay's slow tail gives the carried detents somewhere to go — so how far a
     * fling scrolls does not depend on how many frames the device managed to
     * draw during it.
     */
    suspend fun fling(initialVelocityPx: Float, decaySpec: DecayAnimationSpec<Float>) {
        if (pixelsPerDetent <= 0f || !initialVelocityPx.isFinite()) return

        var budget = MAX_DETENTS_PER_FLING
        var lastValue = 0f
        AnimationState(initialValue = 0f, initialVelocity = initialVelocityPx)
            .animateDecay(decaySpec) {
                budget -= report(
                    deltaPx = value - lastValue,
                    limit = min(MAX_DETENTS_PER_SAMPLE, budget),
                    carryExcess = true,
                )
                lastValue = value
                if (budget <= 0) cancelAnimation()
            }
    }

    /**
     * Convert [deltaPx] of travel into at most [limit] detents and report them,
     * returning how many were sent.
     *
     * When [carryExcess] is true, travel past [limit] stays in the residual for
     * the next call; otherwise it is dropped.
     */
    private fun report(deltaPx: Float, limit: Int, carryExcess: Boolean): Int {
        if (pixelsPerDetent <= 0f || !deltaPx.isFinite() || limit < 1) return 0

        residualPx += deltaPx
        val wanted = (residualPx / pixelsPerDetent).toInt()
        if (wanted == 0) return 0

        val sent = min(abs(wanted), limit)
        val consumed = if (carryExcess) sent else abs(wanted)
        residualPx -= if (wanted > 0) consumed * pixelsPerDetent else -consumed * pixelsPerDetent

        emulator.scrollWheel(
            direction = if (wanted > 0) WheelDirection.UP else WheelDirection.DOWN,
            row = anchorRow,
            col = anchorCol,
            steps = sent,
        )
        return sent
    }
}
