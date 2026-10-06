# ── Pulse Player release rules ─────────────────────────────────────────────
# Keep the JS bridge entry point: it is called reflectively from the WebView.
-keepclassmembers class com.pulse.player.bridge.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep JSON models used by the bridge protocol (reflection-free, but the field
# names are the wire format, so keep them stable).
-keepclassmembers class com.pulse.player.**.models.** { *; }
-keep class com.pulse.player.media.Track { *; }
-keep class com.pulse.player.ads.AdCreative { *; }
-keep class com.pulse.player.search.TrackSearchResult { *; }

# Media3
-keepclassmembers class androidx.media3.session.MediaSessionService { *; }
-dontwarn androidx.media3.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
