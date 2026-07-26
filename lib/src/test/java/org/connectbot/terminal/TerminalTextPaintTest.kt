/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Typeface
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalTextPaintTest {
    @Test
    fun cachedGlyphsAndFallbackClustersMatchPlatformMeasurements() {
        for (typeface in listOf(Typeface.MONOSPACE, Typeface.SERIF)) {
            for (size in listOf(12f, 27f)) {
                val cached = TerminalTextPaint(typeface, size)
                val reference = TextPaint().apply {
                    this.typeface = typeface
                    textSize = size
                    isAntiAlias = true
                }
                repeat(3) {
                    for (bold in listOf(false, true, false)) {
                        cached.isFakeBoldText = bold
                        reference.isFakeBoldText = bold
                        for (value in listOf("M", "i", "é", "日", "e\u0301", "🎉", "fi")) {
                            val chars = "-$value-".toCharArray()
                            assertEquals(reference.measureText(chars, 1, value.length), cached.measureCell(chars, 1, value.length), 0f)
                        }
                    }
                }
            }
        }
    }
}
