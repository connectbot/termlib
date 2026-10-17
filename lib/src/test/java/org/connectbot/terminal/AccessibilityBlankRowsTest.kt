/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat as Node

@Config(sdk = [24, 34])
@RunWith(AndroidJUnit4::class)
class AccessibilityBlankRowsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val lines = listOf(accessibilityLine("before")) + List(7) { accessibilityLine(" ".repeat(20)) } + accessibilityLine("after")
    private val snapshot = accessibilitySnapshot(0, 3).copy(scrollback = lines.dropLast(3), lines = lines.takeLast(3))
    private val review = TerminalReviewSession { snapshot }
    private val view = TerminalAccessibilityView(context, review).apply {
        measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY))
        layout(0, 0, 200, 60)
        update(snapshot, 0, 10f, 20f)
    }
    private val host = AccessibilityNodeProviderCompat.HOST_VIEW_ID
    private val activity = Robolectric.buildActivity(Activity::class.java)

    @Before fun attach() {
        activity.setup().get().setContentView(view)
        activity.visible()
        view.layout(0, 0, 200, 60)
    }

    @After fun detach() {
        activity.pause().stop().destroy()
    }

    @Test fun blankRunIsOneItemEvenWhenItStartsAboveTheViewport() {
        view.focusRow(0)
        assertEquals(2, view.provider.createAccessibilityNodeInfo(host)!!.childCount)
        assertEquals(view.id(1), view.id(7))
        val blank = view.provider.createAccessibilityNodeInfo(view.id(1))!!
        assertEquals("7 blank lines", blank.stateDescription)
        assertEquals(7, blank.collectionItemInfo!!.rowSpan)
        assertEquals(List(7) { " ".repeat(20) }.joinToString("\n"), blank.text.toString())
        view.focusRow(8)
        assertEquals(2, view.provider.createAccessibilityNodeInfo(host)!!.childCount)
        val clipped = view.provider.createAccessibilityNodeInfo(view.id(7))!!
        assertTrue(clipped.isVisibleToUser)
        val bounds = Rect()
        clipped.getBoundsInParent(bounds)
        assertEquals(Rect(0, 0, 200, 40), bounds)
    }

    @Test fun lineNavigationCrossesEachRunInOneStepAndCopiesOriginalNewlines() {
        view.focusRow(0)
        val args = Bundle().apply { putInt(Node.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, Node.MOVEMENT_GRANULARITY_LINE) }
        for (row in listOf(1, 8)) {
            assertTrue(view.provider.performAction(view.id(review.focusedRow!!), Node.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, args))
            assertEquals(row, review.focusedRow)
        }
        for (row in listOf(1, 0)) {
            assertTrue(view.provider.performAction(view.id(review.focusedRow!!), Node.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, args))
            assertEquals(row, review.focusedRow)
        }
        assertTrue(view.reviewAction(R.id.terminal_a11y_mark))
        repeat(2) { assertTrue(view.reviewAction(R.id.terminal_a11y_next_line)) }
        assertEquals("before" + "\n".repeat(8), review.document!!.selection.copyText())
    }

    @Test fun automaticScrollRevealsTheNeighborBeyondTheWholeRun() {
        view.focusRow(1)
        assertTrue(view.provider.performAction(host, Node.ACTION_SCROLL_FORWARD, null))
        assertEquals(6, review.firstRow)
        assertTrue(view.provider.createAccessibilityNodeInfo(view.id(8))!!.isVisibleToUser)
        assertTrue(view.provider.performAction(host, Node.ACTION_SCROLL_BACKWARD, null))
        assertEquals(0, review.firstRow)
        assertTrue(view.provider.createAccessibilityNodeInfo(view.id(0))!!.isVisibleToUser)
    }

    @Test fun touchInsideSharedBlankItemRetainsPhysicalRowAndColumn() {
        shadowOf(context.getSystemService(AccessibilityManager::class.java)).apply {
            setEnabled(true)
            setTouchExplorationEnabled(true)
        }
        view.focusRow(0)
        review.scroll(rows = 3)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_ENTER, 55f, 30f, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue(view.dispatchGenericMotionEvent(event))
            assertTrue(view.provider.performAction(view.id(4), Node.ACTION_ACCESSIBILITY_FOCUS, null))
            assertEquals(4, review.focusedRow)
            assertEquals(ReviewPosition(4, 5), review.document!!.selection.active)
            assertEquals("7 blank lines", view.provider.findFocus(Node.FOCUS_ACCESSIBILITY)!!.stateDescription)
        } finally {
            event.recycle()
        }
    }

    @Test fun sharedNodeOffsetsRoundTripToPhysicalRows() {
        view.focusRow(4, 5)
        val document = review.document!!
        val offset = 3 * 21 + 5
        assertEquals(offset, view.provider.findFocus(Node.FOCUS_ACCESSIBILITY)!!.textSelectionEnd)
        assertEquals(ReviewPosition(4, 5), document.accessiblePosition(1, offset))
        assertEquals(offset, document.accessibleOffset(ReviewPosition(4, 5)))
        val args = Bundle().apply {
            putInt(Node.ACTION_ARGUMENT_SELECTION_START_INT, 20)
            putInt(Node.ACTION_ARGUMENT_SELECTION_END_INT, 2 * 21)
        }
        assertTrue(view.provider.performAction(view.id(1), Node.ACTION_SET_SELECTION, args))
        assertEquals(ReviewPosition(1, 20), document.selection.anchor)
        assertEquals(ReviewPosition(3, 0), document.selection.active)
        assertEquals("\n\n", document.selection.copyText())
    }

    @Test fun entirelyBlankDocumentIsOneStop() {
        val document = TerminalReviewDocument(snapshot.copy(scrollback = emptyList(), lines = List(3) { accessibilityLine("") }))
        assertEquals(0..2, document.accessibleRows(1))
        assertEquals(null, document.selection.next(ReviewUnit.LINE, true))
        assertEquals(null, document.selection.next(ReviewUnit.LINE, false))
        assertFalse(document.selection.marking)
    }
}
