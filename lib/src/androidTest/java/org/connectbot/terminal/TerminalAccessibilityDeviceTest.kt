/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the framework's remote node connection, not only the Compose semantics tree. */
@RunWith(AndroidJUnit4::class)
class TerminalAccessibilityDeviceTest {
    @get:Rule val activityRule = ActivityScenarioRule(ComponentActivity::class.java)
    private val talkBackMode get() = InstrumentationRegistry.getArguments().getString("talkback") == "true"
    private val automation get() = instrumentation.getUiAutomation(if (talkBackMode) UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES else 0)
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
            find(automation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        val root = automation.rootInActiveWindow
        val focus = find(root) { it.isAccessibilityFocused }
        val terminal = find(root) { it.className == "android.widget.ScrollView" }
        val rows = terminal?.let { host -> (0 until host.childCount).map { host.getChild(it)?.text?.toString()?.trim() } }
        throw AssertionError("Accessibility node did not appear; root=${root?.packageName}, focus=${focus?.text ?: focus?.contentDescription}, visible=$rows")
    }

    @Test fun frameworkCanFocusAndScrollFrozenHistory() {
        assumeTrue(!talkBackMode)
        lateinit var emulator: TerminalEmulatorImpl
        activityRule.scenario.onActivity { activity ->
            emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 40) as TerminalEmulatorImpl
            emulator.writeInput((0 until 60).joinToString("") { "history $it\r\n" }.toByteArray())
            emulator.processPendingUpdates()
            activity.setContentView(
                ComposeView(activity).apply {
                    setContent {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            TerminalWithAccessibility(emulator, forceAccessibilityEnabled = true, forcedSize = 5 to 40)
                        }
                    }
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
    private fun installHistory(count: Int = 20): TerminalEmulatorImpl {
        lateinit var emulator: TerminalEmulatorImpl
        activityRule.scenario.onActivity { activity ->
            emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 40) as TerminalEmulatorImpl
            emulator.writeInput((0 until count).joinToString("") { "history $it\r\n" }.toByteArray())
            emulator.processPendingUpdates()
            activity.setContentView(
                ComposeView(activity).apply {
                    setContent {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            TerminalWithAccessibility(emulator, forceAccessibilityEnabled = true, forcedSize = 5 to 40)
                        }
                    }
                },
            )
        }
        return emulator
    }

