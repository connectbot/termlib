/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

abstract class TerminalLifetimeChecks {
    private fun nativeDelegate(terminal: TerminalEmulatorImpl): Lazy<*> = TerminalEmulatorImpl::class.java.getDeclaredField("terminalNativeDelegate").let {
        it.isAccessible = true
        it.get(terminal) as Lazy<*>
    }

    @Test
    fun closingUnusedTerminalDoesNotInitializeNative() {
        val terminal = TerminalEmulatorFactory.create() as TerminalEmulatorImpl
        val native = nativeDelegate(terminal)
        terminal.close()
        terminal.close()
        assertFalse(native.isInitialized())
        assertTrue(terminal.snapshot.value.lines.isEmpty())
        assertThrows(IllegalStateException::class.java) { terminal.writeInput(byteArrayOf(65)) }
        assertFalse(native.isInitialized())
    }

    @Test
    fun closeDisposesContentAndRejectsEveryPublicOperation() {
        val terminal = TerminalEmulatorFactory.create(initialRows = 2, initialCols = 10) as TerminalEmulatorImpl
        terminal.writeInput("first\r\nsecond\r\nthird".toByteArray())
        terminal.processPendingUpdates()
        val previous = terminal.snapshot.value
        val native = nativeDelegate(terminal).value as TerminalNative
        terminal.close()
        terminal.close()
        val disposed = terminal.snapshot.value
        terminal.processPendingUpdates()
        assertSame(disposed, terminal.snapshot.value)
        assertTrue(disposed.lines.isEmpty())
        assertTrue(disposed.scrollback.isEmpty())
        assertFalse(disposed.cursorVisible)
        assertTrue(previous.lines.isNotEmpty())
        assertTrue(previous.scrollback.isNotEmpty())
        assertEquals(TerminalDimensions(2, 10), terminal.dimensions)
        assertThrows(IllegalStateException::class.java) { native.writeInput(byteArrayOf(65)) }
        val operations: List<() -> Any?> = listOf(
            { terminal.writeInput(byteArrayOf(65)) },
            { terminal.writeInput(ByteBuffer.allocateDirect(1), 1) },
            { terminal.resize(2, 10) },
            { terminal.dispatchKey(0, VTermKey.ENTER) },
            { terminal.dispatchCharacter(0, 65) },
            { terminal.pasteText("paste") },
            { terminal.clearScreen() },
            { terminal.setInlineImages(InlineImages.On()) },
            { terminal.setCellPixelSize(8, 16) },
            { terminal.setAnsiPalette(IntArray(16)) },
            { terminal.setDefaultColors(0, 0) },
            { terminal.applyColorScheme(IntArray(16), 0, 0) },
            { terminal.getLastCommandOutput() },
            { terminal.getUrls() },
        )
        operations.forEach { operation -> assertThrows(IllegalStateException::class.java) { operation() } }
    }

    @Test
    fun queuedCommandsAfterCloseFinishWithoutUsingNativeState() {
        val terminal = TerminalEmulatorFactory.create() as TerminalEmulatorImpl
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        terminal.commands.execute {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            terminal.pasteText("queued paste")
            QueuedTerminal(terminal).dispatchCharacter(0, 65)
            terminal.close()
        } finally {
            release.countDown()
        }
        terminal.commands.call { Unit }
        assertFalse(nativeDelegate(terminal).isInitialized())
    }

