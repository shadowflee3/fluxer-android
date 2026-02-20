# Keep JavaScript interface methods so they survive minification
-keepclassmembers class com.shadowflee.fluxer.MainActivity$FluxerBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebView client subclasses intact — method names are invoked reflectively
# by the Android WebKit framework and must not be renamed.
-keep class com.shadowflee.fluxer.MainActivity$FluxerWebViewClient { *; }
-keep class com.shadowflee.fluxer.MainActivity$FluxerChromeClient { *; }

# Keep network callback — methods are invoked by ConnectivityManager
-keepclassmembers class com.shadowflee.fluxer.** extends android.net.ConnectivityManager$NetworkCallback {
    <methods>;
}
