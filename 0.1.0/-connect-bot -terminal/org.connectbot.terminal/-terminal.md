//[ConnectBot Terminal](../../index.md)/[org.connectbot.terminal](index.md)/[Terminal](-terminal.md)

# Terminal

[release]\

@[Composable](https://developer.android.com/reference/kotlin/androidx/compose/runtime/Composable.html)

fun [Terminal](-terminal.md)(terminalEmulator: [TerminalEmulator](-terminal-emulator/index.md), modifier: [Modifier](https://developer.android.com/reference/kotlin/androidx/compose/ui/Modifier.html) = Modifier, typeface: [Typeface](https://developer.android.com/reference/kotlin/android/graphics/Typeface.html) = Typeface.MONOSPACE, initialFontSize: [TextUnit](https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/TextUnit.html) = 11.sp, minFontSize: [TextUnit](https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/TextUnit.html) = 6.sp, maxFontSize: [TextUnit](https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/TextUnit.html) = 30.sp, backgroundColor: [Color](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Color.html) = Color.Black, foregroundColor: [Color](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Color.html) = Color.White, selectionBackgroundColor: [Color](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Color.html) = Color(0xFFB3D7FF), selectionForegroundColor: [Color](https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Color.html) = Color.Black, keyboardEnabled: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = false, showSoftKeyboard: [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) = true, focusRequester: [FocusRequester](https://developer.android.com/reference/kotlin/androidx/compose/ui/focus/FocusRequester.html) = remember { FocusRequester() }, onTerminalTap: () -&gt; [Unit](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-unit/index.html) = {}, onImeVisibilityChanged: ([Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)) -&gt; [Unit](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-unit/index.html) = {}, forcedSize: [Pair](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-pair/index.html)&lt;[Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)&gt;? = null, modifierManager: [ModifierManager](-modifier-manager/index.md)? = null, onSelectionControllerAvailable: ([SelectionController](-selection-controller/index.md)) -&gt; [Unit](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-unit/index.html)? = null, onHyperlinkClick: ([String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html)) -&gt; [Unit](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-unit/index.html) = {}, onComposeControllerAvailable: ([ComposeController](-compose-controller/index.md)) -&gt; [Unit](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-unit/index.html)? = null, onPasteRequest: () -&gt; [Unit](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-unit/index.html)? = null, rightAltMode: [RightAltMode](-right-alt-mode/index.md) = RightAltMode.CharacterModifier, delKeyMode: [DelKeyMode](-del-key-mode/index.md) = DelKeyMode.Delete, onInterceptKey: ([KeyEvent](https://developer.android.com/reference/kotlin/androidx/compose/ui/input/key/KeyEvent.html)) -&gt; [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)? = null)

Terminal - A Jetpack Compose terminal screen component.

This component:

- 
   Renders terminal output using Canvas
- 
   Handles terminal resize based on available space
- 
   Displays cursor
- 
   Supports colors, bold, italic, underline, etc.

#### Parameters

release

| | |
|---|---|
| terminalEmulator | The terminal emulator containing terminal state |
| modifier | Modifier for the composable |
| typeface | Typeface for terminal text (default: Typeface.MONOSPACE) |
| initialFontSize | Initial font size for terminal text (can be changed with pinch-to-zoom) |
| minFontSize | Minimum font size for pinch-to-zoom |
| maxFontSize | Maximum font size for pinch-to-zoom |
| backgroundColor | Default background color |
| foregroundColor | Default foreground color |
| keyboardEnabled | Enable keyboard input handling (default: false for display-only mode).                         When false, no keyboard input (hardware or soft) is accepted. |
| showSoftKeyboard | Whether to show the soft keyboard/IME (default: true when keyboardEnabled=true).                          Only applies when keyboardEnabled=true. Hardware keyboard always works when keyboardEnabled=true. |
| focusRequester | Focus requester for keyboard input (if enabled) |
| onTerminalTap | Callback for a simple tap event on the terminal (when no selection is active) |
| onImeVisibilityChanged | Callback invoked when IME visibility changes (true = shown, false = hidden) |
| forcedSize | Force terminal to specific dimensions (rows, cols). When set, font size is calculated to fit. |
| onSelectionControllerAvailable | Optional callback providing access to the SelectionController for controlling selection mode |
| onHyperlinkClick | Callback when user taps on an OSC8 hyperlink. Receives the URL as parameter. |
| onComposeControllerAvailable | Optional callback providing access to the ComposeController for handling IME compose state |
| onPasteRequest | Optional callback for handling a request to paste content, normally triggered by a context menu action |
| rightAltMode | How the right-alt key should behave (CharacterModifier vs Meta) |
| selectionBackgroundColor | Background color for selected text (default: 0xFFB3D7FF) |
| selectionForegroundColor | Foreground color for selected text (default: Black) |
| delKeyMode | How the backspace/delete keys should map to terminal characters |
| onInterceptKey | Optional callback to intercept raw Compose KeyEvents before the terminal emulator handles them. Return true to consume the event. |