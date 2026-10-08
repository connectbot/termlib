//[ConnectBot Terminal](index.md)

# ConnectBot Terminal

[release]\
ConnectBot Terminal is a high-performance Jetpack Compose terminal emulator component for Android, providing accurate VT100/ANSI terminal emulation via libvterm.

## Key Features

- 
   **Pure display component**: Handles terminal emulation and rendering only; caller manages PTY, SSH/Telnet connections, I/O, etc.
- 
   **Jetpack Compose UI**: Modern declarative UI with Canvas-based rendering
- 
   **Touch interactions**: Pinch-to-zoom, scrollback, text selection with magnifying glass
- 
   **Thread-safe**: Mutex-protected libvterm integration with safe callback handling
- 
   **Efficient rendering**: Batched cell retrieval with format-aware run-length optimization

## Core Components

- 
   **Terminal**: JNI wrapper around libvterm providing input processing and keyboard event generation
- 
   **TerminalBuffer**: Compose state management for terminal content and scrollback
- 
   **TermScreen**: Main composable providing rendering and touch interaction
- 
   **SelectionManager**: Text selection with character/word/line/block modes
- 
   **KeyboardHandler**: Android KeyEvent to VTerm key mapping

## Usage

In your Compose UI:

```kotlin
val emulator = TerminalEmulatorFactory.create(
    onKeyBoardInput = {
        // … send data to PTY/SSH/etc
    }
)

// Feed data from PTY/SSH
emulator.writeInput(data, offset, length)

// Render
Terminal(
    terminalEmulator = emulator
)
```

## Architecture

```kotlin
PTY/SSH → TerminalEmulator.writeInput() → libvterm → Callbacks → TerminalEmulator → Terminal
Keyboard → TerminalEmulator.dispatchKey() → libvterm → onKeyboardInput() → PTY/SSH
```

**Important**: Callbacks must not call back into Terminal methods (causes deadlock). Defer work to avoid reentrancy.

## Packages

| Name |
|---|
| [org.connectbot.terminal](-connect-bot -terminal/org.connectbot.terminal/index.md) |

<!-- BEGIN DOCS API CHANGES -->
## New and changed APIs

Changes since 0.0.40.

[Compare source versions](https://github.com/connectbot/termlib/compare/0.0.40...0.1.0)

### Terminal

#### org.connectbot.terminal.TerminalKt

- Changed: [fun Terminal(terminalEmulator: TerminalEmulator, modifier: Modifier = Modifier, typeface: Typeface = Typeface.MONOSPACE, initialFontSize: TextUnit = 11.sp, minFontSize: TextUnit = 6.sp, maxFontSize: TextUnit = 30.sp, backgroundColor: Color = Color.Black, foregroundColor: Color = Color.White, selectionBackgroundColor: Color = Color(0xFFB3D7FF), selectionForegroundColor: Color = Color.Black, keyboardEnabled: Boolean = false, showSoftKeyboard: Boolean = true, focusRequester: FocusRequester = remember { FocusRequester() }, onTerminalTap: () -> Unit = {}, onImeVisibilityChanged: (Boolean) -> Unit = {}, forcedSize: Pair<Int, Int>? = null, modifierManager: ModifierManager? = null, onSelectionControllerAvailable: (SelectionController) -> Unit? = null, onHyperlinkClick: (String) -> Unit = {}, onComposeControllerAvailable: (ComposeController) -> Unit? = null, onPasteRequest: () -> Unit? = null, rightAltMode: RightAltMode = RightAltMode.CharacterModifier, delKeyMode: DelKeyMode = DelKeyMode.Delete, onInterceptKey: (KeyEvent) -> Boolean? = null)](-connect-bot%20-terminal/org.connectbot.terminal/-terminal.html)
<!-- END DOCS API CHANGES -->
