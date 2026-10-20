package com.quartz.downloader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Resolve shares in the same WebView cookie store as Settings' Facebook sign-in.
 * Website content gets no native bridge and never replaces the trusted share sheet. */
object FacebookSession {
    class LoginRequired : IllegalStateException("Facebook needs you to sign in. Quartz will use your saved session automatically after sign-in.")
    data class Page(val url: String, val userAgent: String, val media: JSONObject? = null)

    fun permalink(raw: String): String? {
        if (!UrlPolicy.isFacebook(raw)) return null
        val uri = Uri.parse(raw)
        if (uri.scheme != "https" || uri.userInfo != null) return null
        val path = uri.path.orEmpty()
        val video = Regex("^/(?:reels?/|(?:[^/]+/)?videos/(?:[^/]+/)?)([0-9]+)(?:/|$)").find(path)
        if (video != null) return "https://www.facebook.com/watch/?v=${video.groupValues[1]}"
        if (path in listOf("/watch", "/watch/", "/video.php", "/video/video.php")) {
            val id = uri.getQueryParameter("v") ?: uri.getQueryParameter("video_id")
            if (id?.matches(Regex("[0-9]+")) == true) return "https://www.facebook.com/watch/?v=$id"
        }
        if (Regex("^/(?:[^/]+/posts/|groups/[^/]+/(?:posts|permalink)/)(?:pfbid[A-Za-z0-9]+|[0-9]+)/?$").matches(path))
            return uri.buildUpon().authority("www.facebook.com").clearQuery().fragment(null).build().toString()
        if (path in listOf("/story.php", "/permalink.php")) {
            val id = uri.getQueryParameter("story_fbid")
            if (id?.matches(Regex("(?:pfbid[A-Za-z0-9]+|[0-9]+)")) == true)
                return uri.buildUpon().authority("www.facebook.com").clearQuery().appendQueryParameter("story_fbid", id)
                    .apply { uri.getQueryParameter("id")?.takeIf { it.matches(Regex("[0-9]+")) }?.let { appendQueryParameter("id", it) } }.fragment(null).build().toString()
        }
        return null
    }

    internal fun choose(page: JSONObject, fallback: String): String =
        listOf(page.optString("canonical"), page.optString("ogUrl"), page.optString("url"), fallback)
            .firstNotNullOfOrNull(::permalink) ?: fallback

    internal fun needsLogin(raw: String): Boolean = UrlPolicy.isFacebook(raw) &&
        Regex("^/(?:login(?:\\.php)?|checkpoint|two_step_verification)(?:/|$)").containsMatchIn(Uri.parse(raw).path.orEmpty())

    internal fun needsLogin(page: JSONObject): Boolean {
        val raw = page.optString("url")
        return needsLogin(raw) || (UrlPolicy.isFacebook(raw) && Uri.parse(raw).path.orEmpty() in listOf("", "/") && page.optBoolean("loginForm"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(context: Context, raw: String, current: () -> Boolean = { true }): Page =
        resolvePage(context, raw, current) { view, url -> view.loadUrl(url) }

    @SuppressLint("SetJavaScriptEnabled")
    internal fun resolvePage(context: Context, raw: String, current: () -> Boolean, load: (WebView, String) -> Unit): Page {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Resolve Facebook off the main thread." }
        require(UrlPolicy.normalize(raw)==raw&&UrlPolicy.isFacebook(raw))
        val script=context.assets.open("web/facebook-media.js").bufferedReader().use{it.readText()}
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var web: WebView? = null
        var result: Page? = null
        var failure: Exception? = null
        var finished = false
        var lastUrl = ""
        var stable = 0
        var loaded = false
        val started = android.os.SystemClock.elapsedRealtime()
        lateinit var probe: Runnable
        fun finish(page: Page? = null, error: Exception? = null) {
            if (finished) return
            finished = true; result = page; failure = error
            done.countDown()
        }
        probe = Runnable {
            val view = web ?: return@Runnable
            if (finished) return@Runnable
            if (!current()) { finish(error = InterruptedException("Preview cancelled")); return@Runnable }
            view.evaluateJavascript(script) { value ->
                if (finished) return@evaluateJavascript
                try {
                    val page = JSONObject(value)
                    val address = page.optString("url")
                    val resolved = choose(page, raw)
                    val media=FacebookMedia.validate(page.optJSONObject("media"),resolved)
                    if(media!=null&&permalink(resolved)!=null){CookieManager.getInstance().flush();finish(Page(media.getString("webpage_url"),view.settings.userAgentString,media));return@evaluateJavascript}
                    if (needsLogin(page)) { finish(error = LoginRequired()); return@evaluateJavascript }
                    stable = if (resolved == lastUrl) stable + 1 else 0; lastUrl = resolved
                    if (loaded && stable >= 1 && permalink(resolved) != null && UrlPolicy.isFacebook(address) && android.os.SystemClock.elapsedRealtime()-started>8000) {
                        CookieManager.getInstance().flush()
                        finish(Page(resolved, view.settings.userAgentString))
                    } else main.postDelayed(probe, 750)
                } catch (_: Exception) { main.postDelayed(probe, 750) }
            }
        }
        main.post {
            try {
                if (!current()) { finish(error = InterruptedException("Preview cancelled")); return@post }
                web = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.isForMainFrame && (request.url.scheme != "https" || !UrlPolicy.isFacebook(request.url.toString()))
                        override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) { loaded = false; stable = 0 }
                        override fun onPageFinished(view: WebView, url: String) { loaded = true }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) finish(error = IllegalStateException("Quartz could not load Facebook. Check your connection and try again."))
                        }
                    }
                    load(this, raw)
                }
                main.postDelayed(probe, 750)
            } catch (error: Exception) { finish(error = error) }
        }
        try {
            check(done.await(35, TimeUnit.SECONDS)) { "Facebook connection timed out. Check your connection and try again." }
            failure?.let { throw it }
            return result ?: error("Facebook did not provide a video page.")
        } finally {
            main.post { finished = true; main.removeCallbacks(probe); web?.apply { stopLoading(); destroy() }; web = null }
        }
    }
}
