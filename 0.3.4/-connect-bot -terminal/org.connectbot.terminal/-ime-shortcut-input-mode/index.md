//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[ImeShortcutInputMode](index.md)

# ImeShortcutInputMode

[release]\
enum [ImeShortcutInputMode](index.md) : [Enum](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-enum/index.html)&lt;[ImeShortcutInputMode](index.md)&gt; 

How a full IME editor should request terminal shortcut characters.

## Entries

| | |
|---|---|
| [DISABLED](-d-i-s-a-b-l-e-d/index.md) | [release]<br>[DISABLED](-d-i-s-a-b-l-e-d/index.md)<br>Leave the active IME editor unchanged. |
| [TYPE_NULL](-t-y-p-e_-n-u-l-l/index.md) | [release]<br>[TYPE_NULL](-t-y-p-e_-n-u-l-l/index.md)<br>Temporarily expose a non-rich editor so supporting IMEs generate raw key events. |
| [FORCE_ASCII](-f-o-r-c-e_-a-s-c-i-i/index.md) | [release]<br>[FORCE_ASCII](-f-o-r-c-e_-a-s-c-i-i/index.md)<br>Keep the rich editor but ask the IME to provide Roman alphabet characters. |

## Properties

| Name | Summary |
|---|---|
| [entries](entries.md) | [release]<br>val [entries](entries.md): [EnumEntries](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.enums/-enum-entries/index.html)&lt;[ImeShortcutInputMode](index.md)&gt;<br>Returns a representation of an immutable list of all enum entries, in the order they're declared. |
| [name](../-progress-state/-w-a-r-n-i-n-g/index.md#-372974862%2FProperties%2F-2117867675) | [release]<br>val [name](../-progress-state/-w-a-r-n-i-n-g/index.md#-372974862%2FProperties%2F-2117867675): [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html) |
| [ordinal](../-progress-state/-w-a-r-n-i-n-g/index.md#-739389684%2FProperties%2F-2117867675) | [release]<br>val [ordinal](../-progress-state/-w-a-r-n-i-n-g/index.md#-739389684%2FProperties%2F-2117867675): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |

## Functions

| Name | Summary |
|---|---|
| [valueOf](value-of.md) | [release]<br>fun [valueOf](value-of.md)(value: [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html)): [ImeShortcutInputMode](index.md)<br>Returns the enum constant of this type with the specified name. The string must match exactly an identifier used to declare an enum constant in this type. (Extraneous whitespace characters are not permitted.) |
| [values](values.md) | [release]<br>fun [values](values.md)(): [Array](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-array/index.html)&lt;[ImeShortcutInputMode](index.md)&gt;<br>Returns an array containing the constants of this enum type, in the order they're declared. |