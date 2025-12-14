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

import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.io.BufferedReader
import java.io.InputStreamReader

const val escape = "\u001B"

/**
 * Represents a terminal session/tab
 */
data class TerminalSession(
    val name: String,
    val emulator: TerminalEmulator,
    val resourceId: Int
)

/**
 * Create a TerminalEmulator with keyboard echo for testing.
 * In a real app, onKeyboardInput would write to PTY.
 */
private fun createTerminalEmulator(): TerminalEmulator {
    lateinit var manager: TerminalEmulator
    manager = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color.White,
        defaultBackground = Color.Black,
        onKeyboardInput = { data ->
            // Echo keyboard input back to terminal for testing
            // In a real app, this would write to PTY which would echo back
            manager.writeInput(data)
        }
    )
    return manager
}

/**
 * Test app screen with multiple terminal sessions (tabs).
 * Each tab has its own TerminalEmulator instance, simulating
 * multiple SSH/Telnet connections in ConnectBot.
 */
@Composable
fun ShellScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentSessionIndex by remember { mutableStateOf(0) }
    var keyboardEnabled by remember { mutableStateOf(false) }

    // Detect hardware keyboard
    val configuration = LocalConfiguration.current
    val hasHardwareKeyboard = remember(configuration) {
        val keyboardType = configuration.keyboard
        keyboardType == android.content.res.Configuration.KEYBOARD_QWERTY ||
                keyboardType == android.content.res.Configuration.KEYBOARD_12KEY
    }

    // Auto-hide IME by default when hardware keyboard is present, but user can override
    var showSoftKeyboard by remember(hasHardwareKeyboard) { mutableStateOf(!hasHardwareKeyboard) }

    var useForcedSize by remember { mutableStateOf(false) }
    var customRows by remember { mutableStateOf(24) }
    var customCols by remember { mutableStateOf(80) }
    var showSizeDialog by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }

    // Color schemes
    data class ColorScheme(
        val name: String,
        val foreground: Color,
        val background: Color,
        val ansiColors: IntArray
    )

    val colorSchemes = remember {
        listOf(
            ColorScheme(
                name = "Default",
                foreground = Color.White,
                background = Color.Black,
                ansiColors = intArrayOf(
                    0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
                    0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
                    0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
                    0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
                )
            ),
            ColorScheme(
                name = "Solarized Light",
                foreground = Color(0xFF657B83),
                background = Color(0xFFFDF6E3),
                ansiColors = intArrayOf(
                    0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
                    0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
                    0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
                    0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt()
                )
            )
        )
    }
    var selectedColorScheme by remember { mutableStateOf(0) }

    // Font selection
    val availableFonts = remember {
        mapOf(
            "Monospace" to Typeface.MONOSPACE,
            "0xProto" to Typeface.createFromAsset(context.assets, "fonts/0xProtoNerdFontMono-Regular.ttf")
        )
    }
    var selectedFont by remember { mutableStateOf("0xProto Regular") }
    var showFontMenu by remember { mutableStateOf(false) }

    // Create separate TerminalEmulator for each session (simulating multiple connections)
    val sessions = remember {
        listOf(
            TerminalSession("Welcome", createTerminalEmulator(), R.raw.test_output),
            TerminalSession("256 Colors", createTerminalEmulator(), R.raw.test_output),
            TerminalSession("Attributes", createTerminalEmulator(), R.raw.test_attributes),
            TerminalSession("Unicode", createTerminalEmulator(), R.raw.test_unicode),
            TerminalSession("Scrolling", createTerminalEmulator(), R.raw.test_scroll)
        )
    }

    // Initialize all sessions once
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                // Load welcome message into first session
                val welcomeText = """
                    |Terminal Test Application
                    |===========================
                    |
                    |Each tab is a separate TerminalEmulator instance:
                    |
                    |$escape[1m256 Colors$escape[0m - Test 256-color palette rendering
                    |$escape[1mAttributes$escape[0m - Test bold, italic, underline, etc.
                    |$escape[1mUnicode$escape[0m    - Test Unicode characters and CJK
                    |$escape[1mScrolling$escape[0m  - Test scrolling with 25 lines
                    |
                    |$escape[1mMulti-Session Demo:$escape[0m
                    |Switch between tabs to see independent terminal sessions.
                    |Each tab maintains its own state, scrollback, and content.
                    |This simulates ConnectBot's multiple SSH connections.
                    |
                    |$escape[1mKeyboard Input:$escape[0m
                    |Toggle the keyboard icon to enable typing.
                    |Input is sent to the currently active tab.
                    |
                    |Select a tab to begin.
                """.trimMargin()

                val normalizedWelcome = welcomeText.replace("\n", "\r\n")
                sessions[0].emulator.writeInput(normalizedWelcome.toByteArray())

            } catch (e: Exception) {
                errorMessage = "Failed to initialize: ${e.message}\n${e.stackTraceToString()}"
            }
        }
    }

    // Get current terminal emulator
    val currentTerminal = sessions[currentSessionIndex].emulator

    // Load test content into a specific session
    fun loadTestIntoSession(sessionIndex: Int) {
        val session = sessions[sessionIndex]
        scope.launch(Dispatchers.IO) {
            try {
                // Clear screen
                session.emulator.clearScreen()

                // Load test file
                context.resources.openRawResource(session.resourceId).use { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val content = reader.readText()
                    // Convert LF to CRLF for proper terminal display
                    val normalizedContent = content.replace("\r\n", "\n").replace("\n", "\r\n")
                    session.emulator.writeInput(normalizedContent.toByteArray())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to load test for ${session.name}: ${e.message}"
                }
            }
        }
    }

    // Size configuration dialog
    if (showSizeDialog) {
        SizeConfigDialog(
            currentRows = customRows,
            currentCols = customCols,
            onDismiss = { showSizeDialog = false },
            onConfirm = { rows, cols ->
                customRows = rows
                customCols = cols
                showSizeDialog = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab bar at top - each tab is a separate TerminalEmulator session
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sessions.forEachIndexed { index, session ->
                Button(
                    onClick = {
                        currentSessionIndex = index
                        // Lazy load test content when tab is first selected
                        if (index > 0) {
                            loadTestIntoSession(index)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentSessionIndex == index)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = when (index) {
                            0 -> "Wel"
                            1 -> "256"
                            2 -> "Attr"
                            3 -> "Uni"
                            4 -> "Scr"
                            else -> session.name.take(3)
                        },
                        maxLines = 1
                    )
                }
            }
        }

        // Settings button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Box {
                Button(
                    onClick = { showSettingsMenu = true }
                ) {
                    Text("⚙ Settings")
                }
                DropdownMenu(
                    expanded = showSettingsMenu,
                    onDismissRequest = { showSettingsMenu = false }
                ) {
                    // Color scheme selector
                    Text(
                        text = "Color Scheme",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    colorSchemes.forEachIndexed { index, scheme ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (index == selectedColorScheme) Text("✓ ") else Text("   ")
                                    Text(scheme.name)
                                }
                            },
                            onClick = {
                                selectedColorScheme = index
                                // Apply color scheme to all terminal sessions
                                sessions.forEach { session ->
                                    session.emulator.applyColorScheme(
                                        ansiColors = scheme.ansiColors,
                                        defaultForeground = scheme.foreground.toArgb(),
                                        defaultBackground = scheme.background.toArgb()
                                    )
                                }
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Font selector submenu
                    Text(
                        text = "Font: $selectedFont",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    availableFonts.keys.forEach { fontName ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (fontName == selectedFont) Text("✓ ") else Text("   ")
                                    Text(fontName)
                                }
                            },
                            onClick = {
                                selectedFont = fontName
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Size configuration
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Fixed Size")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (useForcedSize) "${customRows}×${customCols}" else "Auto",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Switch(
                                        checked = useForcedSize,
                                        onCheckedChange = { useForcedSize = it },
                                        modifier = Modifier.height(24.dp)
                                    )
                                }
                            }
                        },
                        onClick = { showSizeDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Keyboard input toggle
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = CustomIcons.Keyboard,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp).padding(end = 8.dp)
                                    )
                                    Text("Keyboard Input")
                                }
                                Switch(
                                    checked = keyboardEnabled,
                                    onCheckedChange = { keyboardEnabled = it },
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        },
                        onClick = { keyboardEnabled = !keyboardEnabled }
                    )

                    // IME toggle (only when keyboard enabled)
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = CustomIcons.PhoneAndroid,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp).padding(end = 8.dp),
                                        tint = if (keyboardEnabled)
                                            LocalContentColor.current
                                        else
                                            LocalContentColor.current.copy(alpha = 0.38f)
                                    )
                                    Text(
                                        "Show Soft Keyboard",
                                        color = if (keyboardEnabled)
                                            LocalContentColor.current
                                        else
                                            LocalContentColor.current.copy(alpha = 0.38f)
                                    )
                                }
                                Switch(
                                    checked = showSoftKeyboard,
                                    onCheckedChange = { showSoftKeyboard = it },
                                    enabled = keyboardEnabled,
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        },
                        onClick = { if (keyboardEnabled) showSoftKeyboard = !showSoftKeyboard },
                        enabled = keyboardEnabled
                    )
                }
            }
        }

        HorizontalDivider()

        // Terminal display area - shows current session
        Box(modifier = Modifier.fillMaxSize()) {
            if (errorMessage != null) {
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
            } else {
                // Display the currently selected terminal session
                Terminal(
                    terminalEmulator = currentTerminal,
                    modifier = Modifier.fillMaxSize(),
                    typeface = availableFonts[selectedFont] ?: Typeface.MONOSPACE,
                    backgroundColor = colorSchemes[selectedColorScheme].background,
                    foregroundColor = colorSchemes[selectedColorScheme].foreground,
                    keyboardEnabled = keyboardEnabled,
                    showSoftKeyboard = showSoftKeyboard,
                    forcedSize = if (useForcedSize) Pair(customRows, customCols) else null
                )
            }
        }
    }
}

