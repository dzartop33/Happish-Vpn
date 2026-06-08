# Happish VPN release rules

# sing-box/libbox generated bindings and Go mobile runtime.
-keep class io.nekohasekai.libbox.** { *; }
-keep interface io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-dontwarn io.nekohasekai.libbox.**
-dontwarn go.**

# We use reflection in SingBoxCoreAdapter for libbox classes, methods and fields.
-keep class ai.arena.happish.core.SingBoxCoreAdapter { *; }
-keep class ai.arena.happish.core.HappishVpnService { *; }
-keep class ai.arena.happish.core.HappishTileService { *; }
-keep class ai.arena.happish.core.HappishWidgetProvider { *; }
-keep class ai.arena.happish.data.CrashReporter { *; }

# ZXing QR scanner integration.
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**

# Android framework entry points referenced from manifest.
-keep class ai.arena.happish.MainActivity { *; }

# JSON reflection is not used, but keep org.json warnings quiet for vendor builds.
-dontwarn org.json.**
