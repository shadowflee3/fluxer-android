# Keep JavaScript interface methods so they survive minification
-keepclassmembers class com.shadowflee.fluxer.MainActivity$FluxerBridge {
    @android.webkit.JavascriptInterface <methods>;
}
