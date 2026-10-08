# Change Log
All notable changes to this project will be documented in this file.
This project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

<!-- Add user-facing changes here; use ## [version] headings when releasing. -->

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


[0.3.11]: https://github.com/connectbot/termlib/compare/0.3.10...0.3.11
[0.3.10]: https://github.com/connectbot/termlib/compare/0.3.9...0.3.10
[0.3.9]: https://github.com/connectbot/termlib/compare/0.3.8...0.3.9
