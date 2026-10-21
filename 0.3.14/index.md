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
    onKeyboardInput = {
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

## Session lifetime

The session owner must call `emulator.close()` when permanently disposing the session, after stopping its input producers and detaching the terminal UI. `close()` releases native state, screen/history, and inline images. It is safe to call repeatedly and does not initialize an unused native terminal. Further mutations and content queries throw `IllegalStateException`; scalar metadata remains readable. Previously retained snapshots own their own resources.

The `Terminal` composable borrows its emulator and does not close it on view removal. Keep sessions open across tab switches and temporary UI detachment. For a screen that owns its sessions, use `DisposableEffect(sessions)` to close those sessions when that owner leaves composition. Non-UI owners can use Kotlin `use` or Java try-with-resources. GC cleanup is only a best-effort fallback.

## Keyboard visibility and temporary layouts

Set `keyboardEnabled = true` to accept input. With `showSoftKeyboard = true` (the default), entering the terminal and tapping its text area explicitly request the software keyboard, including when the editor already has focus. Android and the selected IME retain control over hardware-keyboard policy. Back dismissal is respected until the next explicit request; output and hardware keypresses do not reopen the keyboard. Setting `showSoftKeyboard = false` also disables tap-to-show. `onImeVisibilityChanged` reports observed window IME visibility, not request success.

Hosts such as ConnectBot's ConsoleScreen can pass `resizeSuspended = true` before opening a menu or temporarily moving interaction elsewhere. The terminal keeps its existing grid and pixel dimensions while still processing output. Intermediate container sizes are discarded. After suspension ends, only the latest size is applied, and an unchanged final size sends no resize callback.

Keep suspension active through popup dismissal and any keyboard restoration and layout animation, not just while the menu's `expanded` flag is true. The demo's settings menu illustrates retaining the pause until window focus returns and IME insets settle. Suspension does not hide the keyboard or change input eligibility.  The option defaults to false, so existing hosts retain automatic resizing.

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

Changes since 0.3.13.

[Compare source versions](https://github.com/connectbot/termlib/compare/0.3.13...0.3.14)

### Terminal

#### org.connectbot.terminal.SelectionController

- Added: [open fun selectAllVisible()](-connect-bot%20-terminal/org.connectbot.terminal/-selection-controller/select-all-visible.html)

#### org.connectbot.terminal.TerminalEmulator

- Added: [abstract override fun close()](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-emulator/close.html)

- Changed: [sealed interface TerminalEmulator : AutoCloseable](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-emulator/index.html)

### Release notes

### Added
- Add `TerminalEmulator.close()` to release native state, screen history, and inline images when disposing a session
- Add translations for terminal text selection actions and accessibility descriptions

### Fixed
- Break JNI callback ownership cycles that prevented terminal sessions from being released
- Advance the cursor to the last row of Kitty image placements without an extra linefeed or unwanted scrolling (by GlassOnTin)

### Changed
- Route terminal diagnostics through SLF4J; applications can supply a compatible logging provider to receive messages

[0.3.14]: https://github.com/connectbot/termlib/compare/0.3.13...0.3.14
[0.3.13]: https://github.com/connectbot/termlib/compare/0.3.12...0.3.13
[0.3.12]: https://github.com/connectbot/termlib/compare/0.3.11...0.3.12
[0.3.11]: https://github.com/connectbot/termlib/compare/0.3.10...0.3.11
[0.3.10]: https://github.com/connectbot/termlib/compare/0.3.9...0.3.10
[0.3.9]: https://github.com/connectbot/termlib/compare/0.3.8...0.3.9

[View changelog](https://github.com/connectbot/termlib/blob/0bd42f06e3ae88e5fc9e2f505a77b443cea26865/CHANGELOG.md)
<!-- END DOCS API CHANGES -->
