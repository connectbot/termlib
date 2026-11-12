//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[TerminalEmulator](index.md)/[getLastCommandOutput](get-last-command-output.md)

# getLastCommandOutput

[release]\
abstract fun [getLastCommandOutput](get-last-command-output.md)(): [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html)?

Get the text output of the last completed command.

Uses OSC 133 semantic segments to find the boundaries of the most recent completed command output. Requires shell integration (OSC 133) to be enabled in the user's shell.

#### Return

The command output text, or null if no completed command is found