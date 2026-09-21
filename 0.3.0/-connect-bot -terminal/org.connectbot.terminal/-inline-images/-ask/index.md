//[ConnectBot Terminal](../../../../index.md)/[org.connectbot.terminal](../../index.md)/[InlineImages](../index.md)/[Ask](index.md)

# Ask

[release]\
data class [Ask](index.md)(val limits: [InlineImageLimits](../../-inline-image-limits/index.md) = InlineImageLimits(), val confirm: suspend ([InlineImageRequest](../../-inline-image-request/index.md)) -&gt; [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)) : [InlineImages](../index.md)

## Constructors

| | |
|---|---|
| [Ask](-ask.md) | [release]<br>constructor(limits: [InlineImageLimits](../../-inline-image-limits/index.md) = InlineImageLimits(), confirm: suspend ([InlineImageRequest](../../-inline-image-request/index.md)) -&gt; [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html)) |

## Properties

| Name | Summary |
|---|---|
| [confirm](confirm.md) | [release]<br>val [confirm](confirm.md): suspend ([InlineImageRequest](../../-inline-image-request/index.md)) -&gt; [Boolean](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-boolean/index.html) |
| [limits](limits.md) | [release]<br>val [limits](limits.md): [InlineImageLimits](../../-inline-image-limits/index.md) |