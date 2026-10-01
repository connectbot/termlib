//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[DelKeyMode](index.md)

# DelKeyMode

sealed class [DelKeyMode](index.md)

Controls what byte sequence the backspace key sends.

#### Inheritors

| |
|---|
| [Delete](-delete/index.md) |
| [Backspace](-backspace/index.md) |

## Types

| Name | Summary |
|---|---|
| [Backspace](-backspace/index.md) | [release]<br>data object [Backspace](-backspace/index.md) : [DelKeyMode](index.md)<br>Backspace key sends ^H (0x08). Use for servers that expect the traditional backspace byte. The Delete key sends DEL (0x7f) instead. |
| [Delete](-delete/index.md) | [release]<br>data object [Delete](-delete/index.md) : [DelKeyMode](index.md)<br>Backspace key sends DEL (0x7f). This is the default. The Delete key sends ESC[3~. |