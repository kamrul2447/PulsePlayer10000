package com.pulse.player.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.pulse.player.BuildConfig
import com.pulse.player.R
import com.pulse.player.bridge.ActivityBridge
import com.pulse.player.bridge.BridgeEvent
import com.pulse.player.bridge.BridgeProtocol
import com.pulse.player.bridge.CommandDispatcher
import com.pulse.player.bridge.JsBridge
import com.pulse.player.di.ServiceLocator
import com.pulse.player.search.AudiusMusicProvider
import com.pulse.player.search.YouTubeMusicProvider
import com.pulse.player.player.PulsePlaybackService
import com.pulse.player.util.EventBus
import com.pulse.player.util.isNetworkAvailable
import com.pulse.player.util.jsQuote
import com.pulse.player.util.sha1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Hosts the Pulse Player web UI and connects it to the native player.
 *
 * Responsibilities (and nothing else):
 *   • create the WebView and serve the app from APK assets
 *   • expose the JS bridge and pump native events into the page
 *   • connect to [PulsePlaybackService] through a [MediaController]
 *   • permission flow, external links, screen-awake, system bars
 *
 * It deliberately owns **no** playback logic — commands go to
 * [CommandDispatcher], truth comes back from the service.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var rootContainer: FrameLayout
    private lateinit var dispatcher: CommandDispatcher

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var pageReady = false
    private val pendingEvents = ArrayList<BridgeEvent>(32)
    private var lastOnlineState: Boolean? = null
    /* ─────────────────────────── permissions ─────────────────────────── */

    /** Audio is the primary library. Video access is requested only so Pulse can
     * classify clearly music-like MP4 downloads as audio-only songs. */
    private val musicPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val musicVideoPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            emptyArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        publishPermissionState(hasMusicPermission())
        requestMusicVideoPermissionAndScan()
    }

    private val videoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        publishPermissionState(hasMusicPermission())
        lifecycleScope.launch { dispatcher.scanLibrary(full = true, silent = true) }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Only after this dialog closes do we request music access. Android
        // does not reliably handle two permission dialogs launched together.
        maybeRequestMusicPermission()
    }

    private var pendingDeleteUid: String? = null
    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uid = pendingDeleteUid
        pendingDeleteUid = null
        if (result.resultCode == RESULT_OK && uid != null) {
            dispatcher.finalizeDeletedTrack(uid)
        } else if (uid != null) {
            EventBus.emit(
                BridgeProtocol.EVENT_TOAST,
                "message" to "Delete cancelled"
            )
        }
    }

    /* ───────────────────────────── lifecycle ─────────────────────────── */

    private var lastTopDp = 0
    private var lastBottomDp = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the splash theme for the real one before the first frame.
        setTheme(R.style.Theme_Pulse)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        ServiceLocator.init(this)
        ServiceLocator.search(this).use(listOf(AudiusMusicProvider(), YouTubeMusicProvider()))

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0A0A0E"))
        }
        // The WebView lives inside a container that is padded by the system
        // bars (status bar, 3-button / gesture navigation bar, cut-out, IME).
        // The web page therefore never has to guess insets: its viewport is
        // already the safe area, so nothing can end up under system buttons.
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0E"))
            addView(webView)
        }
        setContentView(rootContainer)

        setupWindowInsets()
        configureWebView()

        dispatcher = CommandDispatcher(
            context = this,
            scope = lifecycleScope,
            controllerProvider = { controller },
            library = ServiceLocator.library(this),
            settings = ServiceLocator.settings(this),
            artwork = ServiceLocator.artwork(this),
            scanner = ServiceLocator.scanner(this),
            review = ServiceLocator.review(this),
            search = ServiceLocator.search(this),
            activity = activityBridge
        )

        webView.addJavascriptInterface(
            JsBridge(lifecycleScope, CommandHandlerImpl()),
            NATIVE_OBJECT
        )

        webView.loadUrl(PulseWebViewClient.ENTRY_URL)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EventBus.events.collect { deliver(it) }
            }
        }

        setupBackHandling()
        observeConnectivity()

        publishPermissionState(hasMusicPermission())
        // This single gate handles both the first-launch permission sequence and
        // already-granted installs, avoiding two competing startup scans.
        requestAllPermissionsImmediately()
    }

    override fun onStart() {
        super.onStart()
        connectToService()
    }

    override fun onStop() {
        super.onStop()
        releaseController()
    }

    override fun onResume() {
        super.onResume()
        publishPermissionState(hasMusicPermission())
        // The UI may have missed events while it was in the background.
        lifecycleScope.launch {
            EventBus.emit(BridgeProtocol.EVENT_ONLINE_STATUS, "available" to isNetworkAvailable())
            controller?.let { runCatching { dispatcher.publishSnapshot() } }
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface(NATIVE_OBJECT)
        rootContainer.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    /* ───────────────────────────── WebView ───────────────────────────── */

    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Everything is local: no file or content access for the page.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = false
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
        }

        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        val artwork = ServiceLocator.artwork(this)
        webView.webViewClient = PulseWebViewClient(this, artwork)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 60) markPageReady()
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            // Consumed: the page must see zero env(safe-area-inset-*) because the
            // padding above already removed those areas from its viewport.
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(rootContainer)
    }

    private fun applyInsetsToWeb(topDp: Int, bottomDp: Int) {
        val script = "document.documentElement.style.setProperty('--safe-b','${bottomDp}px');" +
                "document.documentElement.style.setProperty('--safe-t','${topDp}px');"
        runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    private fun markPageReady() {
        if (pageReady) return
        pageReady = true
        applyInsetsToWeb(lastTopDp, lastBottomDp)
        val buffered = pendingEvents.toList()
        pendingEvents.clear()
        buffered.forEach { evaluate(it) }
        lifecycleScope.launch {
            EventBus.emit(BridgeProtocol.EVENT_ONLINE_STATUS, "available" to isNetworkAvailable())
            dispatcher.publishSnapshot()
        }
    }

    /** Pushes one event into the page; buffered until the page can receive it. */
    private fun deliver(event: BridgeEvent) {
        if (!pageReady) {
            if (pendingEvents.size < 64) pendingEvents.add(event)
            return
        }
        evaluate(event)
    }

    private fun evaluate(event: BridgeEvent) {
        val json = event.toJson().toString()
        val script = "try{window.PulseBridge&&window.PulseBridge.onNativeEvent(${jsQuote(json)})}catch(e){}"
        runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    /* ────────────────────── Media3 controller ────────────────────── */

    @OptIn(UnstableApi::class)
    private fun connectToService() {
        if (controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PulsePlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = try {
                future.get()
            } catch (t: Throwable) {
                null
            }
            lifecycleScope.launch { runCatching { dispatcher.publishSnapshot() } }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun releaseController() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    /* ─────────────────────────── permissions ─────────────────────────── */

    private fun hasAudioPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasMusicPermission(): Boolean = hasAudioPermission()

    private fun publishPermissionState(granted: Boolean) {
        val audioPermission = musicPermissions.firstOrNull()
        val state = when {
            granted -> "granted"
            audioPermission != null && shouldShowRequestPermissionRationale(audioPermission) -> "denied"
            else -> "permanently_denied"
        }
        EventBus.emit(BridgeProtocol.EVENT_PERMISSION, "state" to state)
    }

    private fun hasMusicVideoPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else true

    /** Ask every permission Pulse needs on first launch, sequentially. Android
     * will show the dialogs immediately when the corresponding permission is not
     * already granted. There are no local "prompted" flags that can strand an
     * older installation with an empty library. */
    private fun requestAllPermissionsImmediately() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestMusicPermissionThenVideo()
    }

    private fun requestMusicPermissionThenVideo() {
        if (!hasMusicPermission()) {
            permissionLauncher.launch(musicPermissions)
            return
        }
        requestMusicVideoPermissionAndScan()
    }

    private fun requestMusicVideoPermissionAndScan() {
        if (hasMusicVideoPermission() || musicVideoPermissions.isEmpty()) {
            lifecycleScope.launch { dispatcher.scanLibrary(full = true, silent = true) }
            return
        }
        videoPermissionLauncher.launch(musicVideoPermissions)
    }

    private fun maybeRequestMusicPermission() {
        requestMusicPermissionThenVideo()
    }


    private val activityBridge = object : ActivityBridge {
        override fun requestMusicPermission() {
            requestMusicPermissionThenVideo()
        }


        override fun requestDelete(trackUid: String, contentUri: String) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            runCatching {
                val uri = Uri.parse(contentUri)
                val request = android.provider.MediaStore.createDeleteRequest(
                    contentResolver,
                    listOf(uri)
                )
                pendingDeleteUid = trackUid
                deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }.onFailure {
                EventBus.emit(
                    BridgeProtocol.EVENT_ERROR,
                    "code" to "delete",
                    "message" to (it.message ?: "Could not request Android delete confirmation")
                )
            }
        }

        override fun openAppSettings() {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
            runCatching { startActivity(intent) }
        }

        override fun openExternalUrl(url: String) {
            if (!url.startsWith("https://") && !url.startsWith("http://")) return
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }
        }


        override fun openAppSpace() {
            openSnapTubeSpace()
        }

        override fun addAppSpace() {
            val prefs = getSharedPreferences("pulse_app_space", MODE_PRIVATE)
            val existing = prefs.getString("package", null)
            if (!existing.isNullOrBlank() && isPackageInstalled(existing)) {
                EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "App Space already contains one app: SnapTube")
                return
            }
            val packageName = findSnapTubePackage()
            if (packageName == null) {
                EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "SnapTube is not installed")
                return
            }
            prefs.edit().putString("package", packageName).apply()
            EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "SnapTube added to App Space")
        }

        override fun keepScreenAwake(enabled: Boolean) {
            runOnUiThread {
                if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        override fun applyTheme(resolvedTheme: String) {
            runOnUiThread {
                val light = resolvedTheme == "light"
                window.statusBarColor = ContextCompat.getColor(
                    this@MainActivity,
                    if (light) R.color.pulse_light_surface else R.color.pulse_bg
                )
                window.navigationBarColor = ContextCompat.getColor(
                    this@MainActivity,
                    if (light) R.color.pulse_light_surface else R.color.pulse_bg
                )
                rootContainer.setBackgroundColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (light) R.color.pulse_light_surface else R.color.pulse_bg
                    )
                )
                val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = light
                insetsController.isAppearanceLightNavigationBars = light
            }
        }
    }

    private fun openSnapTubeSpace() {
        val prefs = getSharedPreferences("pulse_app_space", MODE_PRIVATE)
        var packageName = prefs.getString("package", null)
        if (packageName.isNullOrBlank() || !isPackageInstalled(packageName)) {
            packageName = findSnapTubePackage()
            if (!packageName.isNullOrBlank()) {
                prefs.edit().putString("package", packageName).apply()
            }
        }

        if (packageName.isNullOrBlank()) {
            EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "SnapTube is not installed")
            return
        }

        runCatching {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "SnapTube cannot be launched")
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }.onFailure {
            EventBus.emit(BridgeProtocol.EVENT_TOAST, "message" to "Could not open SnapTube")
        }
    }

    private fun findSnapTubePackage(): String? {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            packageManager.queryIntentActivities(launcher, PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull { info ->
                    val label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault("")
                    label.contains("snaptube", ignoreCase = true) ||
                        info.activityInfo.packageName.contains("snaptube", ignoreCase = true)
                }
                ?.activityInfo
                ?.packageName
        }.getOrNull()
    }

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        true
    }.getOrDefault(false)

    /* ───────────────────────────── back ───────────────────────────── */

    private fun setupBackHandling() {
        val callback: OnBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){try{return !!(window.PulseBack&&window.PulseBack())}catch(e){return false}})()"
                ) { result ->
                    if (result == null || result == "false" || result == "null") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    /* ───────────────────────── connectivity ───────────────────────── */

    private fun observeConnectivity() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publishOnline(true)
            override fun onLost(network: Network) = publishOnline(false)
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    private fun publishOnline(available: Boolean) {
        if (lastOnlineState == available) return
        lastOnlineState = available
        EventBus.emit(BridgeProtocol.EVENT_ONLINE_STATUS, "available" to available)
    }

    /* ─────────────────────── command handler ─────────────────────── */

    private inner class CommandHandlerImpl : JsBridge.CommandHandler {
        override fun onCommand(cmd: String, payload: JSONObject, requestId: String?) {
            when (cmd) {
                BridgeProtocol.CMD_PLAYER_OPENED, BridgeProtocol.CMD_PLAYER_CLOSED -> Unit
                BridgeProtocol.CMD_READY -> {
                    markPageReady()
                    dispatcher.handle(cmd, payload, requestId)
                }
                else -> dispatcher.handle(cmd, payload, requestId)
            }
        }
    }

    companion object {
        private const val NATIVE_OBJECT = "PulseNative"
    }
}
