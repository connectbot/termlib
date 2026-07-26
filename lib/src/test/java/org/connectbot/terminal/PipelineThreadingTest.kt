/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineThreadingTest {
    @Test
    fun decodedStaticImageDoesNotKeepRequestingFrames() {
        val terminal = TerminalEmulatorFactory.create() as TerminalEmulatorImpl
        val store = terminal.imageStore
        val asset = store.add(1, null, ImageFrame(width = 1, height = 1))
        asset.bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        asset.bitmapGeneration = asset.generation
        asset.targetWidth = 1
        asset.targetHeight = 1
        asset.presentation.publish(asset.bitmap, null)
        val slice = ImageSlice(asset, 0, 1, 0, 1, 0, 1, Rect(0, 0, 1, 1), 0)
        store.updateViewport(ImageViewport(7, listOf(slice), 8f, 16f, 0))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (synchronized(store) { store.viewportTop != 7 } && System.nanoTime() < deadline) Thread.yield()
        synchronized(store) {
            assertEquals(7, store.viewportTop)
            assertFalse(store.frameUpdatesNeeded)
        }
    }

    @Test
    fun queuedInputAndViewportSubmissionDoNotWaitForBusyWorker() {
        val terminal = TerminalEmulatorFactory.create() as TerminalEmulatorImpl
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        terminal.commands.execute {
            synchronized(terminal.imageStore) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val submitted = CountDownLatch(1)
        val submitter = thread {
            QueuedTerminal(terminal).dispatchCharacter(0, 'a'.code)
            terminal.pasteText("paste")
            terminal.imageStore.updateViewport(ImageViewport(0, emptyList(), 8f, 16f, 0))
            submitted.countDown()
        }
        try {
            assertTrue("UI submission waited for the worker or store lock", submitted.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            submitter.join()
            terminal.commands.call { Unit }
        }
    }

    @Test
    fun keyboardImeAndChunkedPasteShareOneFifo() {
        val output = java.io.ByteArrayOutputStream()
        val terminal = TerminalEmulatorFactory.create(onKeyboardInput = { output.write(it) }) as TerminalEmulatorImpl
        terminal.writeInput("\u001b[?2004h".toByteArray())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        terminal.commands.execute {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val ui = QueuedTerminal(terminal)
            val ime = KeyboardHandler(ui)
            ui.dispatchCharacter(0, 'A'.code)
            val paste = "日abc".repeat(4000)
            terminal.pasteText(paste)
            ime.onCommittedText("BC\r\n")
            terminal.pasteText("second")
            ui.dispatchKey(0, VTermKey.ENTER)
            release.countDown()
            terminal.commands.call { Unit }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("A\u001b[200~${paste}\u001b[201~BC\r\u001b[200~second\u001b[201~\r", output.toByteArray().toString(Charsets.UTF_8))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun synchronousPublicInputCannotOvertakeQueuedPaste() {
        val output = java.io.ByteArrayOutputStream()
        val terminal = TerminalEmulatorFactory.create(onKeyboardInput = { output.write(it) }) as TerminalEmulatorImpl
        terminal.pasteText("first日")
        terminal.dispatchCharacter(0, 'B'.code)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("first日B", output.toByteArray().toString(Charsets.UTF_8))
    }

    @Test
    fun drawingPublishedImageDoesNotWaitForStoreLock() {
        val terminal = TerminalEmulatorFactory.create() as TerminalEmulatorImpl
        val source = ImageFrame(width = 1, height = 1)
        val asset = terminal.imageStore.add(1, null, source)
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        asset.presentation.publish(pixel, null)
        val slice = ImageSlice(asset, 0, 1, 0, 1, 0, 1, Rect(0, 0, 1, 1), 0)
        val target = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888)
        slice.draw(Canvas(target), 0, 8f, 16f)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = thread {
            synchronized(terminal.imageStore) {
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        val drawn = CountDownLatch(1)
        val renderer = thread {
            slice.draw(Canvas(target), 0, 8f, 16f)
            drawn.countDown()
        }
        try {
            assertTrue("Drawing waited for the image store", drawn.await(2, TimeUnit.SECONDS))
            assertEquals(android.graphics.Color.RED, target.getPixel(4, 8))
        } finally {
            release.countDown()
            blocker.join()
            renderer.join()
        }
    }

    @Test
    fun snapshotsPublishWithoutDrainingMainLooperAndInputCanBeReused() {
        val terminal = TerminalEmulatorFactory.create() as TerminalEmulatorImpl
        val input = "hello".toByteArray()
        terminal.writeInput(input)
        input.fill('X'.code.toByte())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!terminal.snapshot.value.lines[0].text.startsWith("hello") && System.nanoTime() < deadline) Thread.yield()
        assertTrue(terminal.snapshot.value.lines[0].text.startsWith("hello"))
    }
}
