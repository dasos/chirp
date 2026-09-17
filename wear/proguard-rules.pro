# Chirp Wear OS module release rules.
# Keep Guava (Wearable ListenableFuture) and tiles serialization working.
-dontwarn com.google.common.**
-keep class androidx.wear.tiles.** { *; }

# kotlinx.serialization: this module decodes WearContract payloads sent over the
# Data Layer (WearStateRepository, WearCommandClient), so the generated
# serializers must survive R8 here exactly as they do in :app. Without these the
# watch builds fine and then silently fails to decode any state from the phone.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.chirp.core.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.chirp.core.**$$serializer { *; }
-keep class com.chirp.core.wear.** { *; }
