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

import android.content.res.Configuration
import android.view.inputmethod.InputMethodManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reported on a Meta Quest 3 with a Bluetooth keyboard: the virtual keyboard rose
 * over the session on every keypress.
 *
 * SHOW_FORCED is documented to hold the IME open until the user explicitly closes
 * it, which overrides the platform rule that a usable hardware keyboard suppresses
 * the soft one. The policy here is only about which flags to pass; whether the
 * platform then shows the keyboard is its call, and still respects the user's
 * "show virtual keyboard" setting.
 */
@Suppress("DEPRECATION")
class ImeShowFlagsTest {

    @Test
    fun `no physical keyboard keeps SHOW_FORCED`() {
        assertEquals(
            "a touch-only device still needs the forced show that made this reliable",
            InputMethodManager.SHOW_FORCED,
            imeShowFlags(
                keyboard = Configuration.KEYBOARD_NOKEYS,
                hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES,
            ),
        )
    }

    @Test
    fun `an attached usable keyboard drops the forced show`() {
        assertEquals(
            "SHOW_FORCED here is what puts the virtual keyboard over the session",
            0,
            imeShowFlags(
                keyboard = Configuration.KEYBOARD_QWERTY,
                hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO,
            ),
        )
    }

    /**
     * A folded or undocked device reports a keyboard whose keys cannot be
     * reached. The soft keyboard is the only way to type, so it must still be
     * forced up.
     */
    @Test
    fun `a hidden physical keyboard keeps SHOW_FORCED`() {
        assertEquals(
            InputMethodManager.SHOW_FORCED,
            imeShowFlags(
                keyboard = Configuration.KEYBOARD_QWERTY,
                hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_YES,
            ),
        )
    }

    @Test
    fun `a 12-key pad counts as physical`() {
        assertEquals(
            0,
            imeShowFlags(
                keyboard = Configuration.KEYBOARD_12KEY,
                hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO,
            ),
        )
    }
}
