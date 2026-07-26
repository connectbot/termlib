/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class ImageViewport(
    val top: Int,
    val slices: List<ImageSlice>,
    val cellWidth: Float,
    val cellHeight: Float,
    val now: Long,
    val attached: Boolean = true,
)

/** Draw reads only this published resource, never the mutable image store. */
internal class ImagePresentation(private val handler: Handler) {
    data class Frame(val bitmap: Bitmap?, val drawable: Drawable?)
    var frame by mutableStateOf(Frame(null, null))
        private set
    var redraw by mutableStateOf(0)
        private set

    fun publish(bitmap: Bitmap?, drawable: Drawable?) {
        val previous = frame
        if (previous.bitmap === bitmap && previous.drawable === drawable) return
        val next = Frame(bitmap, drawable)
        frame = next
        // Drawable lifecycle belongs to the callback thread, without store locks.
        if (previous.drawable !== drawable) {
            handler.post {
                AnimatedImage.stop(previous.drawable)
                if (frame === next && drawable != null && Build.VERSION.SDK_INT >= 28) {
                    AnimatedImage.start(drawable, handler) { redraw++ }
                }
            }
        }
    }
}
