package network.nervos.watchvisualiser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import network.nervos.watchvisualiser.bridge.WatchBridge
import network.nervos.watchvisualiser.data.AppPreferences

/**
 * Activity housing the WebGL Three.js visualizer on Wear OS (Pixel Watch 3).
 * Intercepts digital crown rotary encoder events and bridges them to the 3D scene.
 */
class VisualiserActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var preferences: AppPreferences
    private lateinit var watchBridge: WatchBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = AppPreferences(this)
        watchBridge = WatchBridge(this, preferences)

        if (preferences.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        webView = WebView(this).apply {
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF03070D.toInt())
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        setContentView(webView)

        configureWebView()

        val assetPath = intent.getStringExtra(EXTRA_ASSET_PATH)
            ?: "file:///android_asset/visualisers/ckb-cell-lab/index.html"

        webView.loadUrl(assetPath)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadsImagesAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        // Bridge native watch capabilities to Three.js environment
        webView.addJavascriptInterface(watchBridge, "WatchBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }
    }

    /**
     * Intercept Pixel Watch 3 Digital Crown / Rotary Encoder inputs
     * and transmit them to the 3D Three.js camera controller.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val axisValue = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
            val delta = axisValue * preferences.rotarySensitivity

            // Subtle haptic tick on rotary step
            watchBridge.vibrate("tick")

            // Dispatch custom event to WebGL visualizer
            val js = "window.dispatchEvent(new CustomEvent('wearRotary', { detail: { delta: $delta } }));"
            webView.evaluateJavascript(js, null)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ASSET_PATH = "extra_asset_path"
        const val EXTRA_VISUALISER_TITLE = "extra_visualiser_title"
    }
}
