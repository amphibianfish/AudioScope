# Keep Room entities and DAOs
-keep class jp.audioscope.player.data.db.** { *; }
# Keep Media3 internal interactions safe under R8
-dontwarn androidx.media3.**
