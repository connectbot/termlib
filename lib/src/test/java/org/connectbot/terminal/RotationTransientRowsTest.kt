package org.connectbot.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotating portrait -> landscape -> portrait with the soft keyboard up
 * measures the window at its full height for one frame — the new
 * configuration's height arrives before the IME inset re-dispatches — so the
 * sizing path computes a row count that is too large (device-verified: 40 rows
 * where the settled keyboard-up grid is 23, with the IME inset reading hidden
 * while the keyboard is actually up). That transient reaches the emulator grid
 * AND the PTY; the server repaints its prompt for the oversized grid, and the
 * later shrink strands that repaint at the top row while the real prompt sits
 * at the bottom — a duplicate prompt pinned to the top.
 *
 * The sample is untrustworthy exactly when the IME is supposed to be shown but
 * its inset reads hidden — a full-height window and a visible IME are mutually
 * exclusive — and the measurement would GROW the grid into that phantom space.
 * A genuine keyboard hide also reads the inset hidden, but then shouldShowIme
 * is false (the host owns showSoftKeyboard), so it must still resize.
 */
class RotationTransientRowsTest {

    @Test
    fun rotationBackPhantomGrowWithKeyboardUpIsHeld() {
        assertTrue(
            "the phantom sample (inset reads hidden while the IME should be " +
                "shown, growing rows) must be held back from the emulator + PTY",
            isRotationTransientRows(
                shouldShowIme = true,
                imeVisible = false,
                growingRows = true,
            ),
        )
    }

    @Test
    fun settledSizeWithInsetVisibleIsNotHeld() {
        assertFalse(
            isRotationTransientRows(
                shouldShowIme = true,
                imeVisible = true,
                growingRows = true,
            ),
        )
    }

    @Test
    fun genuineKeyboardHideStillResizes() {
        assertFalse(
            "a keyboard hide (shouldShowIme=false) must never be held",
            isRotationTransientRows(
                shouldShowIme = false,
                imeVisible = false,
                growingRows = true,
            ),
        )
    }

    @Test
    fun rotationShrinkIsNotHeld() {
        assertFalse(
            isRotationTransientRows(
                shouldShowIme = true,
                imeVisible = false,
                growingRows = false,
            ),
        )
    }

    @Test
    fun physicalKeyboardIsNotHeld() {
        assertFalse(
            isRotationTransientRows(
                shouldShowIme = false,
                imeVisible = false,
                growingRows = true,
            ),
        )
    }
}
