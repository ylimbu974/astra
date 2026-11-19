package dev.astra.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * C1-C4 inside the app (Kanban T-28): the PC's own web viewer in a full-screen WebView.
 * "App v1 shortcut" from the wireframes: no streaming rewrite until the V2 native viewer.
 *
 * The phone's paired token goes in the URL fragment, which the WebView never sends to the
 * server; the page reads it and presents it when the WebSocket opens.
 */
class ViewerActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(false) // pinch zoom is handled by the viewer itself
            settings.builtInZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false
            isHapticFeedbackEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(Bridge(), "AstraBridge")
            webViewClient = object : WebViewClient() {
                // Keep navigation inside this PC only.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != Uri.parse(intent.getStringExtra(EXTRA_URL)).host

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        view.loadData(offlinePage(intent.getStringExtra(EXTRA_NAME) ?: "The PC"), "text/html", "utf-8")
                    }
                }
            }
        }
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back during a session = Disconnect (lands on C4); back anywhere else = A2.
                web.evaluateJavascript("(window.astraDisconnect && window.astraDisconnect()) || false") { r ->
                    if (r != "true") finish()
                }
            }
        })

        if (savedInstanceState == null) web.loadUrl(intent.getStringExtra(EXTRA_URL)!!) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.evaluateJavascript("window.astraDisconnect && window.astraDisconnect()", null)
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun backToDevices() = runOnUiThread { finish() }
    }

    private fun offlinePage(name: String) = """
        <html><body style="background:#0d0f13;color:#e8eaee;font-family:sans-serif;display:flex;
        align-items:center;justify-content:center;height:100vh;margin:0;text-align:center;padding:16px">
        <div><h2>Could not reach ${name.replace("<", "&lt;")}</h2>
        <p style="color:#9aa1ad">The PC may be asleep, or its Host Agent stopped.</p>
        <button onclick="AstraBridge.backToDevices()" style="padding:12px 20px;border-radius:10px;border:0;
        background:#4f7cff;color:#fff;font-size:16px">Back to devices</button></div></body></html>
    """.trimIndent()

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"

        fun intent(
            context: Context, baseUrl: String, pcName: String, token: String, phoneName: String,
            mode: String, quality: String,
        ): Intent {
            val enc = { s: String -> Uri.encode(s) }
            val url = "$baseUrl/?app=1&mode=$mode&q=$quality#token=${enc(token)}&name=${enc(phoneName)}"
            return Intent(context, ViewerActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_NAME, pcName)
        }
    }
}
