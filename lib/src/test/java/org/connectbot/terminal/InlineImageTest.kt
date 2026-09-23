/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
class InlineImageTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="
    private fun emulator() = TerminalEmulatorFactory.create(initialRows = 6, initialCols = 12, inlineImages = InlineImages.On()) as TerminalEmulatorImpl
    private fun iterm(data: String = png, options: String = "width=3;height=2;preserveAspectRatio=0") = "\u001b]1337;File=inline=1;$options:$data\u0007"
    private fun kitty(options: String, payload: String? = null) = "\u001b_G$options${payload?.let { ";$it" } ?: ""}\u001b\\"
    private fun TerminalEmulatorImpl.write(text: String) = writeInput(text.toByteArray())
    private fun TerminalEmulatorImpl.flush(): TerminalSnapshot {
        processPendingUpdates()
        return snapshot.value
    }

    private fun assertSameTextAndCursor(expected: TerminalSnapshot, actual: TerminalSnapshot) {
        assertEquals(expected.cursorRow, actual.cursorRow)
        assertEquals(expected.cursorCol, actual.cursorCol)
        assertEquals(expected.lines.map { it.cells }, actual.lines.map { it.cells })
        assertEquals(expected.scrollback.map { it.cells }, actual.scrollback.map { it.cells })
    }

    @Test
    fun liveAndPendingImagesHaveIdenticalLayout() {
        val multipart = "\u001b]1337;MultipartFile=inline=1;width=3;height=2;preserveAspectRatio=0\u0007" +
            png.chunked(7).joinToString("") { "\u001b]1337;FilePart=$it\u0007" } + "\u001b]1337;FileEnd\u0007"
        val sequences = listOf(
            iterm(),
            iterm(options = "preserveAspectRatio=1"),
            multipart,
            kitty("a=T,f=100,c=3,r=2", png),
            kitty("a=T,f=100,C=1,c=3,r=2", png),
            kitty("a=T,f=100", png),
            kitty("a=T,f=32,s=1,v=1", "/wAA/w=="),
            kitty("a=t,f=100,i=7", png) + kitty("a=p,i=7,c=3,r=2"),
        )
        for (sequence in sequences) {
            for (position in listOf("\u001b[2;2H", "\u001b[6;1H", "\u001b[2;5r\u001b[4;2H", "\u001b[?1049h\u001b[6;1H")) {
                val answers = mutableListOf<Continuation<Boolean>>()
                val live = emulator()
                val ask = TerminalEmulatorFactory.create(
                    initialRows = 6,
                    initialCols = 12,
                    inlineImages = InlineImages.Ask { suspendCoroutine { answers.add(it) } },
                ) as TerminalEmulatorImpl
                val stream = "one\r\ntwo\r\nthree\r\nfour\r\nfive" + position + sequence + "\r\n$ "
                stream.chunked(17).forEach {
                    live.write(it)
                    ask.write(it)
                }
                val expected = live.flush()
                val pending = ask.flush()
                assertSameTextAndCursor(expected, pending)
                assertTrue((pending.lines + pending.scrollback).all { it.images.isEmpty() })
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("Missing consent for sequence=$sequence position=$position", answers.isNotEmpty())
                while (answers.isNotEmpty()) {
                    answers.removeAt(0).resume(true)
                    ask.commands.call { Unit }
                    shadowOf(Looper.getMainLooper()).idle()
                }
                val approved = ask.flush()
                assertSameTextAndCursor(expected, approved)
                // Asset identities differ between terminals; compare the actual image cells.
                fun cells(snapshot: TerminalSnapshot) = (snapshot.scrollback + snapshot.lines).map { line ->
                    line.images.map { listOf(it.left, it.right, it.sourceRow, it.rows, it.sourceCol, it.columns, it.crop) }
                }
                assertEquals(cells(expected), cells(approved))
            }
        }
    }

    @Test
    fun pendingImageTracksEditsResizeAndScreenSwitchBeforeApproval() {
        lateinit var answer: Continuation<Boolean>
        val live = emulator()
        val ask = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            inlineImages = InlineImages.Ask { suspendCoroutine { answer = it } },
        ) as TerminalEmulatorImpl
        val stream = iterm() + "\u001b[1;2HX\u001b[6;1H\r\n\u001b[?1049hOTHER"
        live.write(stream)
        ask.write(stream)
        live.resize(8, 10)
        ask.resize(8, 10)
        shadowOf(Looper.getMainLooper()).idle()
        answer.resume(true)
        ask.commands.call { Unit }
        live.write("\u001b[?1049l")
        ask.write("\u001b[?1049l")
        val expected = live.flush()
        val actual = ask.flush()
        assertSameTextAndCursor(expected, actual)
        assertEquals(
            (expected.scrollback + expected.lines).map { it.images.map { slice -> slice.left to slice.right } },
            (actual.scrollback + actual.lines).map { it.images.map { slice -> slice.left to slice.right } },
        )
    }

    @Test
    fun denyingPendingFramePreservesOriginalImageAndReleasesBytes() {
        lateinit var answer: Continuation<Boolean>
        var first = true
        val terminal = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            inlineImages = InlineImages.Ask {
                if (first) {
                    first = false
                    true
                } else {
                    suspendCoroutine { answer = it }
                }
            },
        ) as TerminalEmulatorImpl
        terminal.write(kitty("a=T,f=32,s=1,v=1,i=7,C=1", "/wAA/w=="))
        shadowOf(Looper.getMainLooper()).idle()
        terminal.commands.call { Unit }
        val original = terminal.imageStore.assets[7]!!.frames.single()
        val usage = terminal.imageStore.encodedUsage()
        terminal.write(kitty("a=f,f=32,s=1,v=1,i=7", "AAD/gA==") + kitty("a=a,i=7,s=3"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(original), terminal.imageStore.assets[7]!!.frames)
        assertTrue(terminal.imageStore.pendingSources.isNotEmpty())
        answer.resume(false)
        terminal.commands.call { Unit }
        assertEquals(listOf(original), terminal.imageStore.assets[7]!!.frames)
        assertTrue(terminal.imageStore.assets[7]!!.displayAllowed)
        assertTrue(terminal.imageStore.pendingSources.isEmpty())
        assertEquals(usage, terminal.imageStore.encodedUsage())
    }

    @Test
    fun disablingImagesCancelsPendingConsentAndLateAnswers() {
        lateinit var answer: Continuation<Boolean>
        val terminal = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            inlineImages = InlineImages.Ask { suspendCoroutine { answer = it } },
        ) as TerminalEmulatorImpl
        terminal.write(iterm() + "\r\n$ ")
        shadowOf(Looper.getMainLooper()).idle()
        val before = terminal.flush()
        terminal.setInlineImages(InlineImages.Off)
        answer.resume(true)
        terminal.commands.call { Unit }
        assertSameTextAndCursor(before, terminal.flush())
        assertTrue(terminal.imageStore.assets.isEmpty())
        assertEquals(0L, terminal.imageStore.encodedUsage())
    }

    @Test
    fun inlineImagesDefaultToOff() {
        val terminal = TerminalEmulatorFactory.create(initialRows = 6, initialCols = 12) as TerminalEmulatorImpl
        terminal.write(iterm())
        assertEquals(InlineImages.Off, terminal.inlineImages)
        assertTrue(terminal.imageStore.assets.isEmpty())
    }

    @Test
    fun askDoesNotDecodeOrBlockTextBeforeApproval() {
        lateinit var answer: Continuation<Boolean>
        lateinit var request: InlineImageRequest
        val asked = CountDownLatch(1)
        val terminal = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            inlineImages = InlineImages.Ask { value ->
                request = value
                suspendCoroutine { continuation ->
                    answer = continuation
                    asked.countDown()
                }
            },
        ) as TerminalEmulatorImpl

        terminal.write("\u001b]1337;File=inline=1;name=dGVzdC5wbmc=;size=68;width=3;height=2;preserveAspectRatio=0:")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(asked.await(1, TimeUnit.SECONDS))
        assertEquals(InlineImageProtocolType.ITERM2, request.protocol)
        assertEquals("test.png", request.name)
        assertEquals(68L, request.declaredSizeBytes)
        assertTrue(terminal.imageStore.assets.isEmpty())
        terminal.write(png + "\u0007X")
        val before = terminal.flush()
        assertEquals('X', before.lines[1].cells.charAt(3))
        assertTrue(before.lines.all { it.images.isEmpty() })
        val asset = terminal.imageStore.assets.values.single()
        assertTrue(!asset.displayAllowed)
        asset.request(1, 1)
        assertTrue(!asset.pending)
        assertNull(asset.bitmap)
        try {
            asset.frames.single().decode(1, 1)
            fail("Pixel decoding must require consent")
        } catch (_: IllegalStateException) { }

        answer.resume(true)
        terminal.commands.call { Unit }
        assertEquals(1, terminal.imageStore.assets.size)
        assertEquals('X', terminal.flush().lines[1].cells.charAt(3))
        assertEquals(before.cursorRow, terminal.flush().cursorRow)
        assertEquals(before.cursorCol, terminal.flush().cursorCol)
    }

    @Test
    fun askDenialRepliesToKittyAndSerializesPrompts() {
        val answers = mutableListOf<Continuation<Boolean>>()
        val requests = mutableListOf<InlineImageRequest>()
        val responses = mutableListOf<String>()
        val terminal = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            onKeyboardInput = { responses.add(it.toString(Charsets.US_ASCII)) },
            inlineImages = InlineImages.Ask { request ->
                requests.add(request)
                suspendCoroutine { answers.add(it) }
            },
        ) as TerminalEmulatorImpl

        terminal.write(kitty("a=T,f=100,i=7", png) + kitty("a=T,f=100,i=8", png))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requests.size)
        answers[0].resume(false)
        terminal.commands.call { Unit }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, requests.size)
        assertTrue(responses.any { "EPERM:user denied inline image" in it })
        assertEquals(2, terminal.flush().cursorRow)
        assertEquals(2, terminal.flush().cursorCol)
        answers[1].resume(true)
        terminal.commands.call { Unit }
        assertEquals(setOf(8L), terminal.imageStore.assets.keys)
    }

    @Test
    fun askKittyApprovalPreservesFollowingPromptAndCursor() {
        lateinit var answer: Continuation<Boolean>
        val terminal = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            inlineImages = InlineImages.Ask { suspendCoroutine { answer = it } },
        ) as TerminalEmulatorImpl

        terminal.write(kitty("a=T,f=100,c=3,r=2", png) + "\r\n\r\nPROMPT\r")
        shadowOf(Looper.getMainLooper()).idle()
        val before = terminal.flush()
        answer.resume(true)
        terminal.commands.call { Unit }

        val after = terminal.flush()
        assertEquals(before.cursorRow, after.cursorRow)
        assertEquals(before.cursorCol, after.cursorCol)
        assertEquals(before.lines.map { it.text }, after.lines.map { it.text })
        assertEquals(1, terminal.imageStore.assets.size)
    }

    @Test
    fun imgcatReservesSpaceBeforePromptWhileApprovalIsPending() {
        for (startRow in listOf(0, 4, 5)) {
            for (height in listOf(2, 10)) {
                lateinit var answer: Continuation<Boolean>
                val terminal = TerminalEmulatorFactory.create(
                    initialRows = 6,
                    initialCols = 12,
                    inlineImages = InlineImages.Ask { suspendCoroutine { answer = it } },
                ) as TerminalEmulatorImpl
                terminal.write("\u001b[${startRow + 1};1H")
                terminal.write("\u001b]1337;MultipartFile=inline=1;width=3;height=$height;preserveAspectRatio=0\u0007")
                png.chunked(7).forEach { terminal.write("\u001b]1337;FilePart=$it\u0007") }
                terminal.write("\u001b]1337;FileEnd\u0007\r\n$ ")
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(terminal.imageStore.assets.values.none { it.displayAllowed })
                val before = terminal.flush()
                assertTrue(before.lines.any { it.text.startsWith("$ ") })
                assertTrue(before.lines.all { it.images.isEmpty() })

                answer.resume(true)
                terminal.commands.call { Unit }

                val snapshot = terminal.flush()
                assertEquals(before.cursorRow, snapshot.cursorRow)
                assertEquals(before.cursorCol, snapshot.cursorCol)
                assertEquals(before.lines.map { it.text }, snapshot.lines.map { it.text })
                val placement = terminal.imageStore.placements.single()
                assertEquals(height, placement.height)
                assertEquals(placement.top + height, snapshot.cursorRow)
                assertEquals(2, snapshot.cursorCol)
                assertTrue(snapshot.lines[snapshot.cursorRow].text.startsWith("$ "))
                assertTrue(snapshot.lines[snapshot.cursorRow].images.isEmpty())
            }
        }
    }

    @Test
    fun imgcatMultipartWithRememberedApprovalDoesNotReenterNative() {
        lateinit var answer: Continuation<Boolean>
        var approved = false
        val terminal = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            inlineImages = InlineImages.Ask {
                if (approved) true else suspendCoroutine { answer = it }
            },
        ) as TerminalEmulatorImpl
        repeat(2) {
            terminal.write("\u001b]1337;MultipartFile=inline=1;width=3;height=2;preserveAspectRatio=0\u0007")
            shadowOf(Looper.getMainLooper()).idle()
            if (!approved) {
                approved = true
                answer.resume(true)
            }
            terminal.commands.call { Unit }
            png.chunked(7).forEach { terminal.write("\u001b]1337;FilePart=$it\u0007") }
            terminal.write("\u001b]1337;FileEnd\u0007\r\n$ ")
            val snapshot = terminal.flush()
            assertEquals(2, snapshot.cursorCol)
            assertTrue(snapshot.lines[snapshot.cursorRow].text.startsWith("$ "))
            terminal.write("\r\n")
        }
        assertEquals(2, terminal.imageStore.assets.size)
    }

    @Test
    fun recordedImgcatMultipartWithDelayedApproval() {
        val path = System.getenv("TERMLIB_IMGCAT_TRANSCRIPT")
        assumeTrue(path != null)
        val stream = File(path!!).readText().replace("\n", "\r\n")
        for (startRow in listOf(0, 38, 39)) {
            lateinit var answer: Continuation<Boolean>
            val terminal = TerminalEmulatorFactory.create(
                initialRows = 40,
                initialCols = 80,
                inlineImages = InlineImages.Ask { suspendCoroutine { answer = it } },
            ) as TerminalEmulatorImpl
            terminal.write("\u001b[${startRow + 1};1H")
            stream.chunked(113).forEach { terminal.write(it) }
            terminal.write("$ ")
            shadowOf(Looper.getMainLooper()).idle()
            answer.resume(true)
            terminal.commands.call { Unit }
            val snapshot = terminal.flush()
            val placement = terminal.imageStore.placements.single()
            assertEquals(placement.top + placement.height, snapshot.cursorRow)
            assertEquals(2, snapshot.cursorCol)
            assertTrue(snapshot.lines[snapshot.cursorRow].text.startsWith("$ "))
            assertTrue(snapshot.lines[snapshot.cursorRow].images.isEmpty())
        }
    }

    @Test
    fun askApprovalBeforePayloadCompletesDoesNotReenterNative() {
        lateinit var answer: Continuation<Boolean>
        var approved = false
        val terminal = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            inlineImages = InlineImages.Ask {
                if (approved) true else suspendCoroutine { answer = it }
            },
        ) as TerminalEmulatorImpl

        repeat(2) {
            terminal.write("\u001b_Ga=T,f=100;")
            shadowOf(Looper.getMainLooper()).idle()
            if (!approved) {
                approved = true
                answer.resume(true)
            }
            terminal.commands.call { Unit }
            terminal.write(png + "\u001b\\\r\nPROMPT")
        }
        assertEquals(2, terminal.imageStore.assets.size)
        assertTrue(terminal.flush().lines.any { it.text.startsWith("PROMPT") })
    }

    @Test
    fun askApprovalDoesNotRepeatKittyCursorReservation() {
        lateinit var answer: Continuation<Boolean>
        val terminal = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            inlineImages = InlineImages.Ask {
                suspendCoroutine { continuation -> answer = continuation }
            },
        ) as TerminalEmulatorImpl

        terminal.write(kitty("a=T,f=100", png))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, terminal.flush().cursorRow)
        assertEquals(1, terminal.flush().cursorCol)
        assertTrue(terminal.flush().lines.all { it.images.isEmpty() })

        answer.resume(true)
        terminal.commands.call { Unit }

        val snapshot = terminal.flush()
        assertEquals(1, snapshot.cursorRow)
        assertEquals(1, snapshot.cursorCol)
        assertEquals(1, terminal.imageStore.assets.size)
    }

    @Test
    fun itermReservesCellsBeforeFollowingTextAndOnlyLosesOverwrittenCells() {
        val terminal = emulator()
        terminal.write(iterm() + "X")
        val snapshot = terminal.flush()
        assertEquals('X', snapshot.lines[1].cells.charAt(3))
        assertEquals(1, snapshot.lines[0].images.size)
        terminal.write("\u001b[1;2H ")
        val edited = terminal.flush()
        assertEquals(listOf(0 to 1, 2 to 3), edited.lines[0].images.map { it.left to it.right })
        assertEquals(1, edited.lines[1].images.size)
    }

    @Test
    fun everyByteBoundaryAndStTerminationWorks() {
        val terminal = emulator()
        val sequence = iterm().dropLast(1) + "\u001b\\"
        sequence.toByteArray().forEach { terminal.writeInput(byteArrayOf(it)) }
        assertEquals(1, terminal.imageStore.assets.size)
        assertEquals(1, terminal.flush().lines[0].images.size)
    }

    @Test
    fun fragmentedKittyStDoesNotRingOrLeakPayload() {
        var bells = 0
        val terminal = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            onBell = { bells++ },
            inlineImages = InlineImages.On(),
        ) as TerminalEmulatorImpl
        val stream = "\u001b]0;recorded title\u0007" + kitty("a=T,q=2,f=100", png) + "SAFE"

        stream.toByteArray().forEach { terminal.writeInput(byteArrayOf(it)) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, bells)
        assertEquals(1, terminal.imageStore.assets.size)
        val snapshot = terminal.flush()
        assertTrue(snapshot.lines.any { it.images.isNotEmpty() })
        assertTrue(snapshot.lines.any { "SAFE" in it.text })
        assertTrue(snapshot.lines.none { "Zg" in it.text })
    }

    @Test
    fun multipartPayloadCanSplitInsideBase64Quartets() {
        val terminal = emulator()
        terminal.write("\u001b]1337;MultipartFile=inline=1;width=2;height=2\u0007")
        png.chunked(7).forEach { terminal.write("\u001b]1337;FilePart=$it\u0007") }
        terminal.write("\u001b]1337;FileEnd\u0007")
        assertEquals(1, terminal.imageStore.assets.size)
        assertEquals(0, terminal.imageStore.uploadBytes)
    }

    @Test
    fun kittyChunksRawDataAndRepliesWithoutMovingForC1() {
        val terminal = emulator()
        val raw = Base64.getEncoder().encodeToString(byteArrayOf(-1, 0, 0, -1))
        terminal.write(kitty("a=T,f=32,s=1,v=1,i=9,C=1,m=1", raw.take(4)))
        assertTrue(terminal.imageStore.assets.isEmpty())
        terminal.write(kitty("m=0", raw.drop(4)) + "Z")
        assertEquals('Z', terminal.flush().lines[0].cells.charAt(0))
        assertEquals(1, terminal.imageStore.assets.size)
        assertTrue(terminal.imageStore.encodedUsage() > 0)
        assertNull(terminal.imageStore.assets[9]!!.bitmap)
    }

    @Test
    fun kittyFinalChunkDefaultsToM0InsteadOfInheritingM1() {
        val terminal = emulator()
        val raw = Base64.getEncoder().encodeToString(byteArrayOf(-1, 0, 0, -1))
        terminal.write(kitty("a=T,f=32,s=1,v=1,i=10,C=1,m=1", raw.take(4)))
        terminal.write(kitty("a=T,q=2", raw.drop(4)))

        assertEquals(1, terminal.imageStore.assets.size)
        assertTrue(terminal.flush().lines.any { it.images.isNotEmpty() })
    }

    @Test
    fun naturalKittyPlacementKeepsPixelSizeWhenCellSizeChanges() {
        val terminal = emulator()
        terminal.setCellPixelSize(8, 16)
        val raw = Base64.getEncoder().encodeToString(ByteArray(16 * 16 * 4) { -1 })
        terminal.write(kitty("a=T,q=2,f=32,s=16,v=16,C=1", raw))
        val placement = terminal.imageStore.placements.single()
        assertEquals(2, placement.width)
        assertEquals(1, placement.height)

        terminal.setCellPixelSize(4, 8)

        assertEquals(4, placement.width)
        assertEquals(2, placement.height)
        val slice = terminal.imageStore.slices(0).single()
        assertEquals(16f, slice.targetWidth(4f))
        assertEquals(16f, slice.targetHeight(8f))
    }

    @Test
    fun viewportResizeDoesNotPermanentlyClipWideKittyPlacement() {
        val terminal = emulator()
        terminal.setCellPixelSize(8, 16)
        val raw = Base64.getEncoder().encodeToString(ByteArray(160 * 16 * 4) { -1 })
        terminal.write(kitty("a=T,q=2,f=32,s=160,v=16,C=1", raw))

        terminal.resize(6, 8)
        assertEquals(8, terminal.imageStore.slices(0).single().right)
        terminal.resize(6, 24)

        assertEquals(20, terminal.imageStore.slices(0).single().right)
    }

    @Test
    fun kittyAcceptsUnpaddedBase64UsedByIcat() {
        val terminal = emulator()
        terminal.write(kitty("a=T,q=2,f=100,s=1,v=1", png.trimEnd('=')))

        assertEquals(1, terminal.imageStore.assets.size)
        assertTrue(terminal.flush().lines.any { it.images.isNotEmpty() })
    }

    /** Optional upstream transcript; set TERMLIB_KITTY_TGP to its extracted directory. */
    @Test
    fun kittyTgp001Transcript() {
        val directory = System.getenv("TERMLIB_KITTY_TGP")?.let(::File)
        assumeTrue(directory?.isDirectory == true)
        ShadowLog.clear()
        val terminal = TerminalEmulatorFactory.create(initialRows = 30, initialCols = 137, inlineImages = InlineImages.On()) as TerminalEmulatorImpl
        terminal.setCellPixelSize(8, 16)
        var peakAssets = 0
        var peakPlacements = 0
        var peakEncoded = 0L
        var peakSnapshotSlices = 0
        directory!!.resolve("typescript3").inputStream().buffered().use { input ->
            val prologue = buildString {
                while (true) {
                    val value = input.read()
                    require(value >= 0) { "Missing typescript prologue" }
                    append(value.toChar())
                    if (value == '\n'.code) break
                }
            }
            assertTrue("Unexpected typescript prologue", prologue.startsWith("Script started on "))
            directory.resolve("timing3").forEachLine { record ->
                if (!record.startsWith("O ")) return@forEachLine
                val size = record.substringAfterLast(' ').toInt()
                val chunk = input.readNBytes(size)
                assertEquals("Truncated typescript record", size, chunk.size)
                terminal.writeInput(chunk)
                peakAssets = maxOf(peakAssets, terminal.imageStore.assets.size)
                peakPlacements = maxOf(peakPlacements, terminal.imageStore.placements.size)
                peakEncoded = maxOf(peakEncoded, terminal.imageStore.encodedUsage())
                if (terminal.imageStore.placements.isNotEmpty()) {
                    terminal.processPendingUpdates()
                    peakSnapshotSlices = maxOf(
                        peakSnapshotSlices,
                        terminal.snapshot.value.lines.sumOf { it.images.size },
                    )
                }
            }
            val trailer = input.readBytes().toString(Charsets.UTF_8)
            assertTrue("Unexpected typescript trailer", trailer.isEmpty() || trailer.trimStart().startsWith("Script done on "))
        }
        terminal.processPendingUpdates()
        val rejections = ShadowLog.getLogsForTag("InlineImageProtocol").map { it.msg }
        println(
            "KITTY_TGP peakAssets=$peakAssets peakPlacements=$peakPlacements " +
                "peakEncoded=$peakEncoded finalAssets=${terminal.imageStore.assets.size} " +
                "peakSnapshotSlices=$peakSnapshotSlices policy=${terminal.inlineImages} rejections=$rejections",
        )
        assertTrue(rejections.joinToString("\n"), rejections.isEmpty())
        assertEquals(36, peakAssets)
        assertEquals(18, peakPlacements)
        assertTrue("No image placement reached a terminal snapshot", peakSnapshotSlices > 0)
    }

    @Test
    fun kittyPersistsThroughOrdinaryErasureButClearsOnEd2() {
        val terminal = emulator()
        terminal.write(kitty("a=T,f=100,i=7,c=3,r=2,C=1,z=-1", png))
        terminal.write("ABC\r\u001b[K")
        assertEquals(1, terminal.flush().lines[0].images.size)
        terminal.write("\u001b[2J")
        assertTrue(terminal.flush().lines.all { it.images.isEmpty() })
    }

    @Test
    fun disablingReleasesRetainedSourcesAndDrainsUploads() {
        val terminal = emulator()
        terminal.write(iterm())
        val oldSnapshot = terminal.flush()
        terminal.setInlineImages(InlineImages.Off)
        assertEquals(0, terminal.imageStore.encodedUsage())
        assertTrue(oldSnapshot.lines[0].images[0].asset.frames.isEmpty())
        terminal.write(iterm() + "Q")
        assertTrue(terminal.imageStore.assets.isEmpty())
        terminal.setInlineImages(InlineImages.On())
        terminal.write(iterm())
        assertEquals(1, terminal.imageStore.assets.size)
    }

    @Test
    fun malformedAndOversizedUploadsRecoverWithoutLeakingPayload() {
        val terminal = emulator()
        terminal.write(iterm("%%%%") + "SAFE")
        assertTrue(terminal.flush().lines[0].text.startsWith("SAFE"))
        assertEquals(0, terminal.imageStore.uploadBytes)
        terminal.write(kitty("a=T,f=32,s=999999,v=999999,i=1", "AAAA") + "OK")
        assertTrue(terminal.imageStore.assets.isEmpty())
    }

    @Test
    fun placeholdersUseRawPaletteIdsAndUnderlinePlacementIds() {
        val terminal = emulator()
        terminal.write(kitty("a=T,f=100,i=42,p=5,c=2,r=2,U=1,q=2", png))
        terminal.write("\u001b[38;5;42;58;5;5m\uDBFB\uDEEE\u0305\u0305\uDBFB\uDEEE\u001b[0m")
        val line = terminal.flush().lines[0]
        assertEquals(2, line.images.size)
        assertTrue(line.text.startsWith("  "))
        assertEquals(1, line.images[1].sourceCol)
        terminal.write("\rX")
        assertEquals(1, terminal.flush().lines[0].images.size)
    }

    @Test
    fun imageMemoryBudgetRejectsVisibleEvictsOffscreen() {
        val limits = InlineImageLimits(encodedBytes = 4096)
        val store = InlineImageStore(limits, Handler(Looper.getMainLooper())).apply {
            rows = 6
            cols = 12
        }
        val responses = mutableListOf<String>()
        val protocol = InlineImageProtocol(store, { responses.add(it.toString(Charsets.US_ASCII)) }, { _, _, _ -> })
        fun send(id: Int) = protocol.accept(true, "Ga=T,f=100,i=$id,C=1;$png".toByteArray(), true, true, 0, 0)
        send(1)
        send(2)
        assertEquals(setOf(1L), store.assets.keys)
        assertTrue(responses.last().contains("ENOSPC"))
        store.placements[0].top = -5
        send(2)
        assertEquals(setOf(2L), store.assets.keys)
        assertTrue(store.encodedUsage() <= limits.encodedBytes)
    }

    @Test
    fun strictBase64RejectsNoncanonicalAndIncompleteTails() {
        for (invalid in listOf("A", "AA=", "AB==", "AAB=", "AA==AA==")) {
            val decoder = ImageBase64(ImageBytes.Builder(64))
            try {
                invalid.forEach { decoder.accept(it.code) }
                decoder.finish()
                fail("Accepted $invalid")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun cancellationReleasesUploadAndRecoversForNextImage() {
        val terminal = emulator()
        terminal.write("\u001b]1337;File=inline=1:${png.take(40)}")
        assertTrue(terminal.imageStore.uploadBytes > 0)
        terminal.write("\u0018SAFE")
        assertEquals(0, terminal.imageStore.uploadBytes)
        assertTrue(terminal.flush().lines[0].text.startsWith("SAFE"))
        terminal.write(iterm())
        assertEquals(1, terminal.imageStore.assets.size)
    }

    @Test
    fun imagesSurviveScrollbackResizeAndAlternateScreenRoundTrip() {
        val terminal = emulator()
        terminal.write(iterm() + "\r\n1\r\n2\r\n3\r\n4\r\n5\r\n6")
        assertTrue(terminal.flush().scrollback.any { it.images.isNotEmpty() })
        terminal.resize(9, 12)
        assertTrue(terminal.flush().lines.any { it.images.isNotEmpty() })
        terminal.write("\u001b[?1049h")
        assertTrue(terminal.flush().lines.all { it.images.isEmpty() })
        terminal.write("\u001b[?1049l")
        assertTrue(terminal.flush().lines.any { it.images.isNotEmpty() })
    }

    @Test
    fun relativePlacementFollowsMovedUnicodeAnchor() {
        val terminal = emulator()
        terminal.write(kitty("a=T,f=100,i=1,p=1,c=1,r=1,U=1,q=2", png))
        terminal.write(kitty("a=T,f=100,i=2,p=2,c=2,r=1,P=1,Q=1,H=1,V=0,q=2", png))
        terminal.write("\u001b[3;4H\u001b[38;5;1;58;5;1m\uDBFB\uDEEE\u0305\u0305")
        val line = terminal.flush().lines[2]
        assertEquals(4, line.images.first { it.asset.id == 2L }.left)
        terminal.write("\u001b[3;4HX")
        assertTrue(terminal.flush().lines.flatMap { it.images }.none { it.asset.id == 2L })
    }

    @Test
    fun capabilityAndPixelQueriesRespectToggleAndMeasurements() {
        val responses = mutableListOf<String>()
        val terminal = TerminalEmulatorFactory.create(
            initialRows = 6,
            initialCols = 12,
            onKeyboardInput = { responses.add(it.toString(Charsets.US_ASCII)) },
            inlineImages = InlineImages.On(),
        ) as TerminalEmulatorImpl
        terminal.setCellPixelSize(10, 20)
        terminal.write("\u001b[16t\u001b[14t\u001b]1337;Capabilities\u0007")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("\u001b[6;20;10t", "\u001b[4;120;120t", "\u001b]1337;Capabilities=F\u001b\\"), responses)
        terminal.setInlineImages(InlineImages.Off)
        terminal.write("\u001b]1337;Capabilities\u0007")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("\u001b]1337;Capabilities=\u001b\\", responses.last())
    }

    @Test
    fun animationControlAdvancesFramesAndDeletionKeepsSharedSources() {
        val terminal = emulator()
        terminal.write(kitty("a=T,f=32,s=1,v=1,i=3,C=1", "/wAA/w=="))
        terminal.write(kitty("a=f,f=32,s=1,v=1,i=3,c=1,z=10", "AAD/gA=="))
        terminal.write(kitty("a=a,i=3,s=3,c=1,z=10,v=1"))
        val asset = terminal.imageStore.assets[3]!!
        terminal.imageStore.advanceAnimations(100)
        terminal.imageStore.advanceAnimations(111)
        assertEquals(1, asset.frameIndex)
        assertEquals(2, asset.frames.size)
        terminal.write(kitty("a=d,d=f,i=3,r=1"))
        assertEquals(1, asset.frames.size)
        assertTrue(terminal.imageStore.encodedUsage() > 0)
        terminal.write(kitty("a=d,d=I,i=3"))
        assertTrue(terminal.imageStore.assets.isEmpty())
    }
}