    @Test fun frameworkGranularityAndSelectionCrossViewportBoundaries() {
        assumeTrue(!talkBackMode)
        installHistory()
        val row = node { it.text?.toString()?.trim() == "history 19" }
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        node { it.isAccessibilityFocused && it.text?.toString()?.trim() == "history 19" }
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
            putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, true)
        }
        for (index in 19 downTo 1) {
            val current = node { it.isAccessibilityFocused && it.text?.toString()?.trim() == "history $index" }
            assertTrue("Move backward from history $index", current.performAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, args))
            node { it.isAccessibilityFocused && it.text?.toString()?.trim() == "history ${index - 1}" }
        }
        assertEquals("history 0", node { it.isAccessibilityFocused }.text.toString().trim())
        assertTrue(node { it.isAccessibilityFocused }.performAction(AccessibilityNodeInfo.ACTION_COPY))
        activityRule.scenario.onActivity { activity ->
            val copied = activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
            assertTrue(copied.startsWith("history 0\nhistory 1\n"))
            assertTrue(copied.endsWith("history 18\n"))
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 37)
    @Test
    fun extendedSelectionValidatesRemoteEndpointsAndDocumentGeneration() {
        assumeTrue(!talkBackMode)
        installHistory()
        val first = node { it.text?.toString()?.trim() == "history 18" }
        val last = node { it.text?.toString()?.trim() == "history 19" }
        assertTrue(first.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        node { it.isAccessibilityFocused && it == first }
        val args = Bundle().apply {
            putParcelable(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_PARCELABLE,
                AccessibilityNodeInfo.Selection(
                    AccessibilityNodeInfo.SelectionPosition(first, 2),
                    AccessibilityNodeInfo.SelectionPosition(last, 5),
                ),
            )
        }
        val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_EXTENDED_SELECTION.id
        val host = node { it.className == "android.widget.ScrollView" && it.contentDescription == "Review output" }
        assertTrue(host.performAction(action, args))
        node { it.isAccessibilityFocused && it == last && it.textSelectionEnd == 5 }
        val selected = node {
            it == host && it.selection?.start?.offset == 2 && it.selection?.end?.offset == 5
        }.selection!!
        assertEquals(first, selected.start.node)
        assertEquals(last, selected.end.node)
        val foreign = node { it.text?.toString() == "Refresh review" }
        val invalid = Bundle().apply {
            putParcelable(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_PARCELABLE,
                AccessibilityNodeInfo.Selection(
                    AccessibilityNodeInfo.SelectionPosition(foreign, 0),
                    AccessibilityNodeInfo.SelectionPosition(last, 5),
                ),
            )
        }
        assertTrue(!host.performAction(action, invalid))
        assertTrue(first.performAction(R.id.terminal_a11y_refresh))
        node { it.isAccessibilityFocused && it != first && it != last }
        val refreshed = node { it == host }
        assertTrue(!refreshed.performAction(action, args))
        assertTrue(refreshed.performAction(action))
    }

    /** Requires TalkBack, the host touchscreen bridge, and instrumentation argument talkback=true. */
    @androidx.test.filters.SdkSuppress(minSdkVersion = 31)
    @Test
    fun talkBackSwipesNavigateIntoScrollbackAndBack() {
        assumeTrue(talkBackMode)
        val emulator = installHistory()
        val row = node { it.text?.toString()?.trim() == "history 19" }
        val bounds = Rect()
        row.getBoundsInScreen(bounds)
        val gestureRun = SystemClock.uptimeMillis()
        var gestureId = 0
        fun input(command: String) {
            val path = "/data/local/tmp/termlib-talkback-touch"
            val id = "$gestureRun${++gestureId}"
            fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().decodeToString().trim() }
            val pipes = automation.executeShellCommandRw("tee $path")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write("$id $command\n".toByteArray()) }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            val deadline = SystemClock.uptimeMillis() + 10000
            while (shell("cat $path.done") != id) {
                check(SystemClock.uptimeMillis() < deadline) { "Start tools/talkback-touch-bridge.py before running this test" }
                SystemClock.sleep(100)
            }
        }
        fun swipe(forward: Boolean) {
            val start = bounds.centerX() + if (forward) -250 else 250
            val finish = bounds.centerX() + if (forward) 250 else -250
            // Emulator gRPC sends hardware touch events through the accessibility input filter.
            input("swipe $start ${bounds.centerY()} $finish ${bounds.centerY()} 200")
        }
        fun waitForRow(index: Int) {
            try {
                node { it.isAccessibilityFocused && it.text?.toString()?.trim() == "history $index" }
            } catch (error: AssertionError) {
                throw AssertionError("Expected history $index; swipe bounds=$bounds: ${error.message}", error)
            }
            SystemClock.sleep(300)
        }
        // Use the focus TalkBack establishes for the new window, then navigate to
        // the last printed row through TalkBack itself. This avoids racing its
        // asynchronous initial-focus restoration with a provider focus action.
        SystemClock.sleep(1500)
        val initial = node { it.isAccessibilityFocused && it.text?.toString()?.trim()?.startsWith("history ") == true }
            .text.toString().trim().substringAfter("history ").toInt()
        for (index in initial + 1..19) {
            swipe(true)
            waitForRow(index)
        }
        activityRule.scenario.onActivity {
            emulator.writeInput((0 until 1100).joinToString("") { "new $it\r\n" }.toByteArray())
            emulator.processPendingUpdates()
        }
        instrumentation.waitForIdleSync()
        for (index in 18 downTo 0) {
            swipe(false)
            waitForRow(index)
        }
        for (index in 1..19) {
            swipe(true)
            waitForRow(index)
        }
    }
}