/**
 * Dialog for configuring custom terminal size with presets
 */
@Composable
fun SizeConfigDialog(
    currentRows: Int,
    currentCols: Int,
    onDismiss: () -> Unit,
    onConfirm: (rows: Int, cols: Int) -> Unit
) {
    var rows by remember { mutableStateOf(currentRows.toString()) }
    var cols by remember { mutableStateOf(currentCols.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminal Size") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Preset buttons
                Text(
                    text = "Presets:",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { rows = "24"; cols = "80" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("24×80", maxLines = 1)
                    }
                    Button(
                        onClick = { rows = "25"; cols = "80" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("25×80", maxLines = 1)
                    }
                    Button(
                        onClick = { rows = "40"; cols = "132" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("40×132", maxLines = 1)
                    }
                }

                // Custom input fields
                Text(
                    text = "Custom:",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = rows,
                        onValueChange = {
                            if (it.isEmpty() || it.toIntOrNull() != null) {
                                rows = it
                            }
                        },
                        label = { Text("Rows") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = cols,
                        onValueChange = {
                            if (it.isEmpty() || it.toIntOrNull() != null) {
                                cols = it
                            }
                        },
                        label = { Text("Cols") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // Info text
                Text(
                    text = "Common sizes:\n• 24×80 - Classic terminal\n• 25×80 - VT100 standard\n• 40×132 - Wide format",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val r = rows.toIntOrNull()?.coerceIn(1, 200) ?: currentRows
                    val c = cols.toIntOrNull()?.coerceIn(1, 500) ?: currentCols
                    onConfirm(r, c)
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
