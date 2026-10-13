/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.content.Context
import android.os.Build
import android.text.Spanned
import android.text.style.URLSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat as Node

@Config(sdk = [24, 34])
@RunWith(AndroidJUnit4::class)
class AccessibilityLinksAndTouchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var snapshot: TerminalSnapshot
    private lateinit var review: TerminalReviewSession
    private lateinit var view: TerminalAccessibilityView
    private val opened = mutableListOf<String>()

    private fun install(vararg lines: TerminalLine, history: List<TerminalLine> = emptyList(), cols: Int = 40) {
        snapshot = accessibilitySnapshot(0, lines.size).copy(lines = lines.toList(), scrollback = history, cols = cols)
        review = TerminalReviewSession { snapshot }
        view = TerminalAccessibilityView(context, review).apply {
            measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY))
            layout(0, 0, 200, 60)
            update(snapshot, 0, 10f, 20f)
            onLink = { opened.add(it) }
        }
        shadowOf(context.getSystemService(AccessibilityManager::class.java)).apply {
            setEnabled(true)
            setTouchExplorationEnabled(true)
        }
    }

    private fun linkedLine() = accessibilityLine("one  two  three").copy(
        semanticSegments = listOf(
            SemanticSegment(0, 3, SemanticType.HYPERLINK, "https://one.example"),
            SemanticSegment(5, 8, SemanticType.HYPERLINK, "https://two.example"),
            SemanticSegment(10, 15, SemanticType.HYPERLINK, "https://one.example"),
        ),
    )

    private fun hover(x: Float, y: Float = 10f, action: Int = MotionEvent.ACTION_HOVER_MOVE): Boolean {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        return try {
            view.dispatchGenericMotionEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun key(code: Int, meta: Int = 0) = view.reviewKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta))

    @Test fun longClickStartsANewRangeAtTheTouchedCharacter() {
        install(accessibilityLine("abcdef"))
        view.focusRow(0)
        review.document!!.selection.set(ReviewPosition(0, 1), ReviewPosition(0, 3))
        assertTrue(hover(45f))
        assertEquals(ReviewPosition(0, 3), review.document!!.selection.active)
        assertTrue(view.provider.performAction(view.id(0), Node.ACTION_LONG_CLICK, null))
        assertEquals(ReviewPosition(0, 4), review.document!!.selection.anchor)
        assertTrue(review.document!!.selection.isEmpty)
        assertTrue(review.document!!.selection.marking)
    }

    @Test fun occurrencesRetainRepeatedUrlsAndUseCellToUtf16Ranges() {
        val cells = listOf(
            TerminalLine.Cell('界', width = 2, fgColor = Color.White, bgColor = Color.Black),
            TerminalLine.Cell('e', listOf('\u0301'), Color.White, Color.Black),
            TerminalLine.Cell('x', fgColor = Color.White, bgColor = Color.Black),
        )
        val line = TerminalLine(0, cells, semanticSegments = listOf(SemanticSegment(2, 4, SemanticType.HYPERLINK, "https://accent.example")))
        install(linkedLine(), line)
        val links = TerminalReviewDocument(snapshot).links(false)
        assertEquals(4, links.size)
        assertEquals(links[0].url, links[2].url)
        assertEquals(ReviewPosition(0, 10), links[2].start)
        assertEquals("e\u0301x", links[3].label)
        assertEquals(1, links[3].ranges.single().start)
        assertEquals(4, links[3].ranges.single().end)
    }

    @Test fun detectionHonorsSettingOsc8PrecedenceAndWrappedOccurrences() {
        install(
            accessibilityLine("https://example.com/").copy(softWrapped = true),
            accessibilityLine("path".padEnd(40)),
            accessibilityLine("https://example.com/ other").copy(semanticSegments = listOf(SemanticSegment(0, 19, SemanticType.HYPERLINK, "https://override.example"))),
        )
        val doc = TerminalReviewDocument(snapshot)
        assertEquals(listOf("https://override.example"), doc.links(false).map { it.url })
        assertEquals(listOf("https://example.com/path", "https://override.example"), doc.links(true).map { it.url })
        assertEquals(2, doc.links(true)[0].ranges.size)
        assertTrue(doc.links(true)[0].contains(ReviewPosition(1, 3)))
        assertFalse(doc.links(true)[0].contains(ReviewPosition(1, 4)))
        view.focusRow(1, 2)
        assertFalse(view.reviewAction(R.id.terminal_a11y_open_link))
        view.autoDetectUrls = true
        assertTrue(view.reviewAction(R.id.terminal_a11y_open_link))
        assertEquals(listOf("https://example.com/path"), opened)
    }

    @Test fun linkActionsNavigateHistoryWithoutWrappingAndExtendSelections() {
        install(linkedLine(), history = List(8) { if (it == 0) linkedLine() else accessibilityLine("history $it") })
        view.focusRow(8, 0)
        assertTrue(view.reviewAction(R.id.terminal_a11y_previous_link))
        assertEquals(ReviewPosition(0, 10), review.document!!.selection.active)
        assertEquals(0, review.firstRow)
        assertTrue(key(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON))
        assertEquals(ReviewPosition(0, 10), review.document!!.selection.anchor)
        assertEquals(ReviewPosition(0, 5), review.document!!.selection.active)
        assertTrue(key(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON))
        assertEquals(listOf("https://two.example"), opened)
        view.focusRow(0, 0)
        assertFalse(view.reviewAction(R.id.terminal_a11y_previous_link))
        view.focusRow(8, 14)
        assertFalse(view.reviewAction(R.id.terminal_a11y_next_link))
        view.focusRow(8, 4)
        assertFalse(view.reviewAction(R.id.terminal_a11y_open_link))
        assertFalse(key(KeyEvent.KEYCODE_TAB))
        assertFalse(key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON))
    }

    @Test fun spansAreStableAcrossNodeQueriesAndFreezeButRejectStaleActivation() {
        install(linkedLine())
        if (Build.VERSION.SDK_INT < 26) {
            assertFalse(view.linkedText(0) is Spanned)
            return
        }
        fun spans() = (view.linkedText(0) as Spanned).getSpans(0, 15, URLSpan::class.java)
        val original = spans()
        assertEquals(3, original.size)
        assertEquals("one  two  three", view.provider.createAccessibilityNodeInfo(view.id(0))!!.text.toString())
        view.focusRow(0, 0)
        view.provider.createAccessibilityNodeInfo(view.id(0))
        assertSame(original[1], spans()[1])
        original[1].onClick(view)
        assertEquals(listOf("https://two.example"), opened)
        review.refreshReview()
        original[0].onClick(view)
        assertEquals(1, opened.size)
        val refreshed = spans()
        view.autoDetectUrls = true
        refreshed[0].onClick(view)
        assertEquals(1, opened.size)
    }

    @Test fun homeEndAreGridRowBoundariesWhileCtrlUsesDocument() {
        install(accessibilityLine("abc  ").copy(softWrapped = true), accessibilityLine("אב界e\u0301  "), accessibilityLine("   "))
        view.focusRow(1, 3)
        key(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_SHIFT_ON)
        assertEquals(ReviewPosition(1, 3), review.document!!.selection.anchor)
        assertEquals(ReviewPosition(1, 7), review.document!!.selection.active)
        key(KeyEvent.KEYCODE_MOVE_HOME)
        assertEquals(ReviewPosition(1, 0), review.document!!.selection.active)
        view.reviewAction(R.id.terminal_a11y_mark)
        view.reviewAction(R.id.terminal_a11y_end_line)
        assertEquals(ReviewPosition(1, 0), review.document!!.selection.anchor)
        assertEquals(ReviewPosition(1, 7), review.document!!.selection.active)
        key(KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON)
        assertEquals(ReviewPosition(2, 3), review.document!!.selection.active)
        key(KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        assertEquals(ReviewPosition(0, 0), review.document!!.selection.active)
        view.reviewAction(R.id.terminal_a11y_clear_selection)
        key(KeyEvent.KEYCODE_MOVE_END)
        assertEquals(ReviewPosition(0, 5), review.document!!.selection.active)
        view.focusRow(2, 1)
        view.reviewAction(R.id.terminal_a11y_start_line)
        assertEquals(ReviewPosition(2, 0), review.document!!.selection.active)
        key(KeyEvent.KEYCODE_MOVE_END)
        assertEquals(ReviewPosition(2, 3), review.document!!.selection.active)
    }

    @Test fun sameRowTouchUpdatesExactLinkAndSurvivesFocusCallbacksAndLift() {
        install(linkedLine())
        assertTrue(hover(15f, action = MotionEvent.ACTION_HOVER_ENTER))
        assertTrue(review.isReviewing)
        assertEquals(ReviewPosition(0, 1), review.document!!.selection.active)
        view.provider.performAction(view.id(0), Node.ACTION_ACCESSIBILITY_FOCUS, null)
        assertTrue(hover(65f))
        view.provider.performAction(view.id(0), Node.ACTION_ACCESSIBILITY_FOCUS, null)
        assertEquals(ReviewPosition(0, 6), review.document!!.selection.active)
        hover(65f, action = MotionEvent.ACTION_HOVER_EXIT)
        assertTrue(view.reviewAction(R.id.terminal_a11y_open_link))
        assertEquals(listOf("https://two.example"), opened)
        hover(45f)
        assertFalse(view.reviewAction(R.id.terminal_a11y_open_link))
    }

    @Test fun touchPreservesNonMarkedSelectionButTracksLinkAndExtendsMarkMode() {
        install(linkedLine(), linkedLine())
        view.focusRow(0, 1)
        val selection = review.document!!.selection
        selection.set(ReviewPosition(0, 1), ReviewPosition(0, 3))
        hover(65f, 30f)
        assertEquals(ReviewPosition(0, 1), selection.anchor)
        assertEquals(ReviewPosition(0, 3), selection.active)
        assertFalse(view.provider.performAction(view.id(0), Node.ACTION_ACCESSIBILITY_FOCUS, null))
        assertTrue(view.provider.performAction(view.id(1), Node.ACTION_ACCESSIBILITY_FOCUS, null))
        assertTrue(view.reviewAction(R.id.terminal_a11y_open_link))
        assertEquals(listOf("https://two.example"), opened)
        selection.marking = true
        hover(115f, 30f)
        assertEquals(ReviewPosition(0, 1), selection.anchor)
        assertEquals(ReviewPosition(1, 11), selection.active)
        view.reviewAction(R.id.terminal_a11y_refresh)
        assertEquals(ReviewPosition(0, 0), review.document!!.selection.active)
    }

    @Test fun geometryAccountsForHistoryHorizontalScrollBidiAndGraphemes() {
        val cells = listOf(
            TerminalLine.Cell('界', width = 2, fgColor = Color.White, bgColor = Color.Black),
            TerminalLine.Cell('e', listOf('\u0301'), Color.White, Color.Black),
        )
        install(TerminalLine(0, cells), accessibilityLine("a😀b"), history = List(4) { accessibilityLine("history $it".padEnd(40)) })
        assertEquals(ReviewPosition(4, 0), view.touchPosition(15f, 10f))
        assertEquals(ReviewPosition(4, 1), view.touchPosition(25f, 10f))
        assertEquals(ReviewPosition(5, 1), view.touchPosition(25f, 30f))
        // Same inverse callback used by TerminalTextPaint for visual-to-logical bidi.
        view.logicalColumn = { _, visual -> if (visual == 0) 2 else visual }
        assertEquals(ReviewPosition(4, 1), view.touchPosition(5f, 10f))
        view.focusRow(2, 0)
        review.scroll(columns = 10)
        assertEquals(ReviewPosition(2, 12), view.touchPosition(25f, 10f))
        assertNull(view.touchPosition(-1f, 10f))
        assertNull(view.touchPosition(200f, 10f))
        assertNull(view.touchPosition(1f, -0.1f))
        assertNull(view.touchPosition(1f, 60f))
        assertNull(view.touchPosition(Float.NaN, 10f))
    }

    @Test fun explicitCursorMovementReplacesTouchTargetAndFormattingUsesSelection() {
        val line = linkedLine().copy(
            cells = PackedCells.from(
                listOf(
                    TerminalLine.Cell('a', fgColor = Color.Red, bgColor = Color.Black),
                    TerminalLine.Cell('b', fgColor = Color.Blue, bgColor = Color.Black),
                ),
            ),
        )
        install(line)
        var message = ""
        view.onMessage = { message = it }
        hover(15f)
        view.reviewAction(R.id.terminal_a11y_formatting)
        assertTrue(message.contains("Foreground: #0000FF"))
        review.document!!.selection.set(ReviewPosition(0, 0), ReviewPosition(0, 2))
        hover(5f)
        view.reviewAction(R.id.terminal_a11y_formatting)
        assertTrue(message.contains("Foreground: mixed"))
        install(linkedLine())
        hover(65f)
        view.focusRow(0, 11)
        assertTrue(view.reviewAction(R.id.terminal_a11y_open_link))
        assertEquals(listOf("https://one.example"), opened)
        view.reviewAction(R.id.terminal_a11y_mark)
        view.reviewAction(R.id.terminal_a11y_previous_link)
        assertEquals(ReviewPosition(0, 11), review.document!!.selection.anchor)
        assertEquals(ReviewPosition(0, 5), review.document!!.selection.active)
    }

    @Test fun hoverFreezesVisibleSnapshotAndLiveOutputDoesNotInvalidateTouchedLink() {
        install(linkedLine())
        hover(65f)
        val frozen = review.document
        snapshot = accessibilitySnapshot()
        view.update(snapshot, 0, 10f, 20f)
        assertSame(frozen, review.document)
        assertTrue(view.reviewAction(R.id.terminal_a11y_open_link))
        assertEquals(listOf("https://two.example"), opened)
    }
}
