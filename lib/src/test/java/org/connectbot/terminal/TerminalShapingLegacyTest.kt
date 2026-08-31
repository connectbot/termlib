/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30])
class TerminalShapingLegacyTest {
    @Test
    fun oldAndroidDoesNotLoadNewShapingApis() {
        val cells = PackedCells.from("سلام".map { TerminalLine.Cell(it, fgColor = Color.White, bgColor = Color.Black) })
        val paint = TerminalTextPaint(Typeface.MONOSPACE, 20f)
        assertNull(paint.layout(cells, 12f))
        paint.clearShaping()
    }
}
