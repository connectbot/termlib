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
package org.connectbot.terminal.testapp

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.connectbot.terminal.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

const val escape = "\u001B"

/**
 * Test app screen with buttons to load different test assets.
 */
@Composable
fun ShellScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var terminalBuffer by remember { mutableStateOf<TerminalBuffer?>(null) }
    var terminalNative by remember { mutableStateOf<TerminalNative?>(null) }
    var currentTest by remember { mutableStateOf("Welcome") }
    var keyboardEnabled by remember { mutableStateOf(false) }

    // Initialize terminal once
    DisposableEffect(Unit) {
        scope.launch {
            try {
                // Create TerminalBuffer with automatic scrollback handling
                val buffer = TerminalBuffer.create(
                    initialRows = 24,
                    initialCols = 80,
                    defaultForeground = Color.White,
                    defaultBackground = Color.Black,
                    onKeyboardInput = { data ->
                        // Echo keyboard input back to terminal for testing
                        // In a real app, this would write to PTY which would echo back
                        terminalNative?.writeInput(data)
                    }
                )
                terminalBuffer = buffer

                // Get terminal from buffer for direct access
                val term = buffer.terminalNative
                terminalNative = term

                // Load welcome message
                val welcomeText = """
                    |Terminal Test Application
                    |===========================
                    |
                    |Use the buttons below to load different test files:
                    |
                    |$escape[1m256 Colors$escape[0m - Test 256-color palette rendering
                    |$escape[1mAttributes$escape[0m - Test bold, italic, underline, etc.
                    |$escape[1mUnicode$escape[0m    - Test Unicode characters and CJK
                    |$escape[1mScrolling$escape[0m  - Test scrolling with 25 lines
                    |
                    |$escape[1mKeyboard Input:$escape[0m
                    |Toggle the keyboard icon to enable typing.
                    |When enabled, you can type and see characters echoed.
                    |Try Ctrl+C, arrow keys, and other special keys!
                    |
                    |Select a test to begin.
                """.trimMargin()

                // Convert LF to CRLF for proper terminal display
                val normalizedWelcome = welcomeText.replace("\n", "\r\n")
                term.writeInput(normalizedWelcome.toByteArray())

            } catch (e: Exception) {
                errorMessage = "Failed to initialize: ${e.message}\n${e.stackTraceToString()}"
            }
        }

        onDispose {
            terminalNative?.close()
        }
    }

    // Load test function
    fun loadTest(testName: String, resourceId: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                terminalNative?.let { term ->
                    // Clear screen and scrollback
                    terminalBuffer?.clearScrollback()
                    term.writeInput("$escape[2J$escape[H".toByteArray())

                    // Load test file
                    context.resources.openRawResource(resourceId).use { inputStream ->
                        val reader = BufferedReader(InputStreamReader(inputStream))
                        val content = reader.readText()
                        // Convert LF to CRLF for proper terminal display
                        val normalizedContent = content.replace("\r\n", "\n").replace("\n", "\r\n")
                        term.writeInput(normalizedContent.toByteArray())
                    }

                    withContext(Dispatchers.Main) {
                        currentTest = testName
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to load test: ${e.message}"
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Button bar at top
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        terminalNative?.let { term ->
                            // Clear screen and scrollback
                            terminalBuffer?.clearScrollback()
                            term.writeInput("$escape[2J$escape[H".toByteArray())
                            val welcomeText = """
                                |TermScreen Test Application
                                |===========================
                                |
                                |Use the buttons below to load different test files:
                                |
                                |$escape[1m256 Colors$escape[0m - Test 256-color palette rendering
                                |$escape[1mAttributes$escape[0m - Test bold, italic, underline, etc.
                                |$escape[1mUnicode$escape[0m    - Test Unicode characters and CJK
                                |$escape[1mScrolling$escape[0m  - Test scrolling with 25 lines
                                |
                                |$escape[1mKeyboard Input:$escape[0m
                                |Toggle the keyboard icon to enable typing.
                                |When enabled, you can type and see characters echoed.
                                |Try Ctrl+C, arrow keys, and other special keys!
                                |
                                |Select a test to begin.
                            """.trimMargin()
                            // Convert LF to CRLF for proper terminal display
                            val normalizedWelcome = welcomeText.replace("\n", "\r\n")
                            term.writeInput(normalizedWelcome.toByteArray())
                            withContext(Dispatchers.Main) {
                                currentTest = "Welcome"
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentTest == "Welcome")
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Welcome", maxLines = 1)
            }

            Button(
                onClick = { loadTest("256 Colors", R.raw.test_output) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentTest == "256 Colors")
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("256 Colors", maxLines = 1)
            }

            Button(
                onClick = { loadTest("Attributes", R.raw.test_attributes) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentTest == "Attributes")
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Attributes", maxLines = 1)
            }

            Button(
                onClick = { loadTest("Unicode", R.raw.test_unicode) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentTest == "Unicode")
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Unicode", maxLines = 1)
            }

            Button(
                onClick = { loadTest("Scrolling", R.raw.test_scroll) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentTest == "Scrolling")
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Scrolling", maxLines = 1)
            }
        }

        // Second row with keyboard toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconToggleButton(
                checked = keyboardEnabled,
                onCheckedChange = { keyboardEnabled = it }
            ) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = "Toggle Keyboard Input",
                    tint = if (keyboardEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = if (keyboardEnabled) "Keyboard: ON" else "Keyboard: OFF",
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (keyboardEnabled)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }

        HorizontalDivider()

        // Terminal display area
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                terminalBuffer != null -> {
                    Terminal(
                        terminalBuffer = terminalBuffer!!,
                        modifier = Modifier.fillMaxSize(),
                        backgroundColor = Color.Black,
                        foregroundColor = Color(0xFFD0D0D0), // Light gray for better readability
                        keyboardEnabled = keyboardEnabled
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
