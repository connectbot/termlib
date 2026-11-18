/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A row's softWrapped flag is the next row's libvterm continuation, so it must
 * follow the next row even when the row itself is not damaged again.
 */
@RunWith(AndroidJUnit4::class)
class SoftWrapUpdateTest {

    private fun getSnapshot(impl: TerminalEmulatorImpl): TerminalSnapshot {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        impl.processPendingUpdates()
        return impl.snapshot.value
    }

    private fun TerminalEmulator.send(s: String) = writeInput(s.toByteArray())

    @Test
    fun testWrapInLaterUpdateMarksRowAboveSoftWrapped() = runBlocking {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 40)
        val impl = emulator as TerminalEmulatorImpl

        emulator.send("x".repeat(40))
        val before = getSnapshot(impl)
        assertFalse("row 0 has not wrapped yet", before.lines[0].softWrapped)

        emulator.send("y")
        val after = getSnapshot(impl)
        assertTrue("row 0 wrapped into row 1", after.lines[0].softWrapped)
    }
}
