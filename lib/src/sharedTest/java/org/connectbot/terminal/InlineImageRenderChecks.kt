/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Paint
import android.text.TextPaint
import android.util.Base64
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Identical pixel assertions on Robolectric's native renderer and an Android device. */
abstract class InlineImageRenderChecks {
    private fun emulator() = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 8, inlineImages = InlineImages.On()) as TerminalEmulatorImpl

    private fun render(terminal: TerminalEmulatorImpl): Bitmap {
        terminal.processPendingUpdates()
        terminal.imageStore.assets.values.forEach { asset ->
            asset.bitmap = asset.frames[0].decode(asset.width, asset.height)
            asset.presentation.publish(asset.bitmap, null)
            asset.bitmapGeneration = asset.generation
            asset.targetWidth = asset.width
            asset.targetHeight = asset.height
        }
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(64f, 48f)) {
            terminal.snapshot.value.lines.forEachIndexed { row, line ->
                drawLine(line, row, 8f, 16f, 12f, TextPaint().apply { textSize = 12f }, Paint(), Color.White, Color.Black, null)
            }
        }
        return bitmap
    }

    @Test
    fun singlePixelImageCoversEveryReservedRowAndPartialOverwriteIsVisible() {
        val terminal = emulator()
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val bytes = ByteArrayOutputStream().also { pixel.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val payload = Base64.encodeToString(bytes, Base64.NO_WRAP)
        terminal.writeInput("\u001b]1337;File=inline=1;width=3;height=2;preserveAspectRatio=0:$payload\u0007".toByteArray())
        val before = render(terminal)
        assertEquals(android.graphics.Color.RED, before.getPixel(4, 8))
        assertEquals(android.graphics.Color.RED, before.getPixel(4, 24))
        terminal.writeInput("\u001b[1;2H ".toByteArray())
        val after = render(terminal)
        assertEquals(android.graphics.Color.RED, after.getPixel(4, 8))
        assertEquals(android.graphics.Color.BLACK, after.getPixel(12, 8))
        assertEquals(android.graphics.Color.RED, after.getPixel(20, 8))
        assertEquals(android.graphics.Color.RED, after.getPixel(12, 24))
    }

    @Test
    fun kittyRespectsBothTextAndBackgroundStackingThresholds() {
        for (z in listOf(-1, -1_073_741_825, 1)) {
            val terminal = emulator()
            terminal.writeInput("\u001b_Ga=T,f=32,s=1,v=1,i=1,c=3,r=2,C=1,z=$z;/wAA/w==\u001b\\\u001b[44m \u001b[0m".toByteArray())
            val rendered = render(terminal)
            if (z < -1_073_741_824) {
                assertTrue(rendered.getPixel(4, 8) != android.graphics.Color.RED)
                assertEquals(android.graphics.Color.RED, rendered.getPixel(12, 8))
            } else {
                assertEquals(android.graphics.Color.RED, rendered.getPixel(4, 8))
            }
            assertEquals(android.graphics.Color.RED, rendered.getPixel(12, 24))
        }
    }

    @Test
    fun cellInsertionPreservesSubpixelImageFragments() {
        val terminal = emulator()
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val bytes = ByteArrayOutputStream().also { pixel.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val payload = Base64.encodeToString(bytes, Base64.NO_WRAP)
        terminal.writeInput("\u001b]1337;File=inline=1;width=3;height=2;preserveAspectRatio=0:$payload\u0007\u001b[1;2H\u001b[@".toByteArray())
        val rendered = render(terminal)
        assertEquals(android.graphics.Color.RED, rendered.getPixel(4, 8))
        assertEquals(android.graphics.Color.BLACK, rendered.getPixel(12, 8))
        assertEquals(android.graphics.Color.RED, rendered.getPixel(20, 8))
        assertEquals(android.graphics.Color.RED, rendered.getPixel(28, 8))
        assertEquals(android.graphics.Color.RED, rendered.getPixel(12, 24))
    }

    @Test
    fun explicitDefaultRgbBackgroundStillOccludesDeepKittyLayer() {
        val terminal = emulator()
        terminal.writeInput("\u001b_Ga=T,f=32,s=1,v=1,i=1,c=3,r=2,C=1,z=-1073741825;/wAA/w==\u001b\\\u001b[48;2;0;0;0m \u001b[0m".toByteArray())
        val rendered = render(terminal)
        assertEquals(android.graphics.Color.BLACK, rendered.getPixel(4, 8))
        assertEquals(android.graphics.Color.RED, rendered.getPixel(12, 8))
    }

    @Test
    fun kittyFrameCompositionUsesAlphaAndDoesNotDecodeOtherFrames() {
        val terminal = emulator()
        terminal.writeInput("\u001b_Ga=t,f=32,s=1,v=1,i=1;/wAA/w==\u001b\\".toByteArray())
        terminal.writeInput("\u001b_Ga=f,f=32,s=1,v=1,i=1,c=1;AAD/gA==\u001b\\".toByteArray())
        val asset = terminal.imageStore.assets[1]!!
        assertEquals(2, asset.frames.size)
        val blended = asset.frames[1].decode(1, 1).getPixel(0, 0)
        assertTrue(android.graphics.Color.red(blended) in 126..128)
        assertTrue(android.graphics.Color.blue(blended) in 127..129)
        assertEquals(null, asset.bitmap)
    }

    @Test
    fun kittyPlacementsAlphaCompositeInCreationOrder() {
        val terminal = emulator()
        terminal.writeInput("\u001b_Ga=T,f=32,s=1,v=1,i=1,c=1,r=1,C=1;/wAAgA==\u001b\\".toByteArray())
        terminal.writeInput("\u001b_Ga=T,f=32,s=1,v=1,i=2,c=1,r=1,C=1;AAD/gA==\u001b\\".toByteArray())
        terminal.writeInput("\u001b_Ga=T,f=32,s=1,v=1,i=3,c=1,r=1,C=1;AP8AgA==\u001b\\".toByteArray())

        val pixel = render(terminal).getPixel(4, 8)
        assertTrue(android.graphics.Color.red(pixel) in 31..33)
        assertTrue(android.graphics.Color.green(pixel) in 127..129)
        assertTrue(android.graphics.Color.blue(pixel) in 63..65)
    }

    @Test
    fun kittyTerminatorSurvivesOneByteNativeWrites() {
        val terminal = emulator()
        val stream = "\u001b_Ga=T,q=2,f=32,s=1,v=1,C=1;/wAA/w==\u001b\\SAFE".toByteArray()

        stream.forEach { terminal.writeInput(byteArrayOf(it)) }
        terminal.processPendingUpdates()

        assertEquals(1, terminal.imageStore.assets.size)
        assertTrue(terminal.snapshot.value.lines.any { "SAFE" in it.text })
        assertTrue(terminal.snapshot.value.lines.any { it.images.isNotEmpty() })
    }
}
