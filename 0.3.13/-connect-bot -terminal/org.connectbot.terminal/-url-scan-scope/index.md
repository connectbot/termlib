//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[UrlScanScope](index.md)

# UrlScanScope

sealed class [UrlScanScope](index.md)

Line range to scan for URLs.

With the current terminal state model, both scopes scan primary scrollback plus the visible primary screen while the primary screen is active. While the alternate screen is active, both scopes scan only the alternate screen because the alternate screen does not have scrollback.

#### Inheritors

| |
|---|
| [CurrentView](-current-view/index.md) |
| [ScreenAndScrollback](-screen-and-scrollback/index.md) |

## Types

| Name | Summary |
|---|---|
| [CurrentView](-current-view/index.md) | [release]<br>data object [CurrentView](-current-view/index.md) : [UrlScanScope](index.md)<br>Scan what users currently see as terminal output: primary scrollback plus visible primary screen, or only the active alternate screen. |
| [ScreenAndScrollback](-screen-and-scrollback/index.md) | [release]<br>data object [ScreenAndScrollback](-screen-and-scrollback/index.md) : [UrlScanScope](index.md)<br>Scan the currently active screen plus its scrollback, when that screen has scrollback. |