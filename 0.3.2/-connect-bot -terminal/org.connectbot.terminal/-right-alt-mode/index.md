//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[RightAltMode](index.md)

# RightAltMode

sealed class [RightAltMode](index.md)

Controls how the right-alt key (AltGr) is interpreted by the terminal keyboard handler.

A future `AUTO` value is reserved for when the Android system exposes the distinction between AltGr and Meta natively, at which point the correct behavior can be inferred automatically from the active keyboard layout.

#### Inheritors

| |
|---|
| [CharacterModifier](-character-modifier/index.md) |
| [Meta](-meta/index.md) |

## Types

| Name | Summary |
|---|---|
| [CharacterModifier](-character-modifier/index.md) | [release]<br>data object [CharacterModifier](-character-modifier/index.md) : [RightAltMode](index.md)<br>Right-alt is passed to [android.view.KeyCharacterMap](https://developer.android.com/reference/kotlin/android/view/KeyCharacterMap.html) as a character-level modifier, allowing international keyboard layouts (e.g. Swiss German `{` via AltGr+APOSTROPHE) to produce the correct characters. This is the default. |
| [Meta](-meta/index.md) | [release]<br>data object [Meta](-meta/index.md) : [RightAltMode](index.md)<br>Right-alt is treated as a terminal Meta modifier (escape prefix), identical to left-alt. Use this for US keyboard users who want right-alt to behave like left-alt. |