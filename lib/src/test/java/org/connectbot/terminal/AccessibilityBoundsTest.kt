/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityBoundsTest {
    @Test fun semanticMetadataDoesNotChangeWideOrCombiningText() {
        val line = TerminalLine(
            0,
            listOf(
                TerminalLine.Cell('界', fgColor = Color.White, bgColor = Color.Black, width = 2),
                TerminalLine.Cell('e', listOf('\u0301'), Color.White, Color.Black),
                TerminalLine.Cell('Z', fgColor = Color.White, bgColor = Color.Black),
            ),
            semanticSegments = listOf(SemanticSegment(0, 2, SemanticType.PROMPT)),
        )
        val snapshot = accessibilitySnapshot(0, 1).copy(lines = listOf(line))
        val review = TerminalReviewSession { snapshot }
        val view = TerminalAccessibilityView(ApplicationProvider.getApplicationContext<Context>(), review)
        view.update(snapshot, 0, 10f, 20f)
        val node = view.provider.createAccessibilityNodeInfo(view.id(0))!!
        assertEquals(line.text, node.text.toString())
        assertNull(node.contentDescription)
        assertTrue(node.isTextSelectable)
    }
}
