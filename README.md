# ConnectBot Terminal

This is the ConnectBot Terminal, a Jetpack Compose component that displays a
terminal emulator. It uses libvterm via JNI to provide accurate terminal
emulation.

## Features

### Current 
* Written in Kotlin and C++
* 256-color and true color support
* East Asian (double width) characters support
* Combining character support
* Text selection
  * Uses "magnifying glass" effect when using touch for more accurate selection
  * Highlights text for selection
* Scrolling
* Zoomable
* Dynamically resizable
* Multiple font support

### Planned

* Inline display of images (compatible with [iTerm2 format](https://iterm2.com/documentation-images.html) via `imgcat`)
* Support for [iTerm2 escape codes](https://iterm2.com/documentation-escape-codes.html)
* Forced size terminal available (size in pixels returned via callback)
* Pasting support
* Shell prompt integration
* Scan for various text automatically, e.g.:
  * URLs
  * Compilation errors

## Host testing with Robolectric

The `termlib-host` artifact lets you run Robolectric tests without requiring a
native `.so` on the host. It ships a WASM-based backend and a Robolectric shadow
that transparently replaces `TerminalNative` so `TerminalEmulatorFactory.create()`
works as normal in tests.

### Setup

Add to your module's `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("org.connectbot:termlib:<version>")
    testImplementation("org.connectbot:termlib-host:<version>")
    testImplementation("org.robolectric:robolectric:<version>")
}
```

Then activate the shadow either per test class:

```kotlin
@RunWith(AndroidJUnit4::class)
@Config(shadows = [ShadowTerminalNative::class])
class MyTerminalTest { ... }
```

or globally for all tests in `src/test/resources/robolectric.properties`:

```
sdk=34
shadows=org.connectbot.terminal.wasm.ShadowTerminalNative
```

Once the shadow is active, use `TerminalEmulatorFactory.create()` as you normally
would — no `java.library.path` configuration or native build step required.

## Used libraries

* libvterm by Paul Evans <leonerd@leonerd.org.uk>; MIT licensed
