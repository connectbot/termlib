/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/** Platform decoders retain compressed input and a small set of working frames. */
@Suppress("DEPRECATION")
internal object AnimatedImage {
    @RequiresApi(28)
    fun decode(source: ImageSource, width: Int, height: Int): Drawable {
        check(source.displayAllowed) { "Inline image consent is required before decoding" }
        val bytes = ByteArray(source.bytes.size)
        source.bytes.input().use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0)
                offset += count
            }
        }
        return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    @RequiresApi(28)
    fun start(drawable: Drawable, handler: Handler, invalidate: () -> Unit) {
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) = invalidate()
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                handler.postAtTime(what, `when`)
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                handler.removeCallbacks(what)
            }
        }
        if (drawable is AnimatedImageDrawable) drawable.start()
    }

    fun stop(drawable: Drawable?) {
        if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) drawable.stop()
        drawable?.callback = null
    }

    fun movie(source: ImageSource): Movie? {
        check(source.displayAllowed) { "Inline image consent is required before decoding" }
        return source.bytes.input().use(Movie::decodeStream)
    }

    fun frame(movie: Movie, width: Int, height: Int, elapsed: Long): Bitmap {
        movie.setTime((elapsed % movie.duration().coerceAtLeast(1)).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width.toFloat() / movie.width(), height.toFloat() / movie.height())
        movie.draw(canvas, 0f, 0f)
        return bitmap
    }
}
