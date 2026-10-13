/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the framework's remote node connection, not only the Compose semantics tree. */
@RunWith(AndroidJUnit4::class)
class TerminalAccessibilityDeviceTest {
    @get:Rule val activityRule = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Accessibility node did not appear")
    }

    @Test fun frameworkCanFocusAndScrollFrozenHistory() {
        lateinit var emulator: TerminalEmulatorImpl
        activityRule.scenario.onActivity { activity ->
            emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 40) as TerminalEmulatorImpl
            emulator.writeInput((0 until 60).joinToString("") { "history $it\r\n" }.toByteArray())
            emulator.processPendingUpdates()
            activity.setContentView(
                ComposeView(activity).apply {
                    setContent { TerminalWithAccessibility(emulator, forceAccessibilityEnabled = true, forcedSize = 5 to 40) }
                },
            )
        }
        val row = node { it.text?.toString()?.trim() == "history 59" }
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        instrumentation.waitForIdleSync()
        activityRule.scenario.onActivity {
            emulator.writeInput((0 until 1100).joinToString("") { "new $it\r\n" }.toByteArray())
            emulator.processPendingUpdates()
        }
        assertEquals("history 59", node { it.isAccessibilityFocused }.text.toString().trim())
        repeat(20) {
            val host = node { it.className == "android.widget.ScrollView" && it.contentDescription == "Review output" }
            if (host.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) {
                assertTrue(host.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                instrumentation.waitForIdleSync()
            }
        }
        assertEquals("history 0", node { it.text?.toString()?.trim() == "history 0" }.text.toString().trim())
    }
}
