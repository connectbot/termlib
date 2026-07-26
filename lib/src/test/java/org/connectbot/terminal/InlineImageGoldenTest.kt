/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziComposeSizeOption
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h300dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineImageGoldenTest {
    @Test
    fun inlineImagesMatchGolden() {
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (y in 0..7) {
            for (x in 0..7) {
                image.setPixel(
                    x,
                    y,
                    when {
                        x < 4 && y < 4 -> 0xFFFF6633.toInt()
                        x >= 4 && y < 4 -> 0xFF33AAEE.toInt()
                        x < 4 -> 0xFF55CC66.toInt()
                        else -> 0xFFFFCC33.toInt()
                    },
                )
            }
        }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val payload = Base64.getEncoder().encodeToString(bytes)
        image.recycle()
        val terminal = TerminalEmulatorFactory.create(initialRows = 12, initialCols = 44) as TerminalEmulatorImpl
        terminal.writeInput(
            buildString {
                append("\u001b[?25l")
                append("iTerm2: partial overwrite\r\n")
                append("\u001b]1337;File=inline=1;width=10;height=3;preserveAspectRatio=0:$payload\u0007")
                append("\u001b[3;3H CUT ")
                append("\u001b[6;1HKitty: text over image\r\n")
                append("\u001b_Ga=T,f=100,i=1,c=10,r=3,C=1,z=-1;$payload\u001b\\")
                append("\u001b[8;2HOVERLAY")
                append("\u001b[2;16HCropped Kitty\u001b[3;16H")
                append("\u001b_Ga=p,i=1,p=2,x=4,y=0,w=4,h=8,c=6,r=3,C=1\u001b\\")
                append("\u001b[7;16HUnicode cells\u001b[8;16H")
                append("\u001b_Ga=p,i=1,p=3,U=1,c=6,r=2\u001b\\")
                append("\u001b[38;5;1;58;5;3m\uDBFB\uDEEE\u0305\u0305")
                repeat(5) { append("\uDBFB\uDEEE") }
                append("\u001b[9;16H\uDBFB\uDEEE\u030D\u0305")
                repeat(5) { append("\uDBFB\uDEEE") }
                append("\u001b[0m")
            }.toByteArray(),
        )
        terminal.processPendingUpdates()
        // Small deterministic sources are decoded before capture so the golden
        // checks final pixels, independently of worker scheduling.
        terminal.imageStore.assets.values.forEach { asset ->
            asset.bitmap = asset.frames.first().decode(asset.width, asset.height)
            asset.presentation.publish(asset.bitmap, null)
            asset.bitmapGeneration = asset.generation
            asset.targetWidth = asset.width
            asset.targetHeight = asset.height
        }
        captureRoboImage(
            filePath = "src/test/roborazzi/inline-images-golden.png",
            roborazziComposeOptions = RoborazziComposeOptions.Builder().addOption(RoborazziComposeSizeOption(480, 300)).build(),
        ) {
            Terminal(terminal, typeface = Typeface.MONOSPACE, initialFontSize = 14.sp, forcedSize = 12 to 44, keyboardEnabled = false)
        }
    }
}
