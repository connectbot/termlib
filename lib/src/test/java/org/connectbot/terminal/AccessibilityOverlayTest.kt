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

    @Test fun staleIdsAfterRefreshAreRejected() {
        val id = view.id(12)
        view.provider.performAction(id, Node.ACTION_ACCESSIBILITY_FOCUS, null)
        snapshot = accessibilitySnapshot(2)
        view.update(snapshot, 0, 10f, 20f)
        view.provider.performAction(id, R.id.terminal_a11y_refresh, null)
        assertNull(view.provider.createAccessibilityNodeInfo(id))
        assertFalse(view.provider.performAction(id, R.id.terminal_a11y_resume, null))
    }
}
