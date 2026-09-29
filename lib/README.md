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
Mouse    → TerminalEmulator.scrollWheel() → libvterm → onKeyboardInput() → PTY/SSH
```

Mouse reports are only emitted once the running application asks for them with
DECSET 1000/1002/1003; check `TerminalEmulator.mouseTracking` to know whether a
gesture belongs to the application or to the terminal's own scrollback. It is
Compose state, so reading it in a composable subscribes to it.

While tracking is on, `Terminal` routes a tap to the application as a click and a
scroll as wheel detents. Long-press selection stays local — it remains the way to
copy text out of a full-screen application.

The public surface is `mouseClick` and `scrollWheel` only — a click is always
delivered with its release, and there is no way to report a bare press or bare
pointer motion, neither of which a touch gesture produces. Coordinates are
clamped to the screen and a single `scrollWheel` call reports a bounded number of
detents, so no caller can put a malformed report or an unbounded burst on the
wire.

**Important**: Callbacks must not call back into Terminal methods (causes deadlock). Defer work to avoid reentrancy.
