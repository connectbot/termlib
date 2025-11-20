# Consumer proguard rules for ConnectBot Terminal library

# Keep all public API
-keep public class org.connectbot.terminal.** {
    public *;
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
