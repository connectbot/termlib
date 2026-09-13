//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalEmulator](index.md)/[getUrls](get-urls.md)

# getUrls

[release]\
abstract fun [getUrls](get-urls.md)(scope: [UrlScanScope](../-url-scan-scope/index.md) = UrlScanScope.CurrentView): [List](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.collections/-list/index.html)&lt;[TerminalUrl](../-terminal-url/index.md)&gt;

Extract URLs from terminal output.

This always performs explicit OSC 8 and plain-text regex URL extraction, independent of [autoDetectUrls](auto-detect-urls.md). Plain-text URL extraction includes URLs split across wrapped adjacent rows.

Primary-screen scans include scrollback before visible screen lines. While the alternate screen is active, primary scrollback is not scanned.