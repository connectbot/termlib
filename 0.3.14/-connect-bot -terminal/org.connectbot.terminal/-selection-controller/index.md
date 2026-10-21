//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[SelectionController](index.md)

# SelectionController

[release]\
interface [SelectionController](index.md)

Interface for controlling text selection in the terminal. This allows external components (UI chrome, keyboard handlers, accessibility) to control selection.

## Properties

| Name | Summary |
|---|---|
| [isSelectionActive](is-selection-active.md) | [release]<br>abstract val [isSelectionActive](is-selection-active.md): [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)<br>Check if selection mode is currently active. |

## Functions

| Name | Summary |
|---|---|
| [clearSelection](clear-selection.md) | [release]<br>abstract fun [clearSelection](clear-selection.md)()<br>Clear the selection without copying. |
| [copySelection](copy-selection.md) | [release]<br>abstract fun [copySelection](copy-selection.md)(): [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html)<br>Copy the selected text to clipboard and clear the selection. |
| [finishSelection](finish-selection.md) | [release]<br>abstract fun [finishSelection](finish-selection.md)()<br>Finish the selection (stop extending it, but keep it active for copying). |
| [moveSelectionDown](move-selection-down.md) | [release]<br>abstract fun [moveSelectionDown](move-selection-down.md)()<br>Move the selection cursor down by one row. |
| [moveSelectionLeft](move-selection-left.md) | [release]<br>abstract fun [moveSelectionLeft](move-selection-left.md)()<br>Move the selection cursor left by one column. |
| [moveSelectionRight](move-selection-right.md) | [release]<br>abstract fun [moveSelectionRight](move-selection-right.md)()<br>Move the selection cursor right by one column. |
| [moveSelectionUp](move-selection-up.md) | [release]<br>abstract fun [moveSelectionUp](move-selection-up.md)()<br>Move the selection cursor up by one row. |
| [selectAll](select-all.md) | [release]<br>abstract fun [selectAll](select-all.md)()<br>Select all retained scrollback and current screen text. |
| [selectAllVisible](select-all-visible.md) | [release]<br>open fun [selectAllVisible](select-all-visible.md)()<br>Select only the rows currently displayed in the terminal viewport. |
| [setSelectionMode](set-selection-mode.md) | [release]<br>abstract fun [setSelectionMode](set-selection-mode.md)(mode: [SelectionMode](../-selection-mode/index.md))<br>Set the selection mode directly. |
| [startSelection](start-selection.md) | [release]<br>abstract fun [startSelection](start-selection.md)(mode: [SelectionMode](../-selection-mode/index.md) = SelectionMode.CHARACTER)<br>Start selection mode at the current cursor position or center of screen. |
| [toggleSelection](toggle-selection.md) | [release]<br>abstract fun [toggleSelection](toggle-selection.md)()<br>Toggle selection mode on/off. If off, turns it on. If on, turns it off. |
| [toggleSelectionMode](toggle-selection-mode.md) | [release]<br>abstract fun [toggleSelectionMode](toggle-selection-mode.md)()<br>Toggle between CHARACTER, WORD, and LINE selection modes. |