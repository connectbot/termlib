/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
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

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityOverlayTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testAccessibilityOverlayExposesLines() {
        runBlocking {
            val emulator = TerminalEmulatorFactory.create(
                initialRows = 5,
                initialCols = 20
            )

            // Write test data
            emulator.writeInput("Hello World\r\n".toByteArray())
            emulator.writeInput("Line 2\r\n".toByteArray())

            (emulator as TerminalEmulatorImpl).processPendingUpdates()

            composeTestRule.setContent {
                TerminalWithAccessibility(
                    terminalEmulator = emulator,
                    forceAccessibilityEnabled = true
                )
            }

            // Verify lines are accessible
            composeTestRule.onNodeWithText("Hello World", substring = true).assertExists()
            composeTestRule.onNodeWithText("Line 2", substring = true).assertExists()
        }
    }

    @Test
    fun testAccessibilityOverlayWithEmptyLines() {
        runBlocking {
            val emulator = TerminalEmulatorFactory.create(
                initialRows = 3,
                initialCols = 10
            )

            // Write some empty lines
            emulator.writeInput("\r\n\r\n\r\n".toByteArray())

            (emulator as TerminalEmulatorImpl).processPendingUpdates()

            composeTestRule.setContent {
                TerminalWithAccessibility(
                    terminalEmulator = emulator,
                    forceAccessibilityEnabled = true
                )
            }

            // Should not crash with empty lines
        }
    }

    @Test
    fun testSemanticDescriptionWithoutSegments() {
        runBlocking {
            val emulator = TerminalEmulatorFactory.create(
                initialRows = 3,
                initialCols = 20
            )

            emulator.writeInput("Plain text\r\n".toByteArray())

            (emulator as TerminalEmulatorImpl).processPendingUpdates()

            composeTestRule.setContent {
                TerminalWithAccessibility(
                    terminalEmulator = emulator,
                    forceAccessibilityEnabled = true
                )
            }

            // Plain text should be accessible
            composeTestRule.onNodeWithText("Plain text", substring = true).assertExists()
        }
    }

    @Test
    fun testMultipleLinesAccessible() {
        runBlocking {
            val emulator = TerminalEmulatorFactory.create(
                initialRows = 10,
                initialCols = 40
            )

            // Write multiple lines
            for (i in 1..5) {
                emulator.writeInput("Line $i\r\n".toByteArray())
            }

            (emulator as TerminalEmulatorImpl).processPendingUpdates()

            composeTestRule.setContent {
                TerminalWithAccessibility(
                    terminalEmulator = emulator,
                    forceAccessibilityEnabled = true
                )
            }

            // All lines should be accessible
            for (i in 1..5) {
                composeTestRule.onNodeWithText("Line $i", substring = true).assertExists()
            }
        }
    }
}
