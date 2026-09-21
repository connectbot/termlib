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

## Keyboard visibility and temporary layouts

Set `keyboardEnabled = true` to accept input. With `showSoftKeyboard = true`
(the default), entering the terminal and tapping its text area explicitly
request the software keyboard, including when the editor already has focus.
Android and the selected IME retain control over hardware-keyboard policy.
Back dismissal is respected until the next explicit request; output and
hardware keypresses do not reopen the keyboard. Setting
`showSoftKeyboard = false` also disables tap-to-show.
`onImeVisibilityChanged` reports observed window IME visibility, not request
success.

Hosts such as ConnectBot's ConsoleScreen can pass `resizeSuspended = true`
before opening a menu or temporarily moving interaction elsewhere. The terminal
keeps its existing grid and pixel dimensions while still processing output.
Intermediate container sizes are discarded. After suspension ends, only the
latest size is applied, and an unchanged final size sends no resize callback.

Keep suspension active through popup dismissal and any keyboard restoration and
layout animation, not just while the menu's `expanded` flag is true. The demo's
settings menu illustrates retaining the pause until window focus returns and
IME insets settle. Suspension does not hide the keyboard or change input
eligibility.  The option defaults to false, so existing hosts retain automatic
resizing.

## Architecture

```
PTY/SSH → TerminalEmulator.writeInput() → libvterm → Callbacks → TerminalEmulator → Terminal
Keyboard → TerminalEmulator.dispatchKey() → libvterm → onKeyboardInput() → PTY/SSH
```

**Important**: Callbacks must not call back into Terminal methods (causes deadlock). Defer work to avoid reentrancy.
