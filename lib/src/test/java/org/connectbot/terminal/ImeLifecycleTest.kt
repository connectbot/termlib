/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.app.Activity
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 34])
class ImeLifecycleTest {
    @Test
    fun detachingCancelsAnUndispatchedShow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        var shows = 0
        val view = ImeInputView(
            activity.get(),
            KeyboardHandler(TerminalEmulatorFactory.create()),
            onShowKeyboard = { shows++ },
        )
        val parent = LinearLayout(activity.get()).apply { addView(view) }
        activity.get().setContentView(parent)
        activity.visible().windowFocusChanged(true)
        view.showIme()
        parent.removeView(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, shows)
        parent.addView(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, shows)
        activity.pause().stop().destroy()
    }

    @Test
    fun requestsWaitForWindowFocusAndCanReopenTheFocusedEditor() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        var shows = 0
        val view = ImeInputView(
            activity.get(),
            KeyboardHandler(TerminalEmulatorFactory.create()),
            onShowKeyboard = { shows++ },
        )
        view.showIme()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, shows)
        activity.get().setContentView(view)
        activity.visible().windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shows)
        view.observeImeVisibility(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shows)
        view.showIme()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, shows)
        activity.pause().stop().destroy()
    }

    @Test
    fun disablingCancelsPendingRequestsAndDoesNotHideAnotherEditor() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        var shows = 0
        var hides = 0
        val view = ImeInputView(
            activity.get(),
            KeyboardHandler(TerminalEmulatorFactory.create()),
            onShowKeyboard = { shows++ },
            onHideKeyboard = { hides++ },
        )
        val other = EditText(activity.get())
        activity.get().setContentView(
            LinearLayout(activity.get()).apply {
                orientation = LinearLayout.VERTICAL
                addView(view, LinearLayout.LayoutParams(100, 100))
                addView(other, LinearLayout.LayoutParams(100, 100))
            },
        )
        activity.visible().windowFocusChanged(true)
        view.showIme()
        view.imeAllowed = false
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, shows)
        view.showIme()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, shows)
        assertTrue(other.requestFocus())
        val before = hides
        view.hideIme()
        assertEquals(before, hides)
        activity.pause().stop().destroy()
    }

    @Test
    fun windowReturnRestoresOnlyPreviouslyVisibleKeyboard() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        var shows = 0
        val view = ImeInputView(
            activity.get(),
            KeyboardHandler(TerminalEmulatorFactory.create()),
            onShowKeyboard = { shows++ },
        )
        activity.get().setContentView(view)
        activity.visible().windowFocusChanged(true)
        view.showIme()
        shadowOf(Looper.getMainLooper()).idle()
        view.observeImeVisibility(true)
        activity.windowFocusChanged(false)
        activity.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, shows)
        view.observeImeVisibility(false)
        activity.windowFocusChanged(false)
        activity.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, shows)
        activity.pause().stop().destroy()
    }
}
