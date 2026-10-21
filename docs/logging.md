## Logging

ConnectBot Terminal uses SLF4J for logging. In order to receive diagnostic
messages, applications may supply a SLF4J compatible logging provider.

For standard Android Log, two possible options are `slf4j-android` and
`logback-android`. If you use Timber there are a few adapters as well.
