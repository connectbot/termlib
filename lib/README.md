# Module ConnectBot Terminal

ConnectBot Terminal is a high-performance Jetpack Compose terminal emulator component for Android, providing accurate VT100/ANSI terminal emulation via libvterm.

## Key Features

- **Pure display component**: Handles terminal emulation and rendering only; caller manages PTY, SSH/Telnet connections, I/O, etc.
- **Jetpack Compose UI**: Modern declarative UI with Canvas-based rendering
- **Touch interactions**: Pinch-to-zoom, scrollback, text selection with magnifying glass
- **Thread-safe**: Mutex-protected libvterm integration with safe callback handling
- **Efficient rendering**: Batched cell retrieval with format-aware run-length optimization

## Core Components

- **Terminal**: JNI wrapper around libvterm providing input processing and keyboard event generation
- **TerminalBuffer**: Compose state management for terminal content and scrollback
- **TermScreen**: Main composable providing rendering and touch interaction
- **SelectionManager**: Text selection with character/word/line/block modes
- **KeyboardHandler**: Android KeyEvent to VTerm key mapping

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

```
PTY/SSH → TerminalEmulator.writeInput() → libvterm → Callbacks → TerminalEmulator → Terminal
Keyboard → TerminalEmulator.dispatchKey() → libvterm → onKeyboardInput() → PTY/SSH
```

**Important**: Callbacks must not call back into Terminal methods (causes deadlock). Defer work to avoid reentrancy.
