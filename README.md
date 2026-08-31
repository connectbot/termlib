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
* Inline images using iTerm2 `imgcat` and Kitty's graphics protocol
* Shaping of complex scripts (e.g., Arabic, Bengali, Thai, etc)

### Planned

* Support for [iTerm2 escape codes](https://iterm2.com/documentation-escape-codes.html)
* Forced size terminal available (size in pixels returned via callback)
* Pasting support
* Shell prompt integration
* Scan for various text automatically, e.g.:
  * URLs
  * Compilation errors

## Used libraries

* libvterm by Paul Evans <leonerd@leonerd.org.uk>; MIT licensed
