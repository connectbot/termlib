# Change Log
All notable changes to this project will be documented in this file.
This project adheres to [Semantic Versioning](https://semver.org/).

## [0.3.15][0.3.15]

### Fixed
- Clamp oversized CSI numeric arguments and reject excess parameters and subparameters to prevent parser overflow and out-of-bounds access
- Ignore REP (repeat character) sequences when no preceding graphic character exists
- Reset scroll regions invalidated by resize and reject empty or inverted scroll rectangles

### Dependencies
- Update vendored libvterm to revision 847 with additional Vim parser and resize fixes

## [0.3.14][0.3.14]

### Added
- Add `TerminalEmulator.close()` to release native state, screen history, and inline images when disposing a session
- Add translations for terminal text selection actions and accessibility descriptions

### Fixed
- Break JNI callback ownership cycles that prevented terminal sessions from being released
- Advance the cursor to the last row of Kitty image placements without an extra linefeed or unwanted scrolling (by GlassOnTin)

### Changed
- Route terminal diagnostics through SLF4J; applications can supply a compatible logging provider to receive messages

## [0.3.13][0.3.13]

### Fixed
- Prevent a crash in bidirectional text rendering when paragraph markers cross line boundaries

### Changed
- Reduce repeated draw calls when rendering stippled characters

## [0.3.12][0.3.12]

### Fixed
- Clamp stale accessibility ranges and use terminal cell columns to handle wide and combining characters correctly

## [0.3.11][0.3.11]

### Fixed
- Cancel terminal gestures consumed by parent components so session swipes do not trigger terminal taps or long presses (by nindanaoto)
- Keep the old terminal dimensions available to scrollback callbacks during resize

### Changed
- Disable text reflow during resize until scrollback, wide characters, and metadata can be preserved reliably

### Dependencies
- Update Kover from 0.9.9 to 0.9.10

## [0.3.10][0.3.10]

### Fixed
- Prevent a native crash when resizing wrapped text whose beginning has scrolled off-screen
- Send the Escape prefix and character together in one output callback for Alt-modified input

### Dependencies
- Update Gradle setup and wrapper validation actions from 6.3.0 to 6.4.0

## [0.3.9][0.3.9]

### Added
- Add an optional Page Up and Page Down gesture callback for vertical swipes starting in the left third of the terminal

### Fixed
- Use the actual initial touch position for text selection near terminal edges
- Preserve Kitty image transfer options and complete uploads with header-only opening and closing chunks

### Dependencies
- Update Gradle wrapper from 9.7.1 to 9.8.0
- Update Spotless from 8.10.2 to 8.10.3
- Update AndroidX Core from 1.19.0 to 1.19.1


[0.3.15]: https://github.com/connectbot/termlib/compare/0.3.14...0.3.15
[0.3.14]: https://github.com/connectbot/termlib/compare/0.3.13...0.3.14
[0.3.13]: https://github.com/connectbot/termlib/compare/0.3.12...0.3.13
[0.3.12]: https://github.com/connectbot/termlib/compare/0.3.11...0.3.12
[0.3.11]: https://github.com/connectbot/termlib/compare/0.3.10...0.3.11
[0.3.10]: https://github.com/connectbot/termlib/compare/0.3.9...0.3.10
[0.3.9]: https://github.com/connectbot/termlib/compare/0.3.8...0.3.9
