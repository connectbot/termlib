//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[InlineImages](index.md)

# InlineImages

sealed class [InlineImages](index.md)

Controls whether inline-image protocol payloads are accepted.

#### Inheritors

| |
|---|
| [Off](-off/index.md) |
| [On](-on/index.md) |
| [Ask](-ask/index.md) |

## Types

| Name | Summary |
|---|---|
| [Ask](-ask/index.md) | [release]<br>data class [Ask](-ask/index.md)(val limits: [InlineImageLimits](../-inline-image-limits/index.md) = InlineImageLimits(), val confirm: suspend ([InlineImageRequest](../-inline-image-request/index.md)) -&gt; [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)) : [InlineImages](index.md) |
| [Off](-off/index.md) | [release]<br>data object [Off](-off/index.md) : [InlineImages](index.md) |
| [On](-on/index.md) | [release]<br>data class [On](-on/index.md)(val limits: [InlineImageLimits](../-inline-image-limits/index.md) = InlineImageLimits()) : [InlineImages](index.md) |