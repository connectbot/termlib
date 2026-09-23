/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineImageRendererTest : InlineImageRenderChecks() {
    @Test
    fun invalidPixelsAreDiscardedAfterApprovalAndDecoderRecovers() {
        for (ask in listOf(false, true)) {
            lateinit var answer: Continuation<Boolean>
            val policy = if (ask) InlineImages.Ask { suspendCoroutine { answer = it } } else InlineImages.On()
            val terminal = TerminalEmulatorFactory.create(initialRows = 6, initialCols = 12, inlineImages = policy) as TerminalEmulatorImpl
            // A complete, valid PNG dimension header with no pixel data.
            val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=").copyOf(33)
            val payload = Base64.getEncoder().encodeToString(png)
            terminal.writeInput("\u001b_Ga=T,f=100,i=1,C=1;$payload\u001b\\".toByteArray())
            val asset = terminal.imageStore.assets.getValue(1)
            if (ask) {
                asset.request(1, 1)
                assertFalse(asset.pending)
                shadowOf(Looper.getMainLooper()).idle()
                answer.resume(true)
                terminal.commands.call { Unit }
            }
            fun awaitDecode(image: ImageAsset) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (synchronized(terminal.imageStore) { image.pending } && System.nanoTime() < deadline) Thread.sleep(5)
                assertFalse(synchronized(terminal.imageStore) { image.pending })
            }
            asset.request(1, 1)
            awaitDecode(asset)
            assertTrue(synchronized(terminal.imageStore) { terminal.imageStore.assets.isEmpty() })
            terminal.setInlineImages(InlineImages.On())
            terminal.writeInput("\u001b_Ga=T,f=32,s=1,v=1,i=2,C=1;/wAA/w==\u001b\\SAFE".toByteArray())
            val valid = terminal.imageStore.assets.getValue(2)
            valid.request(1, 1)
            awaitDecode(valid)
            assertNotNull(valid.bitmap)
            terminal.processPendingUpdates()
            assertTrue(terminal.snapshot.value.lines.any { "SAFE" in it.text })
        }
        assertTrue(ShadowLog.getLogsForTag("InlineImageStore").any { "pixel decoding failed" in it.msg })
    }
}
