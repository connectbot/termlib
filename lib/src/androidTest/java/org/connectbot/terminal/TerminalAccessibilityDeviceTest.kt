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
import android.text.Spanned
import android.text.style.URLSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

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
        val labels = mutableListOf<String>()
        find(root) {
            labels.add("${it.text ?: it.contentDescription} (click=${it.isClickable})")
            false
        }
        throw AssertionError("Accessibility node did not appear; root=${root?.packageName}, focus=${focus?.text ?: focus?.contentDescription}, visible=$rows, nodes=$labels")
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
    private fun installHistory(count: Int = 20, toolbar: Boolean = false, content: String? = null, keyboard: Boolean = false): TerminalEmulatorImpl {
        lateinit var emulator: TerminalEmulatorImpl
        activityRule.scenario.onActivity { activity ->
            emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 40) as TerminalEmulatorImpl
            emulator.writeInput((content ?: (0 until count).joinToString("") { "history $it\r\n" }).toByteArray())
            emulator.processPendingUpdates()
            activity.setContentView(
                ComposeView(activity).apply {
                    setContent {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            if (toolbar) TextButton(onClick = {}) { Text("Up") }
                            Box(Modifier.weight(1f).imePadding()) {
                                TerminalWithAccessibility(emulator, forceAccessibilityEnabled = true, forcedSize = 5 to 40, keyboardEnabled = keyboard, showSoftKeyboard = false)
                            }
                        }
                    }
                },
            )
        }
        return emulator
    }

    private fun button(label: String): AccessibilityNodeInfo = node {
        it.isClickable && find(it) { child -> child.text?.toString() == label } != null
    }

    /** Compose buttons require touch exploration to grant accessibility focus. */
    @Test fun resumeLiveOutputKeepsFocusOnTheReviewToggle() {
        assumeTrue(talkBackMode)
        val emulator = installHistory(toolbar = true)
        if (talkBackMode) SystemClock.sleep(1500)
        val row = node { it.text?.toString()?.trim() == "history 19" }
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        node { it.contentDescription == "Review output" && it.className == "android.widget.ScrollView" }
        val resume = button("Resume live output")
        assertTrue(resume.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        node { it == resume && it.isAccessibilityFocused }
        assertTrue(resume.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        node { it == resume && it.isAccessibilityFocused && find(it) { child -> child.text?.toString() == "Review output" } != null }
        node { it.contentDescription == "Terminal output" && it.className == "android.widget.ScrollView" }
        activityRule.scenario.onActivity {
            emulator.writeInput("live again".toByteArray())
            emulator.processPendingUpdates()
        }
        node { it.text?.toString()?.trim() == "live again" }
        // Give TalkBack time to finish processing the removed controls and content changes.
        SystemClock.sleep(1000)
        node { it == resume && it.isAccessibilityFocused && find(it) { child -> child.text?.toString() == "Review output" } != null }
    }

    @Test fun resumeActionKeepsFocusOnLiveTerminal() {
        val emulator = installHistory(toolbar = true)
        if (talkBackMode) SystemClock.sleep(1500)
        val row = node { it.text?.toString()?.trim() == "history 19" }
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        val focused = node { it.isAccessibilityFocused && it.text?.toString()?.trim() == "history 19" }
        assertTrue(focused.performAction(R.id.terminal_a11y_resume))
        node { it.isAccessibilityFocused && it.contentDescription == "Terminal output" }
        activityRule.scenario.onActivity {
            emulator.writeInput("live again".toByteArray())
            emulator.processPendingUpdates()
        }
        node { it.text?.toString()?.trim() == "live again" }
        SystemClock.sleep(1000)
        node { it.isAccessibilityFocused && it.contentDescription == "Terminal output" }
    }

    @Test fun returnToInputActionsOpenTheCurrentIme() {
        installHistory(toolbar = true, keyboard = true)
        if (talkBackMode) SystemClock.sleep(1500)
        fun waitForIme(visible: Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                activityRule.scenario.onActivity { activity ->
                    val shown = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    ready = shown == visible && (!visible || activity.currentFocus is ImeInputView)
                }
                if (ready) return
                SystemClock.sleep(50)
            }
            throw AssertionError("Expected IME visible=$visible and terminal input focus")
        }
        for (customAction in listOf(true, false)) {
            val row = node { it.text?.toString()?.trim() == "history 19" }
            row.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            node { it.contentDescription == "Review output" && it.className == "android.widget.ScrollView" }
            if (customAction) {
                assertTrue(node { it.isAccessibilityFocused }.performAction(R.id.terminal_a11y_input))
            } else {
                val input = button("Return to input")
                input.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            waitForIme(true)
            SystemClock.sleep(500)
            waitForIme(true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitForIme(false)
        }
    }

    /** With talkback=true, drive double-tap-and-hold through the hardware bridge. */
    @Test fun longPressStartsAndStopsSelection() {
        installHistory(content = "select this\r\nnext row")
        if (talkBackMode) SystemClock.sleep(1500)
        var row = node { it.text?.toString()?.trim() == "select this" }
        row.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        row = node { it.isAccessibilityFocused && it.text?.toString()?.trim() == "select this" }
        assertTrue(row.isLongClickable)
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 2)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 2)
        }
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection))
        val bounds = Rect()
        row.getBoundsInScreen(bounds)
        fun longPress() {
            if (talkBackMode) {
                hardwareInput("longpress ${bounds.centerX()} ${bounds.centerY()}")
            } else {
                assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            }
        }
        longPress()
        row = node {
            it.isAccessibilityFocused && it.text?.toString()?.trim() == "select this" &&
                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_LONG_CLICK && action.label == "Stop selection" }
        }
        assertEquals(2, row.textSelectionStart)
        val args = Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER) }
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args))
        row = node { it.isAccessibilityFocused && it.textSelectionStart == 2 && it.textSelectionEnd == 3 }
        longPress()
        row = node {
            it.isAccessibilityFocused && it.textSelectionStart == 2 && it.textSelectionEnd == 3 &&
                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_LONG_CLICK && action.label == "Start selection" }
        }
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_COPY))
        activityRule.scenario.onActivity { activity ->
            val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals("l", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
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

    @androidx.test.filters.SdkSuppress(minSdkVersion = 37)
    @Test
    fun frameworkExtendedSelectionAddressesPhysicalRowsInsideBlankRun() {
        assumeTrue(!talkBackMode)
        installHistory(content = "before\r\n" + "\r\n".repeat(7) + "after")
        val blank = node { it.stateDescription?.toString() == "7 blank lines" }
        assertTrue(blank.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        val host = node { it.contentDescription == "Review output" && it.className == "android.widget.ScrollView" }
        // Physical rows have 40 columns and one separating newline in the grouped node.
        val first = 41 + 5
        val last = 3 * 41 + 10
        val args = Bundle().apply {
            putParcelable(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_PARCELABLE,
                AccessibilityNodeInfo.Selection(
                    AccessibilityNodeInfo.SelectionPosition(blank, first),
                    AccessibilityNodeInfo.SelectionPosition(blank, last),
                ),
            )
        }
        assertTrue(host.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_EXTENDED_SELECTION.id, args))
        val selected = node { it == host && it.selection?.start?.offset == first && it.selection?.end?.offset == last }.selection!!
        assertEquals(blank, selected.start.node)
        assertEquals(blank, selected.end.node)
        assertTrue(blank.performAction(AccessibilityNodeInfo.ACTION_COPY))
        activityRule.scenario.onActivity { activity ->
            val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals("\n\n" + " ".repeat(10), clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    private val gestureRun = SystemClock.uptimeMillis()
    private var gestureId = 0

    private fun hardwareInput(command: String) {
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

    private fun installLinks(): List<String> {
        val opened = CopyOnWriteArrayList<String>()
        activityRule.scenario.onActivity { activity ->
            val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 40) as TerminalEmulatorImpl
            fun link(label: String, url: String) = "\u001B]8;;$url\u001B\\$label\u001B]8;;\u001B\\"
            emulator.writeInput((link("one", "https://one.example") + "  " + link("two", "https://two.example") + "  " + link("three", "https://one.example")).toByteArray())
            emulator.processPendingUpdates()
            activity.setContentView(
                ComposeView(activity).apply {
                    setContent {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            TerminalWithAccessibility(emulator, forceAccessibilityEnabled = true, forcedSize = 5 to 40, onHyperlinkClick = { opened.add(it) })
                        }
                    }
                },
            )
        }
        return opened
    }

    private fun awaitOpened(opened: List<String>, count: Int) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (opened.size < count && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(count, opened.size)
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 26)
    @Test
    fun frameworkActivatesIndividualSpansAndRejectsStaleLinks() {
        assumeTrue(!talkBackMode)
        val opened = installLinks()
        val row = node { it.text?.toString()?.trim() == "one  two  three" }
        val text = row.text as Spanned
        val spans = text.getSpans(0, text.length, URLSpan::class.java)
        assertEquals(3, spans.size)
        assertEquals(listOf(0, 5, 10), spans.map { text.getSpanStart(it) })
        val widget = View(instrumentation.targetContext)
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        node { it.isAccessibilityFocused && it == row }
        // These spans came from a live node, before entering frozen review.
        spans[1].onClick(widget)
        awaitOpened(opened, 1)
        spans[0].onClick(widget)
        awaitOpened(opened, 2)
        spans[2].onClick(widget)
        awaitOpened(opened, 3)
        assertEquals(listOf("https://two.example", "https://one.example", "https://one.example"), opened)
        assertTrue(row.performAction(R.id.terminal_a11y_start_line))
        node { it == row && it.textSelectionEnd == 0 }
        assertTrue(row.performAction(R.id.terminal_a11y_next_link))
        node { it == row && it.textSelectionEnd == 5 }
        assertTrue(row.performAction(R.id.terminal_a11y_open_link))
        awaitOpened(opened, 4)
        assertEquals("https://two.example", opened.last())
        assertTrue(row.performAction(R.id.terminal_a11y_end_line))
        node { it == row && it.textSelectionEnd == 40 }
        assertTrue(!row.performAction(R.id.terminal_a11y_open_link))
        assertTrue(row.performAction(R.id.terminal_a11y_refresh))
        node { it.isAccessibilityFocused && it != row }
        spans[1].onClick(widget)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        assertEquals(4, opened.size)
    }

    /** Held hardware touch enters exploration before moving within an already focused row. */
    @androidx.test.filters.SdkSuppress(minSdkVersion = 31)
    @Test
    fun talkBackTouchTargetsCharactersAndIndividualLinks() {
        assumeTrue(talkBackMode)
        val opened = installLinks()
        SystemClock.sleep(1500)
        val row = node { it.text?.toString()?.trim() == "one  two  three" }
        val bounds = Rect().also(row::getBoundsInScreen)
        var cellWidth = 0f
        activityRule.scenario.onActivity { activity ->
            fun terminal(view: View): TerminalAccessibilityView? {
                if (view is TerminalAccessibilityView) return view
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) terminal(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            cellWidth = checkNotNull(terminal(activity.window.decorView)).charWidth
        }
        fun explore(from: Int, to: Int) {
            val start = (bounds.left + (from + 0.5f) * cellWidth).toInt()
            val end = (bounds.left + (to + 0.5f) * cellWidth).toInt()
            hardwareInput("explore $start ${bounds.centerY()} $end ${bounds.centerY()} 1000")
            node { it == row && it.isAccessibilityFocused && it.textSelectionEnd == to }
        }
        explore(1, 1)
        assertTrue(row.performAction(R.id.terminal_a11y_open_link))
        awaitOpened(opened, 1)
        explore(1, 6)
        assertTrue(row.performAction(R.id.terminal_a11y_open_link))
        awaitOpened(opened, 2)
        explore(6, 7)
        assertTrue(row.performAction(R.id.terminal_a11y_open_link))
        awaitOpened(opened, 3)
        explore(7, 11)
        assertTrue(row.performAction(R.id.terminal_a11y_open_link))
        awaitOpened(opened, 4)
        assertEquals(listOf("https://one.example", "https://two.example", "https://two.example", "https://one.example"), opened)
        explore(11, 4)
        assertTrue(!row.performAction(R.id.terminal_a11y_open_link))
    }

    /** A run longer than the viewport must still require only one swipe to cross. */
    @Test fun talkBackSwipesSkipWholeBlankRuns() {
        assumeTrue(talkBackMode)
        for (count in listOf(7, 70)) {
            installHistory(content = "before\r\n" + "\r\n".repeat(count) + "after")
            SystemClock.sleep(1500)
            val after = node { it.text?.toString()?.trim() == "after" }
            after.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            node { it.isAccessibilityFocused && it.text?.toString()?.trim() == "after" }
            val bounds = Rect()
            after.getBoundsInScreen(bounds)
            fun swipe(forward: Boolean) {
                val start = bounds.centerX() + if (forward) -250 else 250
                val end = bounds.centerX() + if (forward) 250 else -250
                hardwareInput("swipe $start ${bounds.centerY()} $end ${bounds.centerY()} 200")
            }
            for (forward in listOf(false, true)) {
                swipe(forward)
                node { it.isAccessibilityFocused && it.stateDescription?.toString() == "$count blank lines" }
                SystemClock.sleep(300)
                swipe(forward)
                node { it.isAccessibilityFocused && it.text?.toString()?.trim() == if (forward) "after" else "before" }
                SystemClock.sleep(300)
            }
        }
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
        fun swipe(forward: Boolean) {
            val start = bounds.centerX() + if (forward) -250 else 250
            val finish = bounds.centerX() + if (forward) 250 else -250
            // Emulator gRPC sends hardware touch events through the accessibility input filter.
            hardwareInput("swipe $start ${bounds.centerY()} $finish ${bounds.centerY()} 200")
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
