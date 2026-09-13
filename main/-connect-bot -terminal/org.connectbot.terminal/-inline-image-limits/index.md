//[ConnectBot Terminal](../../../index.md)/[org.connectbot.terminal](../index.md)/[InlineImageLimits](index.md)

# InlineImageLimits

[release]\
data class [InlineImageLimits](index.md)(val encodedBytes: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 16 * 1024 * 1024, val decodedBytes: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 32 * 1024 * 1024, val transferBytes: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 4 * 1024 * 1024, val uploadBytes: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 16 * 1024 * 1024, val maxDimension: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), val maxPixels: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 16 * 1024 * 1024, val maxImages: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 1024, val maxPlacements: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 4096, val maxFrames: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 4096)

Per-terminal limits. Budgets include retained data and reservations for pending work.

## Constructors

| | |
|---|---|
| [InlineImageLimits](-inline-image-limits.md) | [release]<br>constructor(encodedBytes: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 16 * 1024 * 1024, decodedBytes: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 32 * 1024 * 1024, transferBytes: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 4 * 1024 * 1024, uploadBytes: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 16 * 1024 * 1024, maxDimension: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html), maxPixels: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 16 * 1024 * 1024, maxImages: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 1024, maxPlacements: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 4096, maxFrames: [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) = 4096) |

## Properties

| Name | Summary |
|---|---|
| [decodedBytes](decoded-bytes.md) | [release]<br>val [decodedBytes](decoded-bytes.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |
| [encodedBytes](encoded-bytes.md) | [release]<br>val [encodedBytes](encoded-bytes.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |
| [maxDimension](max-dimension.md) | [release]<br>val [maxDimension](max-dimension.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |
| [maxFrames](max-frames.md) | [release]<br>val [maxFrames](max-frames.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |
| [maxImages](max-images.md) | [release]<br>val [maxImages](max-images.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |
| [maxPixels](max-pixels.md) | [release]<br>val [maxPixels](max-pixels.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |
| [maxPlacements](max-placements.md) | [release]<br>val [maxPlacements](max-placements.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |
| [transferBytes](transfer-bytes.md) | [release]<br>val [transferBytes](transfer-bytes.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html)<br>Upper bound for transfer scratch; the streaming implementation needs only 16 KiB. |
| [uploadBytes](upload-bytes.md) | [release]<br>val [uploadBytes](upload-bytes.md): [Int](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-int/index.html) |