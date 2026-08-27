# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.chirp.core.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.chirp.core.**$$serializer { *; }

# Models referenced reflectively by serialization.
-keep class com.chirp.core.chat.** { *; }
-keep class com.chirp.core.wear.** { *; }

# OkHttp / Okio (standard).
-dontwarn okhttp3.**
-dontwarn okio.**

# Hilt and Room generate code; the plugins add their own rules, these are guards.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Optional annotations referenced by Google Tink; not packaged at runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
