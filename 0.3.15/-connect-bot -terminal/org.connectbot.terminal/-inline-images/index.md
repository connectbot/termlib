//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[InlineImages](index.md)

# InlineImages

sealed class [InlineImages](index.md)

Controls whether inline-image protocol payloads are accepted. File dimensions must be available within the first 32 KiB; malformed or unsupported images are ignored. This header limit does not cap payload size.

#### Inheritors

| |
|---|
| [Off](-off/index.md) |
| [On](-on/index.md) |
| [Ask](-ask/index.md) |

## Types

| Name | Summary |
|---|---|
| [Ask](-ask/index.md) | [release]<br>data class [Ask](-ask/index.md)(val limits: [InlineImageLimits](../-inline-image-limits/index.md) = InlineImageLimits(), val confirm: suspend ([InlineImageRequest](../-inline-image-request/index.md)) -&gt; [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)) : [InlineImages](index.md)<br>Inspect bounded image metadata and reserve layout as data arrives. Pixel decoding and display require approval; denial leaves the reserved space. |
| [Off](-off/index.md) | [release]<br>data object [Off](-off/index.md) : [InlineImages](index.md) |
| [On](-on/index.md) | [release]<br>data class [On](-on/index.md)(val limits: [InlineImageLimits](../-inline-image-limits/index.md) = InlineImageLimits()) : [InlineImages](index.md) |