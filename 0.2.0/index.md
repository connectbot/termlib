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

Changes since 0.1.0.

[Compare source versions](https://github.com/connectbot/termlib/compare/0.1.0...0.2.0)

### Terminal

#### org.connectbot.terminal.InlineImageLimits

- Added: [constructor(encodedBytes: Int = 16 * 1024 * 1024, decodedBytes: Int = 32 * 1024 * 1024, transferBytes: Int = 4 * 1024 * 1024, uploadBytes: Int = 16 * 1024 * 1024, maxDimension: Int, maxPixels: Int = 16 * 1024 * 1024, maxImages: Int = 1024, maxPlacements: Int = 4096, maxFrames: Int = 4096)](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-limits/-inline-image-limits.html)

- Added: [val decodedBytes: Int](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-limits/decoded-bytes.html)

- Added: [val encodedBytes: Int](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-limits/encoded-bytes.html)

- Added: [val maxDimension: Int](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-limits/max-dimension.html)

- Added: [val maxFrames: Int](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-limits/max-frames.html)

- Added: [val maxImages: Int](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-limits/max-images.html)

- Added: [val maxPixels: Int](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-limits/max-pixels.html)

- Added: [val maxPlacements: Int](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-limits/max-placements.html)

- Added: [val transferBytes: Int](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-limits/transfer-bytes.html)

- Added: [val uploadBytes: Int](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-limits/upload-bytes.html)

- Added: [data class InlineImageLimits(val encodedBytes: Int = 16 * 1024 * 1024, val decodedBytes: Int = 32 * 1024 * 1024, val transferBytes: Int = 4 * 1024 * 1024, val uploadBytes: Int = 16 * 1024 * 1024, val maxDimension: Int, val maxPixels: Int = 16 * 1024 * 1024, val maxImages: Int = 1024, val maxPlacements: Int = 4096, val maxFrames: Int = 4096)](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-limits/index.html)

#### org.connectbot.terminal.InlineImageProtocolType

- Added: [ITERM2](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-protocol-type/-i-t-e-r-m2/index.html)

- Added: [KITTY](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-protocol-type/-k-i-t-t-y/index.html)

- Added: [enum InlineImageProtocolType : Enum<InlineImageProtocolType> ](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-protocol-type/index.html)

#### org.connectbot.terminal.InlineImageRequest

- Added: [constructor(protocol: InlineImageProtocolType, action: String, imageId: Long? = null, imageNumber: Long? = null, name: String? = null, declaredSizeBytes: Long? = null, pixelWidth: Int? = null, pixelHeight: Int? = null)](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-request/-inline-image-request.html)

- Added: [val action: String](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-request/action.html)

- Added: [val declaredSizeBytes: Long?](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-request/declared-size-bytes.html)

- Added: [val imageId: Long?](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-request/image-id.html)

- Added: [val imageNumber: Long?](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-request/image-number.html)

- Added: [val name: String?](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-request/name.html)

- Added: [val pixelHeight: Int?](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-request/pixel-height.html)

- Added: [val pixelWidth: Int?](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-request/pixel-width.html)

- Added: [val protocol: InlineImageProtocolType](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-request/protocol.html)

- Added: [data class InlineImageRequest(val protocol: InlineImageProtocolType, val action: String, val imageId: Long? = null, val imageNumber: Long? = null, val name: String? = null, val declaredSizeBytes: Long? = null, val pixelWidth: Int? = null, val pixelHeight: Int? = null)](-connect-bot%20-terminal/org.connectbot.terminal/-inline-image-request/index.html)

#### org.connectbot.terminal.InlineImages

- Added: [sealed class InlineImages](-connect-bot%20-terminal/org.connectbot.terminal/-inline-images/index.html)

#### org.connectbot.terminal.InlineImages.Ask

- Added: [constructor(limits: InlineImageLimits = InlineImageLimits(), confirm: suspend (InlineImageRequest) -> Boolean)](-connect-bot%20-terminal/org.connectbot.terminal/-inline-images/-ask/-ask.html)

- Added: [val confirm: suspend (InlineImageRequest) -> Boolean](-connect-bot%20-terminal/org.connectbot.terminal/-inline-images/-ask/confirm.html)

- Added: [val limits: InlineImageLimits](-connect-bot%20-terminal/org.connectbot.terminal/-inline-images/-ask/limits.html)

- Added: [data class Ask(val limits: InlineImageLimits = InlineImageLimits(), val confirm: suspend (InlineImageRequest) -> Boolean) : InlineImages](-connect-bot%20-terminal/org.connectbot.terminal/-inline-images/-ask/index.html)

#### org.connectbot.terminal.InlineImages.Off

- Added: [data object Off : InlineImages](-connect-bot%20-terminal/org.connectbot.terminal/-inline-images/-off/index.html)

#### org.connectbot.terminal.InlineImages.On

- Added: [constructor(limits: InlineImageLimits = InlineImageLimits())](-connect-bot%20-terminal/org.connectbot.terminal/-inline-images/-on/-on.html)

- Added: [val limits: InlineImageLimits](-connect-bot%20-terminal/org.connectbot.terminal/-inline-images/-on/limits.html)

- Added: [data class On(val limits: InlineImageLimits = InlineImageLimits()) : InlineImages](-connect-bot%20-terminal/org.connectbot.terminal/-inline-images/-on/index.html)

#### org.connectbot.terminal.TerminalDimensions

- Changed: [constructor(rows: Int, columns: Int)](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-dimensions/-terminal-dimensions.html)

- Changed: [constructor(rows: Int, columns: Int, widthPixels: Int, heightPixels: Int)](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-dimensions/-terminal-dimensions.html)

- Changed: [fun copy(rows: Int, columns: Int): TerminalDimensions](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-dimensions/copy.html)

- Added: [val heightPixels: Int](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-dimensions/height-pixels.html)

- Added: [val widthPixels: Int](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-dimensions/width-pixels.html)

#### org.connectbot.terminal.TerminalEmulator

- Added: [abstract fun pasteText(text: String)](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-emulator/paste-text.html)

- Added: [abstract fun setCellPixelSize(width: Int, height: Int)](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-emulator/set-cell-pixel-size.html)

- Added: [abstract fun setInlineImages(inlineImages: InlineImages)](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-emulator/set-inline-images.html)

- Added: [abstract val inlineImages: InlineImages](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-emulator/inline-images.html)

#### org.connectbot.terminal.TerminalEmulatorFactory.Companion

- Changed: [fun create(looper: Looper = Looper.getMainLooper(), initialRows: Int = 24, initialCols: Int = 80, defaultForeground: Color = Color.White, defaultBackground: Color = Color.Black, onKeyboardInput: (ByteArray) -> Unit = {}, onBell: () -> Unit? = null, onResize: (TerminalDimensions) -> Unit? = null, onClipboardCopy: (String) -> Unit? = null, onProgressChange: (ProgressState, Int) -> Unit? = null, autoDetectUrls: Boolean = false, boldAsBright: Boolean = true, inlineImages: InlineImages = InlineImages.Off): TerminalEmulator](-connect-bot%20-terminal/org.connectbot.terminal/-terminal-emulator-factory/-companion/create.html)
<!-- END DOCS API CHANGES -->
