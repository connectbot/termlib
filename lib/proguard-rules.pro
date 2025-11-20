# Add project specific ProGuard rules here.

# Keep all public API classes
-keep public class org.connectbot.terminal.** {
    public *;
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep callbacks interface
-keep interface org.connectbot.terminal.TerminalCallbacks {
    *;
}
