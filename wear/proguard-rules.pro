# Chirp Wear OS module release rules.
# Keep Guava (Wearable ListenableFuture) and tiles serialization working.
-dontwarn com.google.common.**
-keep class androidx.wear.tiles.** { *; }