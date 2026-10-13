/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.content.Context
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Config(sdk = [24, 34])
@RunWith(AndroidJUnit4::class)
class AccessibilitySessionStateTest {
    @Test fun recreatedViewKeepsFrozenTextSelectionAndViewportWithoutTakingFocus() {
        val state = TerminalAccessibilityState()
        var latest = accessibilitySnapshot()
        val first = TerminalReviewSession(state) { latest }
        first.resize(3, 10)
        first.begin(latest, row = 3, top = 2)
        first.scroll(columns = 4)
        val document = first.document!!
        document.selection.set(ReviewPosition(2, 1), ReviewPosition(3, 4))
        document.selection.marking = true
        val copied = document.selection.copyText()
        val top = first.firstRow
        val left = first.firstColumn
        // The live history can be completely replaced while the screen is absent.
        latest = accessibilitySnapshot(1000)
        val restored = TerminalReviewSession(state) { latest }
        val view = TerminalAccessibilityView(ApplicationProvider.getApplicationContext<Context>(), restored)
        view.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 100, 60)
        view.update(latest, 0, 10f, 20f)
        assertSame(document, restored.document)
        assertEquals(top, restored.firstRow)
        assertEquals(left, restored.firstColumn)
        assertEquals(3, restored.focusedRow)
        assertEquals(copied, restored.document!!.selection.copyText())
        assertTrue(restored.document!!.selection.marking)
        assertNull(view.provider.findFocus(AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY))
        assertTrue(view.focusRow(restored.focusedRow!!, preserveSelection = true))
        assertEquals(ReviewPosition(3, 4), restored.document!!.selection.active)
        restored.resize(20, 80)
        assertEquals(ReviewPosition(2, 1), restored.document!!.selection.anchor)
        assertEquals(copied, restored.document!!.selection.copyText())
    }

    @Test fun initialFocusHintWaitsForReadinessAndIsConsumedByUserFocus() {
        val review = TerminalReviewSession { accessibilitySnapshot() }
        val view = TerminalAccessibilityView(ApplicationProvider.getApplicationContext<Context>(), review)
        val host = androidx.core.view.accessibility.AccessibilityNodeProviderCompat.HOST_VIEW_ID
        assertFalse(view.provider.createAccessibilityNodeInfo(host)!!.hasRequestInitialAccessibilityFocus())
        view.requestInitialFocus = true
        assertTrue(view.provider.createAccessibilityNodeInfo(host)!!.hasRequestInitialAccessibilityFocus())
        assertFalse(review.isReviewing)
        view.requestInitialFocus = false
        assertFalse(view.provider.createAccessibilityNodeInfo(host)!!.hasRequestInitialAccessibilityFocus())
        assertNull(view.provider.findFocus(AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY))
        view.requestInitialFocus = true
        view.provider.performAction(host, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS, null)
        view.provider.performAction(host, AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
        view.requestInitialFocus = false
        view.requestInitialFocus = true
        assertFalse(view.provider.createAccessibilityNodeInfo(host)!!.hasRequestInitialAccessibilityFocus())
        assertNull(view.provider.findFocus(AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY))
    }

    @Test fun liveModeAndSessionIsolationSurviveRecreation() {
        val state = TerminalAccessibilityState()
        val first = TerminalReviewSession(state) { accessibilitySnapshot() }
        first.startReview()
        val other = TerminalReviewSession { accessibilitySnapshot(200) }
        assertFalse(other.isReviewing)
        first.resumeLiveOutput()
        val restored = TerminalReviewSession(state) { accessibilitySnapshot(1000) }
        assertFalse(restored.isReviewing)
        assertNull(restored.focusedRow)
        restored.startReview()
        assertEquals(1000, restored.document!!.snapshot.scrollback.size)
    }

    @Test fun accidentallyReusingStateWithNewEmulatorDoesNotRestoreAnotherSessionsText() {
        val state = TerminalAccessibilityState()
        val emulator = mockk<TerminalEmulator>()
        state.bindTo(emulator)
        TerminalReviewSession(state) { accessibilitySnapshot() }.startReview()
        state.bindTo(emulator)
        assertTrue(state.document != null)
        state.bindTo(mockk<TerminalEmulator>())
        assertNull(state.document)
        assertNull(state.focusedRow)
    }
}
