//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalEmulator](index.md)/[autoDetectUrls](auto-detect-urls.md)

# autoDetectUrls

[release]\
abstract val [autoDetectUrls](auto-detect-urls.md): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)

Whether plain-text URL auto-detection is enabled.

When true, hyperlink hit-testing continuously scans visible line text for URLs in addition to OSC 8 hyperlink segments. When false, hit-testing only uses OSC 8 segments. This does not affect [getUrls](get-urls.md), which always performs an explicit one-shot scan when called.