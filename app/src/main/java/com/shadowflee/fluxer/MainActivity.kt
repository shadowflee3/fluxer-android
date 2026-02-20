package com.shadowflee.fluxer

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Set once from SharedPreferences, mutated only on the main thread.
    private var serverUrl: String = ""

    // ── WebView permission-request queue ──────────────────────────────────────
    // Android shows one system permission dialog at a time. WebChromeClient can
    // receive a second PermissionRequest while the first dialog is still up.
    // We queue them and process sequentially so none are silently dropped.
    private val pendingWebPermissions = ArrayDeque<PermissionRequest>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        pendingWebPermissions.removeFirstOrNull()?.let { req ->
            grantOrDenyWebRequest(req, grants)
        }
        processNextWebPermission()
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — we check permission at notification time */ }

    // ── File upload chooser ───────────────────────────────────────────────────
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileChooserCallback ?: return@registerForActivityResult
        fileChooserCallback = null
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris: Array<Uri>? = when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            cb.onReceiveValue(uris)
        } else {
            cb.onReceiveValue(null)
        }
    }

    // ── Network monitoring ────────────────────────────────────────────────────
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var wasOffline = false

    // ── PiP / call state ──────────────────────────────────────────────────────
    // "audio" or "video" while a call is active, null otherwise.
    // Drives FLAG_KEEP_SCREEN_ON and automatic PiP entry on home press.
    private var activeCallType: String? = null

    // ── Pending share text ────────────────────────────────────────────────────
    // Text shared from another app; injected into the web page after load.
    private var pendingShareText: String? = null

    companion object {
        const val PREFS_NAME       = "fluxer_prefs"
        const val KEY_SERVER_URL   = "server_url"
        private const val NOTIF_CHANNEL_ID_DEFAULT = "fluxer"
        private const val KEY_NOTIF_SOUND_URI      = "notif_sound_uri"
        private const val SETUP_REQUEST            = 1001
        private const val RINGTONE_REQUEST         = 1002
        private const val LOG_TAG                  = "FluxerWebView"

        // AtomicLong in companion object so it survives Activity recreation
        // (config changes, back-stack restore) without resetting to 0.
        private val notifIdCounter = AtomicLong(0)
    }

    // Active notification channel ID — @Volatile because showNotification() runs
    // on the JavascriptInterface thread while createNotificationChannel() runs on main.
    @Volatile
    private var activeNotifChannelId: String = NOTIF_CHANNEL_ID_DEFAULT

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen must be installed before super.onCreate() so the compat
        // library can intercept the window and display the branded launch screen.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        serverUrl = loadServerUrl()
        if (serverUrl.isEmpty()) {
            openSetup()
            return
        }

        createNotificationChannel()
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        configureWebView()
        requestNotificationPermissionIfNeeded()
        registerNetworkCallback()

        if (savedInstanceState != null) {
            // Restore WebView scroll position and back-stack after config change (rotation, etc.)
            webView.restoreState(savedInstanceState)
        } else {
            val handled = handleIncomingIntent(intent)
            if (!handled) webView.loadUrl(serverUrl)
        }

        // Modern back navigation (replaces deprecated onBackPressed).
        // Predictive back gesture on Android 13+ gets smooth preview animation.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Activity is singleTop: re-delivered when already on top of the stack
        // (deep link tap, share from another app while Fluxer is visible).
        if (::webView.isInitialized) handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persist WebView back-stack and scroll position across config changes.
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        // webView.onPause() is intentionally NOT called — it would fire a
        // visibilitychange → hidden event and suspend JavaScript timers, both
        // of which terminate active voice/video calls.
        // webView.onResume() IS called so the WebView's internal media capture
        // stack returns to the active state when the app comes to the foreground.
        // Without this, getUserMedia() (called on mic unmute) can fail silently
        // after the Activity has been through a pause/resume cycle.
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        // Flush session cookies to disk so they survive process death.
        CookieManager.getInstance().flush()
        // webView.onPause() intentionally NOT called — see onResume() above.
    }

    override fun onStart() {
        super.onStart()
        // App is visible again — dismiss the background notification.
        stopService(Intent(this, FluxerForegroundService::class.java))
    }

    override fun onStop() {
        super.onStop()
        // App fully backgrounded — start the foreground service so Android
        // keeps this process alive and the user's call continues uninterrupted.
        // Note: onStop() is NOT called when entering PiP (the activity stays
        // visible), so the service is not started unnecessarily during PiP.
        if (::webView.isInitialized) {
            val svc = Intent(this, FluxerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // On severe memory pressure clear the in-memory WebView caches
        // (keeps disk cache so the page can reload from cache after GC).
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && ::webView.isInitialized) {
            webView.clearCache(false)
        }
    }

    // ── Picture-in-Picture ────────────────────────────────────────────────────

    /**
     * Called when the user presses Home (or switches apps) while a call is active.
     * We automatically enter PiP so the call stays visible in a small overlay.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activeCallType != null) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            try { enterPictureInPictureMode(params) } catch (_: Exception) {
                // PiP not supported on this device/configuration — silently ignore
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Notify the web app so it can hide nav/toolbars in PiP and restore them on exit.
        if (::webView.isInitialized) {
            val flag = if (isInPictureInPictureMode) "true" else "false"
            webView.evaluateJavascript(
                "if (typeof window.__fluxerOnPipChanged === 'function') " +
                "window.__fluxerOnPipChanged($flag);",
                null
            )
        }
    }

    // ── Deep links & share intents ────────────────────────────────────────────

    /**
     * Inspects the incoming Intent and acts on deep links / share content.
     * Returns true if the intent changed what's loaded in the WebView.
     */
    private fun handleIncomingIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return false
                if (uri.scheme == "fluxer") {
                    // Convert fluxer://channel/123 → serverUrl/#/channel/123
                    val fragment = (uri.encodedPath ?: "").trimStart('/')
                    webView.loadUrl("$serverUrl/#/$fragment")
                    true
                } else false
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
                // Load the app (in case it's not loaded yet) then inject after page finishes.
                pendingShareText = text
                webView.loadUrl(serverUrl)
                true
            }
            else -> false
        }
    }

    // ── Network monitoring ────────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (wasOffline) {
                    wasOffline = false
                    runOnUiThread {
                        if (::webView.isInitialized) {
                            val url = webView.url ?: ""
                            // Auto-reload if we're sitting on the error page
                            if (url.contains("error.html") || url.isEmpty()) {
                                webView.loadUrl(serverUrl)
                            }
                        }
                    }
                }
            }
            override fun onLost(network: Network) {
                wasOffline = true
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity, "Network connection lost", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        cm.registerNetworkCallback(req, cb)
        connectivityCallback = cb
    }

    private fun unregisterNetworkCallback() {
        val cb = connectivityCallback ?: return
        connectivityCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    // ── Server URL persistence ────────────────────────────────────────────────

    private fun loadServerUrl(): String {
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, "") ?: ""
        return if (isValidServerUrl(raw)) raw else ""
    }

    private fun isValidServerUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val uri = Uri.parse(url)
            uri.scheme == "http" || uri.scheme == "https"
        } catch (_: Exception) { false }
    }

    // ── Same-origin helper ────────────────────────────────────────────────────

    private fun effectivePort(uri: Uri): Int {
        val p = uri.port
        if (p != -1) return p
        return when (uri.scheme) { "https" -> 443; "http" -> 80; else -> -1 }
    }

    private fun isSameOrigin(uri: Uri, reference: Uri): Boolean {
        val uriHost = uri.host?.lowercase() ?: return false
        val refHost = reference.host?.lowercase() ?: return false
        return uri.scheme?.lowercase() == reference.scheme?.lowercase() &&
               uriHost == refHost &&
               effectivePort(uri) == effectivePort(reference)
    }

    // ── Setup activity ────────────────────────────────────────────────────────

    private fun openSetup() {
        startActivityForResult(Intent(this, SetupActivity::class.java), SETUP_REQUEST)
    }

    @Deprecated("Using onActivityResult for minSdk 23 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            SETUP_REQUEST -> {
                if (resultCode != RESULT_OK) { finish(); return }
                serverUrl = loadServerUrl()
                if (serverUrl.isEmpty()) { finish(); return }
                createNotificationChannel()
                setContentView(R.layout.activity_main)
                webView = findViewById(R.id.webView)
                configureWebView()
                requestNotificationPermissionIfNeeded()
                unregisterNetworkCallback()
                registerNetworkCallback()
                webView.loadUrl(serverUrl)
            }
            RINGTONE_REQUEST -> {
                if (resultCode != RESULT_OK) return
                val pickedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
                saveNotificationSoundUri(pickedUri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val oldId = activeNotifChannelId
                    createNotificationChannel()
                    if (oldId != activeNotifChannelId) nm.deleteNotificationChannel(oldId)
                }
                val label = if (pickedUri == null) "Silent" else {
                    val ringtone = RingtoneManager.getRingtone(this, pickedUri)
                    try { ringtone?.getTitle(this) ?: "Custom" } finally { ringtone?.stop() }
                }
                Toast.makeText(this, "Notification sound: $label", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    private fun configureWebView() {
        // Enable remote debugging in debug builds so devs can inspect with chrome://inspect
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Disallow local file / content access — only remote content is loaded
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Respect system text size preference (accessibility)
            textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(50, 200)
            // Safe Browsing warns users about phishing/malware URLs (Android 8.1+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                safeBrowsingEnabled = true
            }
        }

        webView.addJavascriptInterface(FluxerBridge(), "FluxerAndroid")
        webView.webChromeClient = FluxerChromeClient()
        webView.webViewClient = FluxerWebViewClient()

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    // ── WebChromeClient ───────────────────────────────────────────────────────

    private inner class FluxerChromeClient : WebChromeClient() {

        override fun onPermissionRequest(request: PermissionRequest) {
            val requestUri = request.origin ?: Uri.EMPTY
            val serverUri  = Uri.parse(serverUrl)
            if (!isSameOrigin(requestUri, serverUri)) { request.deny(); return }

            val supportedResources = request.resources.filter { resource ->
                resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }
            if (supportedResources.isEmpty()) { request.deny(); return }

            val neededAndroidPerms = supportedResources.flatMap { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
                    else -> emptyList()
                }
            }.filter { perm ->
                ContextCompat.checkSelfPermission(this@MainActivity, perm) !=
                    PackageManager.PERMISSION_GRANTED
            }

            if (neededAndroidPerms.isEmpty()) {
                request.grant(supportedResources.toTypedArray())
            } else {
                val wasEmpty = pendingWebPermissions.isEmpty()
                pendingWebPermissions.addLast(request)
                if (wasEmpty) permissionLauncher.launch(neededAndroidPerms.toTypedArray())
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            callback.invoke(origin, false, false)
        }

        // ── File upload (camera, gallery, documents) ──────────────────────────
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            // Cancel any stale callback that never got a result (safety)
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback

            val acceptTypes = fileChooserParams.acceptTypes
                .filter { it.isNotBlank() }
                .joinToString(",")
                .ifBlank { "*/*" }

            // Use ACTION_GET_CONTENT for broad compatibility across all launchers
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // If multiple types, collapse to */* — the OS file picker filters by extension
                type = if (acceptTypes.contains(",")) "*/*" else acceptTypes
                if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
            return try {
                fileChooserLauncher.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                fileChooserCallback = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }

        // ── JS console → Logcat (debug builds only) ───────────────────────────
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) {
                val text = "${message.message()} [${message.sourceId()}:${message.lineNumber()}]"
                when (message.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR   -> Log.e(LOG_TAG, text)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(LOG_TAG, text)
                    else                               -> Log.d(LOG_TAG, text)
                }
            }
            return true
        }
    }

    private fun grantOrDenyWebRequest(req: PermissionRequest, grants: Map<String, Boolean>?) {
        val grantableResources = req.resources.filter { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    grants == null || grants[Manifest.permission.RECORD_AUDIO] == true
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    grants == null || grants[Manifest.permission.CAMERA] == true
                else -> false
            }
        }.toTypedArray()
        if (grantableResources.isNotEmpty()) req.grant(grantableResources) else req.deny()
    }

    private fun processNextWebPermission() {
        while (pendingWebPermissions.isNotEmpty()) {
            val next = pendingWebPermissions.first()
            val neededPerms = next.resources.flatMap { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
                    else -> emptyList()
                }
            }.filter { perm ->
                ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
            }
            if (neededPerms.isEmpty()) {
                pendingWebPermissions.removeFirst().let { req -> grantOrDenyWebRequest(req, null) }
            } else {
                permissionLauncher.launch(neededPerms.toTypedArray())
                return
            }
        }
    }

    // ── WebViewClient ─────────────────────────────────────────────────────────

    private inner class FluxerWebViewClient : WebViewClient() {

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            val loadedUri = Uri.parse(url)
            val serverUri = Uri.parse(serverUrl)
            if (isSameOrigin(loadedUri, serverUri) || url.startsWith("file:///android_asset/")) {
                view.evaluateJavascript("window.__fluxerAndroid = true;", null)

                // Inject any text that was shared from another app while the page was loading
                pendingShareText?.let { text ->
                    pendingShareText = null
                    // JSON-encode to safely handle quotes, newlines, backslashes
                    val escaped = text
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "")
                        .take(4096) // sanity cap
                    view.evaluateJavascript(
                        "if (typeof window.__fluxerReceiveShare === 'function')" +
                        "{ window.__fluxerReceiveShare('$escaped'); }",
                        null
                    )
                }
            } else if (!url.startsWith("about:")) {
                val scheme = loadedUri.scheme
                if (scheme == "http" || scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, loadedUri)) } catch (_: Exception) {}
                }
                view.loadUrl("file:///android_asset/error.html")
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (!request.isForMainFrame) return
            view.loadUrl("file:///android_asset/error.html")
        }

        /**
         * Handle SSL errors — for self-hosted servers that use self-signed certificates,
         * offer the user a one-time "Continue anyway?" prompt.
         * For any other host the error is silently cancelled (secure-by-default).
         */
        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            val serverHost = Uri.parse(serverUrl).host ?: run { handler.cancel(); return }
            val errorUrl   = error.url ?: ""
            // Proceed only when the error is on the configured server's host
            if (errorUrl.contains(serverHost)) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Untrusted Certificate")
                    .setMessage(
                        "The server \"$serverHost\" has an untrusted SSL certificate.\n\n" +
                        "This is normal for self-signed certificates on private servers. Continue?"
                    )
                    .setPositiveButton("Continue") { _, _ -> handler.proceed() }
                    .setNegativeButton("Cancel")   { _, _ -> handler.cancel() }
                    .show()
            } else {
                handler.cancel()
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url ?: return false
            return handleNavigation(uri)
        }
    }

    private fun handleNavigation(uri: Uri): Boolean {
        return try {
            val serverUri = Uri.parse(serverUrl)
            if (isSameOrigin(uri, serverUri)) {
                false
            } else {
                val scheme = uri.scheme
                if (scheme == "http" || scheme == "https" || scheme == "mailto") {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                true
            }
        } catch (_: Exception) { true }
    }

    // ── File downloads ────────────────────────────────────────────────────────

    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Storage permission required to download files.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val parsedUri = Uri.parse(url)
            val scheme = parsedUri.scheme
            if (scheme != "http" && scheme != "https") return

            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .replace(Regex("[/\\\\]"), "_")
                .take(255)
                .ifBlank { "download" }
            val safeMime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
                ?: "application/octet-stream"

            val req = DownloadManager.Request(parsedUri).apply {
                setMimeType(safeMime)
                addRequestHeader("User-Agent", userAgent)
                setTitle(filename)
                setDescription("Downloading…")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, "Downloading $filename…", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Download failed.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun loadNotificationSoundUri(): Uri? {
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NOTIF_SOUND_URI, null) ?: return null
        return try { Uri.parse(raw) } catch (_: Exception) { null }
    }

    private fun saveNotificationSoundUri(uri: Uri?) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_NOTIF_SOUND_URI, uri?.toString())
            .apply()
    }

    private fun openRingtonePicker() {
        val currentUri = loadNotificationSoundUri()
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Notification Sound")
            if (currentUri != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, RINGTONE_REQUEST)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val soundUri = loadNotificationSoundUri()
            val newChannelId = if (soundUri != null) {
                "fluxer_${soundUri.toString().hashCode().and(0x7FFFFFFF)}"
            } else {
                NOTIF_CHANNEL_ID_DEFAULT
            }
            if (nm.getNotificationChannel(newChannelId) != null) {
                activeNotifChannelId = newChannelId
                return
            }
            val channel = NotificationChannel(
                newChannelId, "Fluxer", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Messages and alerts from Fluxer"
                if (soundUri != null) {
                    val audioAttrs = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                    setSound(soundUri, audioAttrs)
                }
            }
            nm.createNotificationChannel(channel)
            activeNotifChannelId = newChannelId
        } else {
            activeNotifChannelId = NOTIF_CHANNEL_ID_DEFAULT
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ── JavaScript bridge ─────────────────────────────────────────────────────

    inner class FluxerBridge {

        @JavascriptInterface
        fun showNotification(title: String, body: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return

            val safeTitle = title.take(256)
            val safeBody  = body.take(1024)
            val id = (notifIdCounter.incrementAndGet() and 0x7FFFFFFF).toInt()
            val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this@MainActivity, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this@MainActivity, activeNotifChannelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(safeTitle)
                .setContentText(safeBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(this@MainActivity).notify(id, notif)
            } catch (_: SecurityException) {
                // Permission revoked between check and notify()
            }
        }

        @JavascriptInterface
        fun getServerUrl(): String = serverUrl

        @JavascriptInterface
        fun openChangeServer() { runOnUiThread { openSetup() } }

        /**
         * Opens system notification channel settings so the user can change
         * sound/vibration/importance directly. Falls back to ringtone picker on < API 26.
         */
        @JavascriptInterface
        fun openNotificationSettings() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        putExtra(Settings.EXTRA_CHANNEL_ID, activeNotifChannelId)
                    }
                    try { startActivity(intent) } catch (_: ActivityNotFoundException) {
                        openRingtonePicker()
                    }
                } else {
                    openRingtonePicker()
                }
            }
        }

        /** Opens the ringtone picker so the user can set a custom notification sound. */
        @JavascriptInterface
        fun openCustomSoundPicker() { runOnUiThread { openRingtonePicker() } }

        /**
         * Called by the web app when the user joins a call.
         * @param type "audio" or "video"
         *
         * Effect: keeps the screen on and enables automatic PiP on home-press.
         */
        @JavascriptInterface
        fun startCall(type: String) {
            runOnUiThread {
                activeCallType = if (type == "video" || type == "audio") type else "audio"
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Called by the web app when the user leaves a call.
         * Clears FLAG_KEEP_SCREEN_ON and disables automatic PiP.
         */
        @JavascriptInterface
        fun endCall() {
            runOnUiThread {
                activeCallType = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Returns "audio", "video", or "" (empty string = not in a call).
         * The web app can call this after a page reload to restore call UI state.
         */
        @JavascriptInterface
        fun getCallState(): String = activeCallType ?: ""
    }
}
