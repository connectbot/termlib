/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.text.TextRunShaper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AndroidFontFallbackTest {
    private fun font(name: String) = Font.Builder(InstrumentationRegistry.getInstrumentation().context.assets, "$name.ttf").build()

    @Test
    fun customFallbackWorksWithCanvas() {
        val primary = FontFamily.Builder(font("Primary")).build()
        val fallback = FontFamily.Builder(font("Fallback")).build()
        val face = Typeface.CustomFallbackBuilder(primary).addCustomFallback(fallback).setSystemFallback("monospace").build()
        val paint = Paint().apply {
            typeface = face
            textSize = 20f
            color = android.graphics.Color.WHITE
        }
        assertEquals(12f, paint.measureText("A"), 0.1f)
        assertEquals(22f, paint.measureText("B"), 0.1f)
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText("AB", 0f, 25f, paint)
        assertTrue(bitmap.getPixel(4, 20) != 0)
        assertTrue(bitmap.getPixel(18, 20) != 0)
        bitmap.recycle()
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun actualSelectedFontsFollowCoverageOrder() {
        val primary = font("Primary")
        val fallback = font("Fallback")
        val face = Typeface.CustomFallbackBuilder(FontFamily.Builder(primary).build())
            .addCustomFallback(FontFamily.Builder(fallback).build()).setSystemFallback("monospace").build()
        val paint = Paint().apply {
            typeface = face
            textSize = 20f
        }
        val glyphs = TextRunShaper.shapeTextRun("AB", 0, 2, 0, 2, 0f, 0f, false, paint)
        assertEquals(2, glyphs.glyphCount())
        assertEquals(primary.buffer, glyphs.getFont(0).buffer)
        assertEquals(fallback.buffer, glyphs.getFont(1).buffer)
        // Plain warning is covered by the primary font, but VS16 requests
        // emoji presentation from a font supporting that variation sequence.
        val plain = TextRunShaper.shapeTextRun("⚠", 0, 1, 0, 1, 0f, 0f, false, paint)
        assertEquals(primary.buffer, plain.getFont(0).buffer)
        val emoji = TextRunShaper.shapeTextRun("⚠️", 0, 2, 0, 2, 0f, 0f, false, paint)
        assertTrue(emoji.glyphCount() > 0)
        assertTrue(emoji.getFont(0).buffer != primary.buffer)
        assertTrue(emoji.getFont(0).buffer != fallback.buffer)
    }
}
