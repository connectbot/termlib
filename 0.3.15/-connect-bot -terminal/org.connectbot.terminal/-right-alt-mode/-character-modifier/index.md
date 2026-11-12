//[ConnectBot Terminal](../../../../index.md)/[org.connectbot.terminal](../../index.md)/[RightAltMode](../index.md)/[CharacterModifier](index.md)

# CharacterModifier

[release]\
data object [CharacterModifier](index.md) : [RightAltMode](../index.md)

Right-alt is passed to [android.view.KeyCharacterMap](https://developer.android.com/reference/kotlin/android/view/KeyCharacterMap.html) as a character-level modifier, allowing international keyboard layouts (e.g. Swiss German `{` via AltGr+APOSTROPHE) to produce the correct characters. This is the default.