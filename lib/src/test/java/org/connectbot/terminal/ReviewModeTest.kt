/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ReviewModeTest {
    @get:Rule val rule = createComposeRule()

    private fun bridge(view: View): TerminalAccessibilityView? {
        if (view is TerminalAccessibilityView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) bridge(view.getChildAt(index))?.let { return it }
        return null
    }

    @Test fun nativeProviderFreezesWhileLiveTerminalContinues() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40) as TerminalEmulatorImpl
        lateinit var root: View
        lateinit var controller: TerminalAccessibilityController
        rule.setContent {
            root = LocalView.current
            TerminalWithAccessibility(
                emulator,
                forceAccessibilityEnabled = true,
                onAccessibilityControllerAvailable = { controller = it },
            )
        }
        rule.runOnIdle {
            emulator.writeInput("before review\r\n".toByteArray())
            emulator.processPendingUpdates()
        }
        rule.waitForIdle()
        var frozen = ""
        rule.runOnIdle {
            val view = bridge(root)!!
            val node = view.provider.findAccessibilityNodeInfosByText("before review", -1)!!.first()
            assertEquals("before review", node.text.toString().trim())
            // Focus the corresponding virtual row through the actual provider.
            assertTrue(view.focusRow(0))
            frozen = view.provider.findFocus(AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY)!!.text.toString()
            assertTrue(controller.isReviewing)
            emulator.writeInput("after review\r\n".toByteArray())
            emulator.processPendingUpdates()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            val view = bridge(root)!!
            assertEquals(frozen, view.provider.findFocus(AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY)!!.text.toString())
            assertTrue(emulator.snapshot.value.lines.any { "after review" in it.text })
            controller.resumeLiveOutput()
        }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(controller.isReviewing) }
    }

    @Test fun cancellingStandardEditorDoesNotSendInput() {
        val output = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40, onKeyboardInput = { output.add(it) })
        rule.setContent { TerminalWithAccessibility(emulator, keyboardEnabled = true, showSoftKeyboard = false, forceAccessibilityEnabled = true) }
        rule.onNodeWithText("Compose command").performClick()
        rule.onNodeWithText("Command").performTextInput("echo must-not-send")
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertTrue(output.isEmpty()) }
    }

    @Test fun standardEditorSendsDraftExactlyOnceWithOptionalEnter() {
        val output = mutableListOf<ByteArray>()
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40, onKeyboardInput = { output.add(it) }) as TerminalEmulatorImpl
        rule.setContent { TerminalWithAccessibility(emulator, keyboardEnabled = true, showSoftKeyboard = false, forceAccessibilityEnabled = true) }
        rule.onNodeWithText("Compose command").performClick()
        rule.onNodeWithText("Command").performTextInput("echo hello")
        rule.runOnIdle { assertTrue(output.isEmpty()) }
        rule.onNodeWithText("Send and Enter").performClick()
        emulator.commands.call { }
        shadowOf(Looper.getMainLooper()).idle()
        rule.runOnIdle { assertEquals("echo hello\r", output.flatMap { it.toList() }.toByteArray().decodeToString()) }
    }
}
