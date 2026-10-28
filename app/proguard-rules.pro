# YouDrop Proguard / R8 Optimization Rules

# youtubedl-android & JNI bindings
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class com.yausername.aria2c.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Apache Commons Compress (used by youtubedl-android when unpacking Python/FFmpeg)
# ExtraFieldUtils registers zip extra-field parsers and instantiates them by reflection.
# Without this, R8 strips their no-arg constructors and app startup crashes in initPython().
-keep class org.apache.commons.compress.archivers.zip.** { *; }

# NewPipeExtractor
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class * implements org.schabi.newpipe.extractor.downloader.Downloader { *; }
-keep interface org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Rhino JavaScript runtime (used by NewPipeExtractor to evaluate YouTube JS)
# The jar also ships desktop debugger/shell tools; Android has no AWT or Swing.
-keep class org.mozilla.javascript.** { *; }
-dontwarn java.beans.**
-dontwarn java.awt.**
-dontwarn javax.swing.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# WebView JavaScript bridge (called by name from injected page scripts)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.vidgrab.app.core.webview.YouTubeJsBridge { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Room SQLite
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