    @Test
    fun closeCanRaceInputResizeSnapshotsAndOtherCloses() {
        val terminal = TerminalEmulatorFactory.create() as TerminalEmulatorImpl
        terminal.writeInput("hello".toByteArray())
        val failure = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val actions: List<() -> Unit> = listOf(
            { terminal.writeInput("hello\r\n".toByteArray()) },
            { terminal.resize(25, 81) },
            { terminal.processPendingUpdates() },
            { terminal.dispatchCharacter(0, 65) },
            { terminal.close() },
            { terminal.close() },
        )
        val workers = actions.map { action ->
            thread {
                start.await()
                try {
                    repeat(20) { action() }
                } catch (_: IllegalStateException) {
                    // A public operation may lose the race to disposal.
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
        }
        start.countDown()
        workers.forEach { it.join(10_000) }
        terminal.close()
        assertTrue(workers.none { it.isAlive })
        failure.get()?.let { throw AssertionError(it) }
        assertTrue(terminal.snapshot.value.lines.isEmpty())
    }

    @Test
    fun nativeCallbackExceptionsDoNotLeaveBorrowedContextInstalled() {
        val callbacks = NativeCallbackStub()
        TerminalNative(callbacks).use { native ->
            callbacks.native = native
            callbacks.bellAction = { throw IllegalArgumentException("callback failed") }
            assertThrows(IllegalArgumentException::class.java) { native.writeInput(byteArrayOf(7)) }
            callbacks.bellAction = { 0 }
            assertEquals(1, native.writeInput(byteArrayOf(7)))
            callbacks.bellAction = {
                assertThrows(IllegalStateException::class.java) { native.close() }
                assertThrows(IllegalStateException::class.java) { native.writeInput(byteArrayOf(65)) }
                0
            }
            native.writeInput(byteArrayOf(7))
        }
    }

    @Test
    fun abandonedManagedCallbackCyclesAreCollectible() {
        fun abandoned(): WeakReference<NativeCallbackStub> {
            val callbacks = NativeCallbackStub()
            callbacks.native = TerminalNative(callbacks)
            return WeakReference(callbacks)
        }
        // ART may keep a WeakReference.get() result in an interpreter register
        // across the next GC. Create on a terminated thread and don't poll the
        // referents while collecting, so the test itself cannot keep one alive.
        val created = AtomicReference<List<WeakReference<NativeCallbackStub>>>()
        thread { created.set(List(20) { abandoned() }) }.join()
        repeat(20) {
            System.gc()
            @Suppress("DEPRECATION")
            System.runFinalization()
            Thread.sleep(20)
        }
        assertEquals("JNI must not root the callback/native ownership cycle", 0, created.get().count { it.get() != null })
    }

    @Test
    fun closedImagesIgnoreStaleViewportAndPreserveBorrowedBitmap() {
        val terminal = TerminalEmulatorFactory.create(inlineImages = InlineImages.On()) as TerminalEmulatorImpl
        val asset = terminal.imageStore.add(1, null, ImageFrame(width = 1, height = 1))
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        asset.bitmap = bitmap
        asset.presentation.publish(bitmap, null)
        val borrowed = asset.presentation.frame
        terminal.close()
        terminal.imageStore.updateViewport(ImageViewport(0, emptyList(), 8f, 16f, 0))
        asset.request(1, 1)
        asset.presentation.publish(bitmap, null)
        assertTrue(terminal.imageStore.assets.isEmpty())
        assertTrue(terminal.imageStore.placements.isEmpty())
        assertEquals(0L, terminal.imageStore.encodedUsage())
        assertFalse(terminal.imageStore.frameUpdatesNeeded)
        assertNull(asset.presentation.frame.bitmap)
        assertSame(bitmap, borrowed.bitmap)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun decodeCompletingAfterCloseDoesNotPublishOrRetainPixels() {
        val terminal = TerminalEmulatorFactory.create(inlineImages = InlineImages.On()) as TerminalEmulatorImpl
        val decoder = InlineImageStore::class.java.getDeclaredField("decoder").let {
            it.isAccessible = true
            it.get(null) as ExecutorService
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        decoder.execute {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            terminal.writeInput("\u001b_Ga=T,f=32,s=1,v=1;/wAA/w==\u001b\\".toByteArray())
            val asset = terminal.imageStore.assets.values.single()
            asset.request(1, 1)
            assertTrue(asset.pending)
            terminal.close()
            release.countDown()
            decoder.submit {}.get(5, TimeUnit.SECONDS)
            val maintenance = InlineImageStore::class.java.getDeclaredField("maintenance").let {
                it.isAccessible = true
                it.get(terminal.imageStore) as TerminalDispatcher
            }
            maintenance.call { Unit }
            assertFalse(asset.pending)
            assertNull(asset.bitmap)
            assertNull(asset.presentation.frame.bitmap)
            assertTrue(terminal.imageStore.assets.isEmpty())
            assertEquals(0L, terminal.imageStore.encodedUsage())
        } finally {
            release.countDown()
            terminal.close()
        }
    }
}

/** No-op callbacks for lifetime and boundary tests, without mock-framework roots. */
internal class NativeCallbackStub : TerminalCallbacks {
    var native: TerminalNative? = null
    var bellAction: () -> Int = { 0 }
    private val buffer = CellData.buffer()
    override fun cellBuffer(): ByteBuffer = buffer
    override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int): Int = 0
    override fun moverect(dest: TermRect, src: TermRect): Int = 0
    override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean): Int = 0
    override fun moveCursor(row: Int, col: Int, oldRow: Int, oldCol: Int, visible: Boolean): Int = 0
    override fun setTermProp(prop: Int, value: TerminalProperty): Int = 0
    override fun bell(): Int = bellAction()
    override fun pushScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer, softWrapped: Boolean): Int = 0
    override fun popScrollbackLine(cols: Int, start: Int, count: Int, cells: ByteBuffer): Int = 0
    override fun clearScrollback(): Int = 0
    override fun onKeyboardInput(data: ByteArray): Int = 0
    override fun onTextFragment(kind: Int, command: Int, data: ByteArray, initial: Boolean, final: Boolean, cursorRow: Int, cursorCol: Int): Int = 0
}
