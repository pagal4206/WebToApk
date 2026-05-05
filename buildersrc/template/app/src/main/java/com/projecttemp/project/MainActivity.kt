package com.projecttemp.project

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {
    private lateinit var rootView: View
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var loadingIndicator: LinearProgressIndicator
    private lateinit var errorContainer: View
    private lateinit var errorMessageView: TextView

    private var mainFrameLoadFailed = false
    private val homeUrl by lazy { getString(R.string.webapp_url) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        bindViews()
        applyWindowInsets()
        configureSwipeRefresh()
        configureWebView()
        configureBackNavigation()

        findViewById<View>(R.id.retryButton).setOnClickListener {
            loadHomePage()
        }

        if (savedInstanceState == null) {
            loadHomePage()
        } else {
            showWebContent()
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::webView.isInitialized) {
            webView.saveState(outState)
        }
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun bindViews() {
        rootView = findViewById(R.id.root)
        swipeRefreshLayout = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorContainer = findViewById(R.id.errorContainer)
        errorMessageView = findViewById(R.id.errorMessage)
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun configureSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeColors(
            MaterialColors.getColor(swipeRefreshLayout, com.google.android.material.R.attr.colorPrimary)
        )
        swipeRefreshLayout.setOnRefreshListener {
            if (mainFrameLoadFailed) {
                loadHomePage()
            } else {
                webView.reload()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true

            val userAgentSuffix = getString(R.string.webapp_user_agent_suffix).trim()
            if (userAgentSuffix.isNotEmpty()) {
                userAgentString = "$userAgentString $userAgentSuffix".trim()
            }
        }

        webView.setDownloadListener(
            DownloadListener { url, _, _, _, _ ->
                openExternalLink(Uri.parse(url))
            }
        )

        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    loadingIndicator.progress = newProgress.coerceIn(0, 100)
                    loadingIndicator.isVisible = newProgress in 0..99
                }
            }

        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url ?: return false
                    if (!request.isForMainFrame) {
                        return false
                    }

                    return when (url.scheme?.lowercase()) {
                        "http", "https" -> false
                        "intent" -> {
                            handleIntentUrl(url.toString())
                            true
                        }

                        else -> {
                            openExternalLink(url)
                            true
                        }
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    mainFrameLoadFailed = false
                    errorContainer.isVisible = false
                    webView.isVisible = true
                    loadingIndicator.progress = 0
                    loadingIndicator.isVisible = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    swipeRefreshLayout.isRefreshing = false
                    if (!mainFrameLoadFailed) {
                        showWebContent()
                    }
                    loadingIndicator.isVisible = false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        val message = error.description?.toString()?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.web_error_message)
                        showError(message)
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        showError(getString(R.string.web_error_http, errorResponse.statusCode))
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler,
                    error: SslError
                ) {
                    handler.cancel()
                    showError(getString(R.string.web_error_ssl))
                }
            }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    private fun loadHomePage() {
        mainFrameLoadFailed = false
        swipeRefreshLayout.isRefreshing = false
        errorContainer.isVisible = false
        webView.isVisible = true
        loadingIndicator.progress = 0
        loadingIndicator.isVisible = true
        webView.loadUrl(homeUrl)
    }

    private fun showWebContent() {
        errorContainer.isVisible = false
        webView.isVisible = true
    }

    private fun showError(message: String) {
        mainFrameLoadFailed = true
        swipeRefreshLayout.isRefreshing = false
        loadingIndicator.isVisible = false
        webView.isVisible = false
        errorContainer.isVisible = true
        errorMessageView.text = message
    }

    private fun handleIntentUrl(url: String) {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }

            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
            when {
                intent.resolveActivity(packageManager) != null -> startActivity(intent)
                !fallbackUrl.isNullOrBlank() -> webView.loadUrl(fallbackUrl)
                else -> showToast(R.string.external_app_missing)
            }
        } catch (_: Exception) {
            showToast(R.string.external_app_missing)
        }
    }

    private fun openExternalLink(uri: Uri) {
        val intent =
            Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showToast(R.string.external_app_missing)
        }
    }

    private fun showToast(messageResId: Int) {
        Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show()
    }
}
