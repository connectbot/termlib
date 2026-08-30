/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidBoxDrawingTest {
    @Test
    fun everyCharacterRendersAndFullBlocksMeetOnAndroidCanvas() {
        val renderer = TerminalBoxDrawing()
        for (size in listOf(13f, 16f, 21f)) {
            val bitmap = Bitmap.createBitmap(16, 25, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            for (cp in 0x2500..0x259F) {
                bitmap.eraseColor(Color.BLACK)
                renderer.draw(canvas, cp.toChar(), 0f, 0f, 16f, 25f, Color.WHITE, size)
                assertTrue("Blank U+${cp.toString(16)}", (0 until 25).any { y -> (0 until 16).any { x -> bitmap.getPixel(x, y) != Color.BLACK } })
            }
        }
        val bitmap = Bitmap.createBitmap(38, 63, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (row in 0..2) {
            for (col in 0..3) {
                renderer.draw(canvas, '█', col * 9.5f, row * 21f, 9.5f, 21f, Color.WHITE, 16f)
            }
        }
        for (y in 0 until 63) for (x in 0 until 38) assertEquals(Color.WHITE, bitmap.getPixel(x, y))
    }
}
