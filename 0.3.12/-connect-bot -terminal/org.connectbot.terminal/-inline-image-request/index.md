//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[InlineImageRequest](index.md)

# InlineImageRequest

[release]\
data class [InlineImageRequest](index.md)(val protocol: [InlineImageProtocolType](../-inline-image-protocol-type/index.md), val action: [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html), val imageId: [Long](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-long/index.html)? = null, val imageNumber: [Long](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-long/index.html)? = null, val name: [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html)? = null, val declaredSizeBytes: [Long](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-long/index.html)? = null, val pixelWidth: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)? = null, val pixelHeight: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)? = null)

Header metadata available before any image payload is decoded.

## Constructors

| | |
|---|---|
| [InlineImageRequest](-inline-image-request.md) | [release]<br>constructor(protocol: [InlineImageProtocolType](../-inline-image-protocol-type/index.md), action: [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html), imageId: [Long](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-long/index.html)? = null, imageNumber: [Long](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-long/index.html)? = null, name: [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html)? = null, declaredSizeBytes: [Long](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-long/index.html)? = null, pixelWidth: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)? = null, pixelHeight: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)? = null) |

## Properties

| Name | Summary |
|---|---|
| [action](action.md) | [release]<br>val [action](action.md): [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html) |
| [declaredSizeBytes](declared-size-bytes.md) | [release]<br>val [declaredSizeBytes](declared-size-bytes.md): [Long](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-long/index.html)? |
| [imageId](image-id.md) | [release]<br>val [imageId](image-id.md): [Long](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-long/index.html)? |
| [imageNumber](image-number.md) | [release]<br>val [imageNumber](image-number.md): [Long](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-long/index.html)? |
| [name](name.md) | [release]<br>val [name](name.md): [String](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-string/index.html)? |
| [pixelHeight](pixel-height.md) | [release]<br>val [pixelHeight](pixel-height.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)? |
| [pixelWidth](pixel-width.md) | [release]<br>val [pixelWidth](pixel-width.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)? |
| [protocol](protocol.md) | [release]<br>val [protocol](protocol.md): [InlineImageProtocolType](../-inline-image-protocol-type/index.md) |