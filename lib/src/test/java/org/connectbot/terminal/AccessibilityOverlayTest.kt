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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityOverlayTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun historyAnchorSurvivesScrollbackEviction() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 40) as TerminalEmulatorImpl
        emulator.writeInput((1..1030).joinToString("\r\n") { "Row $it" }.toByteArray())
        emulator.processPendingUpdates()
        val state = TerminalScreenState(emulator.snapshot.value)
        val list = LazyListState()
        composeTestRule.setContent {
            AccessibilityOverlay(state, 24f, lazyListState = list, modifier = Modifier.height(72.dp).testTag("history"))
        }
        composeTestRule.onNodeWithTag("history").performScrollToIndex(100)
        var anchor = ""
        composeTestRule.runOnIdle {
            anchor = state.snapshot.scrollback[list.firstVisibleItemIndex].text
            emulator.writeInput((1031..1040).joinToString("\r\n", prefix = "\r\n") { "Row $it" }.toByteArray())
            emulator.processPendingUpdates()
            state.updateSnapshot(emulator.snapshot.value)
        }
        composeTestRule.runOnIdle {
            assertEquals(anchor, state.snapshot.scrollback[list.firstVisibleItemIndex].text)
        }
    }

    @Test
    fun carriageReturnRedrawKeepsTheSemanticNode() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 20) as TerminalEmulatorImpl
        emulator.writeInput("Before".toByteArray())
        emulator.processPendingUpdates()
        val state = TerminalScreenState(emulator.snapshot.value)
        composeTestRule.setContent { AccessibilityOverlay(state, 24f, modifier = Modifier.fillMaxSize()) }
        val id = composeTestRule.onNodeWithText("Before", substring = true).fetchSemanticsNode().id
        composeTestRule.runOnIdle {
            emulator.writeInput("\rChange".toByteArray())
            emulator.processPendingUpdates()
            state.updateSnapshot(emulator.snapshot.value)
        }
        assertEquals(id, composeTestRule.onNodeWithText("Change", substring = true).fetchSemanticsNode().id)
    }

    @Test
    fun historySupportsAccessibilityScrolling() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 3, initialCols = 20)
        emulator.writeInput((1..30).joinToString("\r\n") { if (it == 1) "Oldest row" else "History $it" }.toByteArray())
        (emulator as TerminalEmulatorImpl).processPendingUpdates()
        composeTestRule.setContent {
            AccessibilityOverlay(
                screenState = rememberTerminalScreenState(emulator),
                charHeight = 24f,
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .testTag("history"),
            )
        }
        composeTestRule.onNodeWithTag("history").assert(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsActions.ScrollBy,
            ),
        )
        composeTestRule.onNodeWithText("History 30", substring = true).assertExists()
        composeTestRule.onNodeWithTag("history").performScrollToIndex(0)
        composeTestRule.onNodeWithText("Oldest row", substring = true).assertExists()
    }

    @Test
    fun testAccessibilityOverlayExposesLines() {
        runBlocking {
            val emulator = TerminalEmulatorFactory.create(
                initialRows = 5,
                initialCols = 20,
            )

            // Write test data
            emulator.writeInput("Hello World\r\n".toByteArray())
            emulator.writeInput("Line 2\r\n".toByteArray())

            (emulator as TerminalEmulatorImpl).processPendingUpdates()

            composeTestRule.setContent {
                TerminalWithAccessibility(
                    terminalEmulator = emulator,
                    forceAccessibilityEnabled = true,
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
                initialCols = 10,
            )

            // Write some empty lines
            emulator.writeInput("\r\n\r\n\r\n".toByteArray())

            (emulator as TerminalEmulatorImpl).processPendingUpdates()

            composeTestRule.setContent {
                TerminalWithAccessibility(
                    terminalEmulator = emulator,
                    forceAccessibilityEnabled = true,
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
                initialCols = 20,
            )

            emulator.writeInput("Plain text\r\n".toByteArray())

            (emulator as TerminalEmulatorImpl).processPendingUpdates()

            composeTestRule.setContent {
                TerminalWithAccessibility(
                    terminalEmulator = emulator,
                    forceAccessibilityEnabled = true,
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
                initialCols = 40,
            )

            // Write multiple lines
            for (i in 1..5) {
                emulator.writeInput("Line $i\r\n".toByteArray())
            }

            (emulator as TerminalEmulatorImpl).processPendingUpdates()

            composeTestRule.setContent {
                TerminalWithAccessibility(
                    terminalEmulator = emulator,
                    forceAccessibilityEnabled = true,
                )
            }

            // All lines should be accessible
            for (i in 1..5) {
                composeTestRule.onNodeWithText("Line $i", substring = true).assertExists()
            }
        }
    }
}
