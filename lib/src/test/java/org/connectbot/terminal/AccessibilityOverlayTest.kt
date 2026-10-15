/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat as Node

@org.robolectric.annotation.Config(sdk = [24, 34])
@RunWith(AndroidJUnit4::class)
class AccessibilityOverlayTest {
    private lateinit var review: TerminalReviewSession
    private lateinit var view: TerminalAccessibilityView
    private var snapshot = accessibilitySnapshot()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        review = TerminalReviewSession { snapshot }
        view = TerminalAccessibilityView(context, review)
        view.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 200, 60)
        view.update(snapshot, 0, 10f, 20f)
    }

    @Test fun resumeFocusSurvivesLiveOutputAndProviderRefresh() {
        view.focusRow(12)
        assertTrue(view.reviewAction(R.id.terminal_a11y_resume))
        val host = AccessibilityNodeProviderCompat.HOST_VIEW_ID
        assertFalse(review.isReviewing)
        assertTrue(view.provider.createAccessibilityNodeInfo(host)!!.isAccessibilityFocused)
        snapshot = accessibilitySnapshot(1000)
        view.update(snapshot, 0, 10f, 20f)
        assertEquals("Terminal output", view.provider.findFocus(Node.FOCUS_ACCESSIBILITY)!!.contentDescription)
        assertTrue(view.provider.performAction(host, Node.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null))
        assertNull(view.provider.findFocus(Node.FOCUS_ACCESSIBILITY))
    }

    @Test fun standardScrollActionsReachOldestAndNewestRows() {
        val provider = view.provider
        val host = AccessibilityNodeProviderCompat.HOST_VIEW_ID
        assertTrue(provider.createAccessibilityNodeInfo(host)!!.actionList.any { it.id == Node.ACTION_SCROLL_BACKWARD })
        repeat(6) { assertTrue(provider.performAction(host, Node.ACTION_SCROLL_BACKWARD, null)) }
        assertEquals(0, review.firstRow)
        assertFalse(provider.performAction(host, Node.ACTION_SCROLL_BACKWARD, null))
        repeat(6) { assertTrue(provider.performAction(host, Node.ACTION_SCROLL_FORWARD, null)) }
        assertEquals(12, review.firstRow)
        assertFalse(provider.performAction(host, Node.ACTION_SCROLL_FORWARD, null))
    }

    @Test fun focusFreezesExactNodeAndRenderedTextWhileOutputChanges() {
        val provider = view.provider
        val id = view.id(12)
        assertTrue(provider.performAction(id, Node.ACTION_ACCESSIBILITY_FOCUS, null))
        val before = provider.createAccessibilityNodeInfo(id)!!.text
        snapshot = accessibilitySnapshot(1000).copy(lines = listOf(accessibilityLine("replacement")), rows = 1)
        view.update(snapshot, 0, 10f, 20f)
        assertEquals(before, provider.findFocus(Node.FOCUS_ACCESSIBILITY)!!.text)
        assertEquals("Screen 0", review.screenState.getVisibleLine(0).text)
        assertTrue(provider.performAction(view.id(0), androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SHOW_ON_SCREEN.id, null))
        assertEquals("History 0", review.screenState.getVisibleLine(0).text)
        val bounds = Rect()
        provider.createAccessibilityNodeInfo(view.id(0))!!.getBoundsInParent(bounds)
        assertEquals(Rect(0, 0, 200, 20), bounds)
    }

    @Test fun granularSelectionAndCopyUseActualTextOffsets() {
        val provider = view.provider
        val id = view.id(12)
        provider.performAction(id, Node.ACTION_ACCESSIBILITY_FOCUS, null)
        val selection = Bundle().apply {
            putInt(Node.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(Node.ACTION_ARGUMENT_SELECTION_END_INT, 6)
        }
        assertTrue(provider.performAction(id, Node.ACTION_SET_SELECTION, selection))
        assertEquals(6, provider.createAccessibilityNodeInfo(id)!!.textSelectionEnd)
        assertTrue(provider.performAction(id, Node.ACTION_COPY, null))
        val clipboard = view.context.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("Screen", clipboard.primaryClip!!.getItemAt(0).text.toString())
        selection.putInt(Node.ACTION_ARGUMENT_SELECTION_END_INT, 999)
        assertFalse(provider.performAction(id, Node.ACTION_SET_SELECTION, selection))
    }

    @Test fun characterNavigationCrossesViewportIntoOlderHistory() {
        view.focusRow(12, 0)
        val args = Bundle().apply { putInt(Node.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, Node.MOVEMENT_GRANULARITY_CHARACTER) }
        assertTrue(view.provider.performAction(view.id(12), Node.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, args))
        assertEquals(11, review.focusedRow)
        assertEquals(11, review.firstRow)
        assertEquals("History 11", view.provider.findFocus(Node.FOCUS_ACCESSIBILITY)!!.text.toString())
        assertEquals(9, view.provider.findFocus(Node.FOCUS_ACCESSIBILITY)!!.textSelectionEnd)
        view.reviewAction(R.id.terminal_a11y_start_output)
        assertFalse(view.provider.performAction(view.id(0), Node.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, args))
    }

    @Test fun longClickStartsAtReviewCursorAndStopsWithoutClearingTheRange() {
        view.focusRow(12, 2)
        val id = view.id(12)
        val info = view.provider.createAccessibilityNodeInfo(id)!!
        assertTrue(info.isLongClickable)
        assertEquals("Start selection", info.actionList.single { it.id == Node.ACTION_LONG_CLICK }.label)
        assertTrue(view.provider.performAction(id, Node.ACTION_LONG_CLICK, null))
        val model = review.document!!.selection
        assertTrue(model.marking)
        assertEquals(ReviewPosition(12, 2), model.anchor)
        val args = Bundle().apply { putInt(Node.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, Node.MOVEMENT_GRANULARITY_CHARACTER) }
        assertTrue(view.provider.performAction(id, Node.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args))
        assertEquals("r", model.copyText())
        assertEquals("Stop selection", view.provider.createAccessibilityNodeInfo(id)!!.actionList.single { it.id == Node.ACTION_LONG_CLICK }.label)
        assertTrue(view.provider.performAction(id, Node.ACTION_LONG_CLICK, null))
        assertFalse(model.marking)
        assertEquals("r", model.copyText())
        assertTrue(view.provider.performAction(id, Node.ACTION_COPY, null))
        assertEquals("r", view.context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun longClickOnLiveHostFreezesAndStartsAtTerminalCursor() {
        assertFalse(review.isReviewing)
        assertTrue(view.reviewAction(Node.ACTION_LONG_CLICK))
        assertTrue(review.isReviewing)
        assertTrue(review.document!!.selection.marking)
        assertEquals(ReviewPosition(snapshot.scrollback.size + snapshot.cursorRow, snapshot.cursorCol), review.document!!.selection.anchor)
    }

    @Test fun markSelectionSurvivesScrollResizeAndNewOutput() {
        view.focusRow(11, 8)
        assertTrue(view.reviewAction(R.id.terminal_a11y_mark))
        assertTrue(view.provider.performAction(view.id(12), Node.ACTION_ACCESSIBILITY_FOCUS, null))
        assertEquals("11\n", review.document!!.selection.copyText())
        val original = review.document
        snapshot = accessibilitySnapshot(0, 1).copy(alternateScreen = true)
        view.update(snapshot, 0, 10f, 20f)
        view.layout(0, 0, 100, 40)
        assertEquals(original, review.document)
        assertTrue(view.reviewAction(Node.ACTION_SCROLL_BACKWARD))
        assertEquals("11\n", review.document!!.selection.copyText())
        assertTrue(view.reviewAction(R.id.terminal_a11y_switch_endpoint))
        assertEquals(ReviewPosition(11, 8), review.document!!.selection.active)
        assertTrue(view.reviewAction(Node.ACTION_COPY))
        assertEquals("11\n", view.context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun keyboardSelectionExtendsAcrossRowsAndSwitchesEndpoints() {
        fun key(code: Int, meta: Int = 0) = view.reviewKey(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, 0, meta))
        view.focusRow(12, 2)
        assertTrue(key(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.META_SHIFT_ON))
        assertEquals(ReviewPosition(12, 2), review.document!!.selection.anchor)
        assertEquals(ReviewPosition(11, 2), review.document!!.selection.active)
        assertTrue(key(android.view.KeyEvent.KEYCODE_X, android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON))
        assertEquals(ReviewPosition(12, 2), review.document!!.selection.active)
        assertTrue(key(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.META_CTRL_ON))
        assertEquals(ReviewPosition(0, 0), review.document!!.selection.anchor)
        assertEquals(review.document!!.selection.last, review.document!!.selection.active)
        assertTrue(view.reviewAction(R.id.terminal_a11y_clear_selection))
        assertTrue(review.isReviewing)
        assertTrue(review.document!!.selection.isEmpty)
    }

    @Test fun lineActionsReachBothEndsWithoutLeavingReview() {
        view.focusRow(12)
        repeat(12) { assertTrue(view.reviewAction(R.id.terminal_a11y_previous_line)) }
        assertEquals(0, review.firstRow)
        assertFalse(view.reviewAction(R.id.terminal_a11y_previous_line))
        repeat(14) { assertTrue(view.reviewAction(R.id.terminal_a11y_next_line)) }
        assertEquals(14, review.focusedRow)
        assertEquals(12, review.firstRow)
        assertFalse(view.reviewAction(R.id.terminal_a11y_next_line))
    }

    @Test fun staleIdsAfterRefreshAreRejected() {
        val id = view.id(12)
        view.provider.performAction(id, Node.ACTION_ACCESSIBILITY_FOCUS, null)
        snapshot = accessibilitySnapshot(2)
        view.update(snapshot, 0, 10f, 20f)
        view.provider.performAction(id, R.id.terminal_a11y_refresh, null)
        assertNull(view.provider.createAccessibilityNodeInfo(id))
        assertFalse(view.provider.performAction(id, R.id.terminal_a11y_resume, null))
        assertFalse(view.provider.performAction(id, Node.ACTION_LONG_CLICK, null))
    }

    @Test fun controllerRefreshRejectsOldSelectionBeforeNextViewUpdate() {
        view.focusRow(12, 3)
        val oldId = view.id(12)
        review.refreshReview()
        val range = Bundle().apply {
            putInt(Node.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(Node.ACTION_ARGUMENT_SELECTION_END_INT, 2)
        }
        assertFalse(view.provider.performAction(oldId, Node.ACTION_SET_SELECTION, range))
        assertNull(view.provider.createAccessibilityNodeInfo(oldId))
        assertTrue(review.document!!.selection.isEmpty)
    }
}
