/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Requires an unlocked device with an enabled software IME. Uses no Espresso hidden APIs. */
@RunWith(AndroidJUnit4::class)
class TerminalImeLifecycleTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(ComponentActivity::class.java)
    private lateinit var activity: ComponentActivity
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setup() {
        activityRule.scenario.onActivity { activity = it }
    }

    private fun imeVisible(): Boolean = ViewCompat.getRootWindowInsets(activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        throw AssertionError(message)
    }

    private fun settleLayout() {
        // Allow real popup/IME animations to complete (not Compose's virtual clock).
        SystemClock.sleep(500)
        instrumentation.waitForIdleSync()
    }

    private fun setContent(content: @Composable () -> Unit) {
        activity.setContentView(ComposeView(activity).apply { setContent(content) })
    }

    @Test
    fun backThenTapReopensKeyboardWithoutChangingEditors() {
        val terminal = TerminalEmulatorFactory.create()
        val enabled = mutableStateOf(true)
        var reportedVisibility: Boolean? = null
        instrumentation.runOnMainSync {
            setContent {
                Box(Modifier.fillMaxSize().imePadding()) {
                    Terminal(
                        terminal,
                        Modifier.fillMaxSize(),
                        keyboardEnabled = true,
                        showSoftKeyboard = enabled.value,
                        onImeVisibilityChanged = { reportedVisibility = it },
                    )
                }
            }
        }
        waitUntil("Keyboard did not open on entry") { imeVisible() && reportedVisibility == true }
        settleLayout()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("Back did not dismiss the keyboard") { !imeVisible() && reportedVisibility == false }
        terminal.writeInput("still connected\r\n".toByteArray())
        settleLayout()
        instrumentation.runOnMainSync { assertFalse(imeVisible()) }
        tapTerminal()
        waitUntil("Tapping the focused terminal did not reopen the keyboard") { imeVisible() && reportedVisibility == true }
        instrumentation.runOnMainSync { enabled.value = false }
        waitUntil("Host opt-out did not hide the keyboard") { !imeVisible() && reportedVisibility == false }
        settleLayout()
        tapTerminal()
        settleLayout()
        instrumentation.runOnMainSync { assertFalse("A tap must honor showSoftKeyboard=false", imeVisible()) }
    }

    private fun tapTerminal() {
        val now = SystemClock.uptimeMillis()
        val location = IntArray(2)
        instrumentation.runOnMainSync { activity.window.decorView.getLocationOnScreen(location) }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, location[0] + 100f, location[1] + 200f, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    @Test
    fun menuRoundTripRetainsTerminalDimensions() {
        val menu = mutableStateOf(false)
        val suspended = mutableStateOf(false)
        val sizes = CopyOnWriteArrayList<TerminalDimensions>()
        val terminal = TerminalEmulatorFactory.create(onResize = { sizes.add(it) }) as TerminalEmulatorImpl
        instrumentation.runOnMainSync {
            setContent {
                Box(Modifier.fillMaxSize().imePadding()) {
                    Terminal(terminal, Modifier.fillMaxSize(), keyboardEnabled = true, resizeSuspended = suspended.value)
                    DropdownMenu(expanded = menu.value, onDismissRequest = { menu.value = false }) {
                        Text("Settings")
                    }
                }
            }
        }
        waitUntil("Keyboard did not open on entry") { imeVisible() }
        settleLayout()
        terminal.commands.call { Unit }
        val original = terminal.dimensions
        sizes.clear()
        instrumentation.runOnMainSync {
            suspended.value = true
            menu.value = true
        }
        settleLayout()
        instrumentation.runOnMainSync { menu.value = false }
        waitUntil("Menu did not restore window focus and keyboard") { activity.hasWindowFocus() && imeVisible() }
        settleLayout()
        instrumentation.runOnMainSync { suspended.value = false }
        settleLayout()
        terminal.commands.call { Unit }
        assertTrue("Temporary menu layouts must not reach the remote PTY (original=$original): $sizes", sizes.isEmpty())
    }
}
