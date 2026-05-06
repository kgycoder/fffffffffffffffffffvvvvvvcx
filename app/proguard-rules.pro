-keep class com.sync.app.AndroidBridge { *; }
-keepclassmembers class com.sync.app.AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
