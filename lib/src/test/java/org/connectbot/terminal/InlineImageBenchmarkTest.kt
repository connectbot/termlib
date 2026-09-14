/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.Handler
import android.os.Looper
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.management.ManagementFactory
import java.util.Base64

/** Opt-in protocol/decode benchmark. Run with TERMLIB_BENCHMARK=1. */
@RunWith(RobolectricTestRunner::class)
class InlineImageBenchmarkTest {
    @Test
    fun rgbaUploadAndDecode() {
        assumeTrue(System.getenv("TERMLIB_BENCHMARK") == "1")
        val width = 512
        val height = 512
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 4
                rgba[offset] = x.toByte()
                rgba[offset + 1] = y.toByte()
                rgba[offset + 2] = (x xor y).toByte()
                rgba[offset + 3] = 0xff.toByte()
            }
        }
        val payload = Base64.getEncoder().encodeToString(rgba)
        val sequence = "Ga=T,f=32,s=$width,v=$height,i=1,C=1;$payload".toByteArray()
        val store = InlineImageStore(InlineImageLimits(), Handler(Looper.getMainLooper()))
        val protocol = InlineImageProtocol(store, {}, { _, _, _ -> })
        val allocations = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val threadId = Thread.currentThread().threadId()

        fun upload() {
            protocol.accept(true, sequence, true, true, 0, 0)
        }
        repeat(3) { upload() }
        val uploadTimes = mutableListOf<Long>()
        val uploadBytes = mutableListOf<Long>()
        repeat(7) {
            val before = allocations.getThreadAllocatedBytes(threadId)
            val start = System.nanoTime()
            upload()
            uploadTimes += System.nanoTime() - start
            uploadBytes += allocations.getThreadAllocatedBytes(threadId) - before
        }
        println(
            "INLINE_BENCH upload pixels=${width * height} wire=${sequence.size} " +
                "ns/op=${uploadTimes.sorted()[3]} bytes/op=${uploadBytes.sorted()[3]} retained=${store.encodedUsage()}",
        )

        val frame = store.assets.getValue(1).frames.first()
        repeat(3) { frame.decode(width, height).recycle() }
        val decodeTimes = mutableListOf<Long>()
        val decodeBytes = mutableListOf<Long>()
        repeat(7) {
            val before = allocations.getThreadAllocatedBytes(threadId)
            val start = System.nanoTime()
            val bitmap = frame.decode(width, height)
            decodeTimes += System.nanoTime() - start
            decodeBytes += allocations.getThreadAllocatedBytes(threadId) - before
            bitmap.recycle()
        }
        println(
            "INLINE_BENCH decode pixels=${width * height} ns/op=${decodeTimes.sorted()[3]} " +
                "java-bytes/op=${decodeBytes.sorted()[3]} bitmap-bytes=${width * height * 4}",
        )
    }
}
