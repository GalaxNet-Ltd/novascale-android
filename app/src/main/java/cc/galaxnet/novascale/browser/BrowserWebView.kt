// Copyright (C) 2026 NovaScale contributors; SPDX-License-Identifier: Apache-2.0
package cc.galaxnet.novascale.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import cc.galaxnet.novascale.R

@SuppressLint("SetJavaScriptEnabled")
internal fun createBrowserWebView(context: android.content.Context, session: ProxySession, isCurrent: () -> Boolean,
    changed: (WebView, Int?) -> Unit, started: () -> Unit = {},
    finished: (WebView, String) -> Unit = { _, _ -> }, favicon: (Bitmap?) -> Unit = {},
    fullscreen: (android.view.View?, WebChromeClient.CustomViewCallback?) -> Unit = { _, callback -> callback?.onCustomViewHidden() }): WebView = WebView(context).apply {
    clipToOutline = true
    outlineProvider = android.view.ViewOutlineProvider.BOUNDS
    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    clearCache(true)
    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
    settings.allowFileAccess = false; settings.allowContentAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; settings.safeBrowsingEnabled = true
    settings.useWideViewPort = true; settings.loadWithOverviewMode = true
    settings.builtInZoomControls = true; settings.displayZoomControls = false
    var mainFrameFailed = false
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !isCurrent() || runCatching { browserUrl(request.url.toString()) }.isFailure
        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            // Never answer a destination website's authentication challenge with proxy credentials.
            if (isCurrent() && host == "127.0.0.1" && realm == session.realm) handler.proceed(session.username, session.password)
            else handler.cancel()
        }
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { mainFrameFailed = false; started(); changed(view, null) }
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            // SPA navigation changes the address without another page-finished callback.
            if (isCurrent()) changed(view, null)
        }
        override fun onPageFinished(view: WebView, url: String) {
            changed(view, null)
            if (!mainFrameFailed && isCurrent() && runCatching { browserUrl(url) }.isSuccess) finished(view, url)
        }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
            if (request.isForMainFrame) { mainFrameFailed = true; changed(view, R.string.browser_open_failed) }
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) { mainFrameFailed = true; changed(view, R.string.browser_open_failed) }
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { mainFrameFailed = true; handler.cancel(); changed(view, R.string.browser_tls_failed) }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onReceivedIcon(view: WebView, icon: Bitmap) {
            if (isCurrent() && icon.width in 1..512 && icon.height in 1..512) favicon(Bitmap.createScaledBitmap(icon, 32, 32, true))
        }
        override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) { if (isCurrent()) fullscreen(view, callback) else callback.onCustomViewHidden() }
        override fun onHideCustomView() { fullscreen(null, null) }
        override fun onReceivedTitle(view: WebView, title: String) { changed(view, null) }
        override fun onProgressChanged(view: WebView, progress: Int) { if (isCurrent()) changed(view, null) }
        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
    }
}

