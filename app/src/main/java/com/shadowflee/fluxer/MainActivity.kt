package com.shadowflee.fluxer

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Set once from SharedPreferences, mutated only on the main thread.
    private var serverUrl: String = ""

    // AtomicLong prevents overflow (Int.MAX_VALUE ~= 2 billion; Long can safely
    // accumulate a lifetime of notifications without wrapping to negative IDs).
    private val notifIdCounter = AtomicLong(0)

    // ── WebView permission-request queue ──────────────────────────────────────
    // Android shows one system permission dialog at a time.  WebChromeClient can
    // receive a second PermissionRequest while the first dialog is still up.
    // We queue them and process sequentially so none are silently dropped.
    private val pendingWebPermissions = ArrayDeque<PermissionRequest>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Process the head of the queue that triggered this dialog
        pendingWebPermissions.removeFirstOrNull()?.let { req ->
            grantOrDenyWebRequest(req, grants)
        }
        // Trigger the next queued request (if any)
        processNextWebPermission()
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — we check permission at notification time */ }

    companion object {
        const val PREFS_NAME = "fluxer_prefs"
        const val KEY_SERVER_URL = "server_url"
        private const val NOTIF_CHANNEL_ID = "fluxer"
        private const val SETUP_REQUEST = 1001
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
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
        webView.loadUrl(serverUrl)
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
        } catch (_: Exception) {
            false
        }
    }

    // ── Same-origin helper ────────────────────────────────────────────────────

    // Returns the effective port for a URI, substituting scheme defaults.
    private fun effectivePort(uri: Uri): Int {
        val p = uri.port
        if (p != -1) return p
        return when (uri.scheme) {
            "https" -> 443
            "http"  -> 80
            else    -> -1
        }
    }

    private fun isSameOrigin(uri: Uri, reference: Uri): Boolean {
        // Domain names are case-insensitive (RFC 3986 §6.2.2.1) — lowercase before comparing.
        // Also guard against null hosts (e.g. malformed or non-hierarchical URIs).
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
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SETUP_REQUEST) return
        if (resultCode != RESULT_OK) { finish(); return }
        serverUrl = loadServerUrl()
        if (serverUrl.isEmpty()) { finish(); return }
        createNotificationChannel()
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        configureWebView()
        requestNotificationPermissionIfNeeded()
        webView.loadUrl(serverUrl)
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Disallow file:// and content:// access — only remote content is loaded
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
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
            // Validate origin before touching any resource — a cross-origin redirect
            // or iframe must not receive camera/microphone grants.
            val requestUri = Uri.parse(request.origin ?: "")
            val serverUri = Uri.parse(serverUrl)
            if (!isSameOrigin(requestUri, serverUri)) {
                request.deny()
                return
            }

            // Filter to only the resource types we support
            val supportedResources = request.resources.filter { resource ->
                resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }
            if (supportedResources.isEmpty()) {
                request.deny()
                return
            }

            val neededAndroidPerms = supportedResources.flatMap { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        listOf(Manifest.permission.RECORD_AUDIO)
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        listOf(Manifest.permission.CAMERA)
                    else -> emptyList()
                }
            }.filter { perm ->
                ContextCompat.checkSelfPermission(this@MainActivity, perm) !=
                        PackageManager.PERMISSION_GRANTED
            }

            if (neededAndroidPerms.isEmpty()) {
                // Already granted — approve immediately
                request.grant(supportedResources.toTypedArray())
            } else {
                // Queue and process sequentially — only launch the dialog if
                // no other request is already in progress (queue was empty).
                val wasEmpty = pendingWebPermissions.isEmpty()
                pendingWebPermissions.addLast(request)
                if (wasEmpty) permissionLauncher.launch(neededAndroidPerms.toTypedArray())
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            callback.invoke(origin, false, false) // Deny geolocation
        }
    }

    // grants == null means "all system permissions were already held" — grant everything.
    // grants != null means a launcher result came back; filter by what was actually granted.
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
        // Use a loop instead of recursion to avoid stack overflow when many
        // already-granted permission requests are queued back-to-back.
        while (pendingWebPermissions.isNotEmpty()) {
            val next = pendingWebPermissions.first()
            val neededPerms = next.resources.flatMap { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        listOf(Manifest.permission.RECORD_AUDIO)
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        listOf(Manifest.permission.CAMERA)
                    else -> emptyList()
                }
            }.filter { perm ->
                ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
            }
            if (neededPerms.isEmpty()) {
                // All needed permissions already granted — pass null so the helper grants all
                pendingWebPermissions.removeFirst().let { req ->
                    grantOrDenyWebRequest(req, null)
                }
            } else {
                // Need to show a dialog — launcher result will call us again when done
                permissionLauncher.launch(neededPerms.toTypedArray())
                return
            }
        }
    }

    // ── WebViewClient ─────────────────────────────────────────────────────────

    private inner class FluxerWebViewClient : WebViewClient() {

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            // Guard: only inject the bridge flag when we are on the app's own origin.
            // HTTP redirects bypass shouldOverrideUrlLoading, so we must check here
            // to prevent an attacker-controlled redirect from accessing the bridge.
            val loadedUri = Uri.parse(url)
            val serverUri = Uri.parse(serverUrl)
            if (isSameOrigin(loadedUri, serverUri) || url.startsWith("file:///android_asset/")) {
                // Inject the bridge flag for both same-origin app pages and the bundled error
                // page — the error page's "Change Server" button needs FluxerAndroid.openChangeServer().
                view.evaluateJavascript("window.__fluxerAndroid = true;", null)
            } else if (!url.startsWith("about:")) {
                // HTTP redirect landed us outside our origin.
                // Try to open the destination in the system browser, then show the error page.
                val scheme = loadedUri.scheme
                if (scheme == "http" || scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, loadedUri)) } catch (_: Exception) {}
                }
                // Always show the error page regardless of whether the browser launched —
                // the WebView should not remain on a cross-origin page.
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

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url ?: return false
            return handleNavigation(uri)
        }
    }

    // Returns true if the URL was handled externally (WebView should not navigate)
    private fun handleNavigation(uri: Uri): Boolean {
        return try {
            val serverUri = Uri.parse(serverUrl)
            if (isSameOrigin(uri, serverUri)) {
                false // Let WebView handle same-origin navigation
            } else {
                val scheme = uri.scheme
                if (scheme == "http" || scheme == "https" || scheme == "mailto") {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                true // Block WebView from navigating away
            }
        } catch (_: Exception) {
            true // Block anything that fails to parse
        }
    }

    // ── File downloads ────────────────────────────────────────────────────────

    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        // On Android 9 and below, WRITE_EXTERNAL_STORAGE is required at runtime.
        // On Android 10+ the permission is not needed for public Downloads.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Storage permission required to download files.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val parsedUri = Uri.parse(url)
            // Only allow http/https — block blob:, data:, file:, javascript:, etc.
            val scheme = parsedUri.scheme
            if (scheme != "http" && scheme != "https") return

            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .replace(Regex("[/\\\\]"), "_")  // strip any path separators from the name
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
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
            Toast.makeText(this, "Downloading $filename…", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // Do not expose exception details — they may contain internal paths or URLs
            Toast.makeText(this, "Download failed.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "Fluxer", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Messages and alerts from Fluxer" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
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

            // Compute the notification ID first so it can be used as the PendingIntent
            // request code — using 0 for all notifications causes cache collisions on
            // Android 12+ where PendingIntents with the same request code are merged.
            val id = (notifIdCounter.incrementAndGet() and 0x7FFFFFFF).toInt()
            val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this@MainActivity, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this@MainActivity, NOTIF_CHANNEL_ID)
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
                // Permission revoked between the check above and notify()
            }
        }

        @JavascriptInterface
        fun getServerUrl(): String = serverUrl

        @JavascriptInterface
        fun openChangeServer() {
            runOnUiThread { openSetup() }
        }
    }

    // ── Back navigation ───────────────────────────────────────────────────────

    @Deprecated("Compatibility with minSdk 23")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
