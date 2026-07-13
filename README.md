# ConnectBot Terminal

This is the ConnectBot Terminal, a Jetpack Compose component that displays a
terminal emulator. It uses libvterm via JNI to provide accurate terminal
emulation.

## Documentation

The latest public API documentation is available at
[termlib.connectbot.org](https://termlib.connectbot.org/).

Generate the documentation locally with:

```shell
./gradlew :lib:dokkaGeneratePublicationHtml --no-configuration-cache
```

The generated site is written to `lib/build/dokka/html`.

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

## Used libraries

* libvterm by Paul Evans <leonerd@leonerd.org.uk>; MIT licensed
