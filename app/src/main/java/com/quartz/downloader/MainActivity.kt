package com.quartz.downloader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.FrameLayout
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

open class MainActivity : Activity() {
    protected open val shareOnly = false
    private lateinit var web: WebView
    private val worker = Executors.newFixedThreadPool(2)
    @Volatile private var pageReady = false
    @Volatile private var pendingLink = ""
    @Volatile private var previewUrl = ""
    @Volatile private var previewResolvedUrl = ""
    @Volatile private var previewFacebookMedia: JSONObject? = null
    @Volatile private var previewYoutubeMedia: JSONObject? = null
    @Volatile private var previewProcessId = ""
    @Volatile private var inspectingUrl = ""
    @Volatile private var inspectingGeneration = -1L
    @Volatile private var previewHeights = emptySet<Int>()
    @Volatile private var previewTitle = ""
    @Volatile private var previewThumbnail = ""
    @Volatile private var previewDuration = 0
    @Volatile private var previewAudioAvailable = true
    @Volatile private var previewVideoAvailable = true
    @Volatile private var previewRoute = ExtractorRoutes.DEFAULT
    @Volatile private var previewTikTokChoices = emptyMap<String, String>()
    private val previewGeneration = AtomicLong()
    private val waitingJobs = linkedSetOf<String>()
    private var notificationRequest = false
    private var pendingDownloadFocus = ""
    private var pendingTab = ""
    private var facebookSignInLink = ""
    private var lastFacebookLink = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadNotifications.channels(this)
        if (!shareOnly) PlaylistStore.recover(this)
        if (!DownloadService.active) {
            DownloadStore.recoverInterrupted(this)
            BrowserCookies.cleanInactive(this)
            // Keep partial downloads for resume.
        }
        window.statusBarColor = android.graphics.Color.rgb(16, 14, 23)
        window.navigationBarColor = android.graphics.Color.rgb(16, 14, 23)
        web = WebView(this)
        if (shareOnly) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowFileAccessFromFileURLs = false
        web.settings.allowUniversalAccessFromFileURLs = false
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("file:///android_asset/web/")) return false
                if (request.isForMainFrame && url.startsWith("https://")) openBrowser(url)
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url != "file:///android_asset/web/index.html") return
                pageReady = true
                if (pendingLink.isNotBlank()) { send("sharedLink", JSONObject().put("url", pendingLink).put("popup", true)); pendingLink = "" }
                if (pendingDownloadFocus.isNotBlank()) { send("focusDownload", JSONObject().put("id", pendingDownloadFocus)); pendingDownloadFocus = "" }
                if(pendingTab.isNotBlank()){send("navigate",JSONObject().put("tab",pendingTab));pendingTab=""}
            }
        }
        web.addJavascriptInterface(Bridge(), "QuartzNative")
        val root = FrameLayout(this).apply {
            setBackgroundColor(if (shareOnly) android.graphics.Color.TRANSPARENT else android.graphics.Color.rgb(16, 14, 23))
            addView(web, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        ScreenInsets.apply(this, root)
        handleIntent(intent)
        web.loadUrl("file:///android_asset/web/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("quartz_tab")?.let { pendingTab=it;intent.removeExtra("quartz_tab");if(pageReady){send("navigate",JSONObject().put("tab",pendingTab));pendingTab=""} }
        if (intent?.data?.scheme == "quartz" && intent.data?.host == "downloads") {
            pendingDownloadFocus = intent.data?.lastPathSegment.orEmpty()
            if (pageReady) { send("focusDownload", JSONObject().put("id", pendingDownloadFocus)); pendingDownloadFocus = "" }
        }
        intent?.getStringExtra("quartz_open_id")?.let { Bridge().open(it); intent.removeExtra("quartz_open_id") }
        intent?.getStringExtra("quartz_retry_id")?.let { retryJob(it); intent.removeExtra("quartz_retry_id") }
        val raw = intent?.getStringExtra("quartz_url")
            ?: if (intent?.action == Intent.ACTION_SEND) {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            } else null
        if (raw.isNullOrBlank()) return
        val found = Regex("https://[^\\s<>]+", RegexOption.IGNORE_CASE).find(raw)?.value?.trimEnd('.', ',', ')') ?: raw
        pendingLink = found
        // Start shared YouTube discovery while the options screen is loading.
        if (!pageReady && UrlPolicy.isYoutube(found)) Bridge().inspect(found)
        if (pageReady) { send("sharedLink", JSONObject().put("url", found).put("popup", true)); pendingLink = "" }
    }

    private fun send(event: String, data: JSONObject) {
        runOnUiThread {
            if (pageReady) web.evaluateJavascript("window.Quartz.receive(${JSONObject.quote(event)}, ${data});", null)
        }
    }

    private fun error(message: String, signIn: String? = null) = send("error", JSONObject().put("message", message).put("signIn", signIn))

    private fun openBrowser(raw: String) {
        val url = try { UrlPolicy.normalize(raw) } catch (_: Exception) { "https://www.youtube.com" }
        startActivity(Intent(this, BrowserActivity::class.java).putExtra("url", url))
    }

    inner class Bridge {
        @JavascriptInterface fun playerState(): String = PlaybackService.state().toString()
        @JavascriptInterface fun state(): String {
            val preferences = getSharedPreferences("quartz_ui", MODE_PRIVATE)
            return JSONObject()
                .put("jobs", DownloadStore.all(this@MainActivity))
                .put("theme", preferences.getString("theme", "dark"))
                .put("defaultMode", preferences.getString("defaultMode", "video"))
                .put("askQuality", preferences.getBoolean("askQuality", false))
                .put("parallelDownloads", preferences.getInt("parallelDownloads", 2).coerceIn(1, 3))
                .put("spotifyAudioFormat", preferences.getString("spotifyAudioFormat", "original"))
                .put("videoAudioFormat", preferences.getString("videoAudioFormat", "original"))
                .put("sessions", BrowserCookies.status())
                .put("version", packageManager.getPackageInfo(packageName, 0).versionName)
                .put("spotify", PlaylistStore.get(this@MainActivity))
                .put("playlists", PlaylistStore.summaries(this@MainActivity))
                .put("shareOnly", shareOnly)
                .put("notifications", DownloadNotifications.status(this@MainActivity))
                .put("player",PlaybackService.state())
                .put("updates",AppUpdates.state(this@MainActivity))
                .toString()
        }

        @JavascriptInterface fun updateAction(action: String) {
            try {
                when(action){
                    "check" -> AppUpdates.check(this@MainActivity,true)
                    "download" -> AppUpdates.download(this@MainActivity)
                    "cancel" -> AppUpdates.cancel(this@MainActivity)
                    "install" -> runOnUiThread { try{AppUpdates.install(this@MainActivity)}catch(_:Exception){Toast.makeText(this@MainActivity,"Android could not open the installer. Try again.",Toast.LENGTH_LONG).show()} }
                    "releases" -> runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(AppUpdates.RELEASES))) }
                }
            }catch(_:Exception){runOnUiThread{Toast.makeText(this@MainActivity,"Could not start the update action. Try again.",Toast.LENGTH_LONG).show()}}
        }
        @JavascriptInterface fun updateLater(version: String) = AppUpdates.dismiss(this@MainActivity,version)

        @JavascriptInterface fun inspect(raw: String) {
            val url = try { UrlPolicy.normalize(raw) } catch (e: Exception) { error(e.message ?: "Paste a complete link."); return }
            if (UrlPolicy.isSpotify(url)) { previewGeneration.incrementAndGet(); previewUrl = ""; loadPlaylist(url); return }
            if (inspectingUrl == url && inspectingGeneration == previewGeneration.get()) return
            inspectingUrl = url
            lastFacebookLink = url.takeIf { UrlPolicy.isFacebook(it) }.orEmpty()
            val generation = previewGeneration.incrementAndGet()
            inspectingGeneration = generation
            previewProcessId.takeIf { it.isNotBlank() }?.let { YoutubeDL.destroyProcessById(it) }
            val processId = "preview-${UUID.randomUUID()}"; previewProcessId = processId
            previewUrl = ""
            previewFacebookMedia = null
            previewYoutubeMedia = null
            send("previewLoading", JSONObject().put("url", url).put("message", if (UrlPolicy.isFacebook(url)) "Opening Facebook with your saved session…" else "Finding available video formats…"))
            worker.execute {
                var cookies: File? = null
                try {
                    val facebook = if (UrlPolicy.isFacebook(url)) FacebookSession.resolve(this@MainActivity, url) { generation == previewGeneration.get() } else null
                    val resolvedUrl = facebook?.url ?: url
                    if (generation != previewGeneration.get()) return@execute
                    MediaEngine.prepare(this@MainActivity)
                    cookies = BrowserCookies.create(this@MainActivity, resolvedUrl)
                    val inspection = facebook?.media?.let { MediaInspection(FacebookMedia.videoInfo(it), FacebookMedia.ROUTE, it) }
                        ?: ExtractorRoutes.inspect(resolvedUrl, cookies, facebook?.userAgent.orEmpty(), processId,
                            { generation == previewGeneration.get() }) { message ->
                            if (generation == previewGeneration.get()) send("previewLoading", JSONObject().put("url", url).put("message", message))
                        }
                    val (info, route) = inspection
                    val qualities = Quality.offered(info.formats, UrlPolicy.isTikTok(url))
                    if (UrlPolicy.isTikTok(url) && qualities.isEmpty()) throw IllegalStateException("No clean TikTok format")
                    if (generation != previewGeneration.get()) return@execute
                    previewUrl = url
                    previewResolvedUrl = resolvedUrl
                    previewFacebookMedia = facebook?.media
                    previewYoutubeMedia = inspection.raw.takeIf { UrlPolicy.isYoutube(url) }
                    previewHeights = qualities.map { it.height }.toSet()
                    previewTitle = info.title ?: info.fulltitle ?: "Untitled video"
                    previewThumbnail = info.thumbnail.orEmpty()
                    previewDuration = info.duration
                    val formats=info.formats.orEmpty()
                    previewAudioAvailable=formats.isEmpty() || formats.any { it.acodec!=null && it.acodec!="none" }
                    previewVideoAvailable=formats.isEmpty() || formats.any { it.vcodec!="none" && it.ext!="mhtml" }
                    previewRoute = route
                    previewTikTokChoices = if (UrlPolicy.isTikTok(url)) Quality.tikTokChoices(info.formats) else emptyMap()
                    val rows = JSONArray()
                    qualities.forEach { rows.put(JSONObject().put("height", it.height).put("width", it.width).put("fps", it.fps)
                        .put("bytes", Quality.estimatedBytes(info.formats, it.height, info.duration, UrlPolicy.isTikTok(url)))) }
                    send("preview", JSONObject()
                        .put("url", url).put("title", previewTitle)
                        .put("thumbnail", info.thumbnail ?: "").put("qualities", rows).put("duration", info.duration)
                        .put("audioBytes", Quality.audioBytes(info.formats, info.duration)).put("hasAudio",previewAudioAvailable).put("hasVideo",previewVideoAvailable))
                } catch (e: Exception) {
                    if (generation != previewGeneration.get()) return@execute
                    val networkError = Regex("no address associated|unable to resolve|network is unreachable|timed out|connection|could not load facebook", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())
                    if (UrlPolicy.isFacebook(url)) {
                        val login = e is FacebookSession.LoginRequired || Regex("login required|log in|sign in|login to|login\\.php|checkpoint", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty())
                        error(when {
                            login -> "Sign in to Facebook to continue. Quartz will retry this video when you return and use your saved session for future shares."
                            networkError -> "Quartz could not reach Facebook. Check your connection and try again."
                            else -> "Facebook did not provide a downloadable video for this post using your saved session. Check that the post contains a video and is still available to your account."
                        }, if (login) "facebook" else null)
                        return@execute
                    }
                    if (UrlPolicy.isTwitter(url) && !networkError) {
                        error(ExtractorRoutes.X_HINT, "x")
                        return@execute
                    }
                    error(when {
                        e.message?.contains("No clean TikTok", true) == true -> "TikTok did not offer a video without a watermark for this link."
                        Regex("no address associated|unable to resolve|network is unreachable|timed out", RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty()) -> "Quartz could not reach this website. Check your internet connection and try again."
                        e.message?.contains("sign in", true) == true || e.message?.contains("login", true) == true -> "This website asks for a login. Use the sign-in buttons in Settings, or open the page in Quartz's browser and tap Use link."
                        else -> "This link did not reveal a downloadable video. Open it in the browser and try the page link."
                    })
                } finally {
                    BrowserCookies.release(cookies)
                    if (generation == previewGeneration.get()) inspectingUrl = ""
                }
            }
        }

        @JavascriptInterface fun download(rawUrl: String, choice: String, mode: String, audioFormat: String) {
            val url = try { UrlPolicy.normalize(rawUrl) } catch (e: Exception) { error(e.message ?: "Invalid link"); return }
            if(url==previewUrl && ((mode=="audio"&&!previewAudioAvailable)||(mode=="video"&&!previewVideoAvailable))){error(if(mode=="audio") "This video has no audio stream. Choose video instead." else "This link offers audio only. Choose an audio format.");return}
            if (url != previewUrl || mode !in listOf("video", "audio") || audioFormat !in listOf("original", "mp3") || (mode == "video" && choice != "best" && choice.toIntOrNull() !in previewHeights)) {
                error("Preview this link and choose one of its available qualities first.")
                return
            }
            val id = UUID.randomUUID().toString()
            val facebookKey=previewFacebookMedia?.let{FacebookMedia.save(this@MainActivity,it)}.orEmpty()
            val mediaInfoKey=previewYoutubeMedia?.let{DownloadMediaInfo.save(this@MainActivity,it)}.orEmpty()
            val job = JSONObject().put("id", id).put("url", url).put("mode", mode)
                .put("resolvedUrl", previewResolvedUrl)
                .put("facebookMediaKey", facebookKey)
                .put("mediaInfoKey", mediaInfoKey)
                .put("quality", if (mode == "audio") "best" else choice)
                .put("extractorRoute", previewRoute)
                .put("selector", previewTikTokChoices[choice] ?: "")
                .put("title", previewTitle)
                .put("thumbnail", previewThumbnail).put("duration",previewDuration)
                .put("audioFormat", audioFormat).put("audioQuality", if (audioFormat == "mp3") 320 else 0)
                .put("state", "queued").put("progress", 0).put("message", "Waiting to start")
                .put("createdAt", System.currentTimeMillis()).put("type", "media")
            DownloadStore.add(this@MainActivity, job)
            startJobs(listOf(id))
        }

        @JavascriptInterface fun loadPlaylist(raw: String) {
            val url = try { SpotifyCatalog.normalize(raw) } catch (e: Exception) { error(e.message ?: "Paste a Spotify playlist link."); return }
            PlaylistStore.select(this@MainActivity, url)
            if (PlaylistStore.getFor(this@MainActivity, url) == null) {
                PlaylistStore.save(this@MainActivity, JSONObject().put("url", url).put("status", "loading").put("title", "Loading playlist…"))
                SpotifyService.command(this@MainActivity, "load", url)
            }
            send("playlist", PlaylistStore.get(this@MainActivity))
        }
        @JavascriptInterface fun selectPlaylist(url: String) { PlaylistStore.select(this@MainActivity, url); send("playlist", PlaylistStore.get(this@MainActivity)) }
        @JavascriptInterface fun clearSpotifyHistory(raw: String): Boolean {
            val url = try { SpotifyCatalog.normalize(raw) } catch (_: Exception) { return false }
            return PlaylistStore.clearHistory(this@MainActivity, url)
        }
        @JavascriptInterface fun bulkAction(scope: String, rawKeys: String, deleteFiles: Boolean, playlistUrl: String): String {
            return try {
                val rows = JSONArray(rawKeys)
                require(rows.length() in 1..10000)
                val keys = (0 until rows.length()).map { rows.getString(it) }.toSet()
                if (scope == "spotifyTracks") BulkActions.removeTracks(this@MainActivity, SpotifyCatalog.normalize(playlistUrl), keys).toString()
                else BulkActions.apply(this@MainActivity, scope, keys, deleteFiles) { id ->
                    delete(id)
                    DownloadStore.get(this@MainActivity, id) == null
                }.toString()
            } catch (_: Exception) { JSONObject().put("done", 0).put("failed", 1).toString() }
        }
        @JavascriptInterface fun findSpotifyMatches() = findSpotifyMatchesInternal(null)
        @JavascriptInterface fun retrySpotifyMatch(key: String) = findSpotifyMatchesInternal(key)
        @JavascriptInterface fun stopSpotify() { SpotifyService.command(this@MainActivity, "pause", PlaylistStore.get(this@MainActivity).optString("url")) }
        @JavascriptInterface fun downloadControl(action: String, id: String) {
            val nativeAction = when (action) {
                "pause" -> DownloadService.ACTION_PAUSE
                "resume" -> DownloadService.ACTION_RESUME
                "pauseAll" -> DownloadService.ACTION_PAUSE_ALL
                "resumeAll" -> DownloadService.ACTION_RESUME_ALL
                else -> return
            }
            runOnUiThread { startForegroundService(Intent(this@MainActivity, DownloadService::class.java).setAction(nativeAction).putExtra(DownloadService.EXTRA_ID, id)) }
        }
        @JavascriptInterface fun clearHistory() {
            val jobs = DownloadStore.all(this@MainActivity)
            for (i in 0 until jobs.length()) if (jobs.getJSONObject(i).optString("state") in listOf("complete", "failed", "cancelled")) DownloadStore.update(this@MainActivity, jobs.getJSONObject(i).getString("id"), JSONObject().put("historyHidden", true))
        }

        @JavascriptInterface fun chooseSpotifyMatch(key: String, url: String) {
            PlaylistStore.update(this@MainActivity) { state ->
                val tracks = state.optJSONArray("tracks") ?: return@update
                for (i in 0 until tracks.length()) {
                    val row = tracks.getJSONObject(i)
                    if (row.optString("key") != key) continue
                    val choices = row.optJSONArray("candidates") ?: continue
                    if ((0 until choices.length()).any { choices.getJSONObject(it).optString("url") == url }) {
                        row.put("selectedUrl", url).put("confirmed", true).put("status", "matched")
                    }
                }
            }
        }

        @JavascriptInterface fun downloadSpotify(rawKeys: String, audioFormat: String) {
            if (audioFormat !in listOf("original", "mp3")) return
            val keys = try { JSONArray(rawKeys) } catch (_: Exception) { return }
            if (keys.length() !in 1..1000) { error("Select songs with reviewed matches first."); return }
            val chosen = (0 until keys.length()).map { keys.optString(it) }.toSet()
            val playlist = PlaylistStore.get(this@MainActivity)
            val tracks = playlist.optJSONArray("tracks") ?: return
            val existing = DownloadStore.all(this@MainActivity)
            val jobs = mutableListOf<JSONObject>()
            val assigned = mutableMapOf<String, String>()
            for (i in 0 until tracks.length()) {
                val row = tracks.getJSONObject(i)
                val key = row.optString("key")
                if (key !in chosen) continue
                val candidates = row.optJSONArray("candidates") ?: continue
                val selected = (0 until candidates.length()).map { candidates.getJSONObject(it) }.find { it.optString("url") == row.optString("selectedUrl") } ?: continue
                if (!row.optBoolean("confirmed") && (selected.optDouble("score") < .82 || selected.optDouble("duration") <= 0)) continue
                val previous = (jobs + (0 until existing.length()).map { existing.getJSONObject(it) }).find {
                    it.optString("spotifyTrackUri") == row.optString("uri") && it.optString("audioFormat", "mp3") == audioFormat &&
                        (audioFormat == "original" || it.optInt("audioQuality") == 320) &&
                        it.optString("state") in listOf("complete", "queued", "preparing", "downloading", "processing", "saving", "paused", "waiting")
                }
                if (previous != null) { assigned[key] = previous.getString("id"); continue }
                val id = UUID.randomUUID().toString()
                val artists = row.getJSONArray("artists")
                val artistText = (0 until artists.length()).joinToString(", ") { artists.getString(it) }
                jobs.add(JSONObject().put("id", id).put("url", selected.getString("url")).put("mode", "audio").put("quality", "best")
                    .put("audioFormat", audioFormat).put("audioQuality", if (audioFormat == "mp3") 320 else 0)
                    .put("title", "$artistText — ${row.getString("name")}").put("spotifyTitle", row.getString("name"))
                    .put("spotifyArtists", artistText).put("spotifyTrackUri", row.getString("uri")).put("spotifyPlaylist", playlist.optString("title"))
                    .put("thumbnail",row.optString("thumbnail")).put("duration",row.optDouble("duration")).put("spotifyAlbum",row.optString("album"))
                    .put("sourceUrl", "https://open.spotify.com/track/${row.getString("uri").substringAfterLast(':')}")
                    .put("extractorRoute", ExtractorRoutes.DEFAULT).put("type", "media").put("state", "queued").put("progress", 0)
                    .put("message", "Waiting to download matched audio").put("createdAt", System.currentTimeMillis()))
                assigned[key] = id
            }
            if (assigned.isEmpty()) { error("Find matches and confirm uncertain ones before downloading."); return }
            DownloadStore.addAll(this@MainActivity, jobs)
            PlaylistStore.update(this@MainActivity) { state ->
                val stored = state.getJSONArray("tracks")
                for (i in 0 until stored.length()) assigned[stored.getJSONObject(i).optString("key")]?.let { stored.getJSONObject(i).put("jobId", it) }
            }
            startJobs(jobs.map { it.getString("id") })
            send("spotifyQueued", JSONObject().put("count", jobs.size).put("existing", assigned.size - jobs.size).put("format", audioFormat))
        }

        @JavascriptInterface fun retrySpotifyDownload(id: String) {
            retryJob(id)
        }

        @JavascriptInterface fun retry(id: String) = retryJob(id)
        @JavascriptInterface fun closeShare() = runOnUiThread { if (shareOnly) finish() }
        @JavascriptInterface fun openDownloads() = runOnUiThread {
            startActivity(Intent(this@MainActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            if (shareOnly) finish()
        }
        @JavascriptInterface fun openSharedPlaylist(url: String) = runOnUiThread {
            startActivity(Intent(this@MainActivity, MainActivity::class.java).putExtra("quartz_url", url).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            if (shareOnly) finish()
        }
        @JavascriptInterface fun notificationSettings() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !getSharedPreferences("quartz_ui", MODE_PRIVATE).getBoolean("notificationAsked", false)) {
                notificationRequest = true
                getSharedPreferences("quartz_ui", MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            } else startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        }

        @JavascriptInterface fun openSaveLocation() = runOnUiThread {
            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FQuartz%20Downloader"),"vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            catch (_:Exception) { android.app.AlertDialog.Builder(this@MainActivity).setTitle("Save location").setMessage("Your files are saved in Downloads / Quartz Downloader. Open your phone's Files app to view or share them.").setPositiveButton("OK",null).show() }
        }

        @JavascriptInterface fun backgroundSettings() = runOnUiThread {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        @JavascriptInterface fun browser(raw: String) = runOnUiThread { openBrowser(raw) }
        @JavascriptInterface fun browseSearch(provider:String,query:String) = runOnUiThread {
            startActivity(Intent(this@MainActivity,BrowserActivity::class.java).putExtra("provider",provider).putExtra("query",query))
        }
        @JavascriptInterface fun play(id:String) = runOnUiThread {
            val job=DownloadStore.get(this@MainActivity,id) ?: return@runOnUiThread
            if(job.optString("state")!="complete"||job.optString("mode")!="audio")return@runOnUiThread
            startForegroundService(Intent(this@MainActivity,PlaybackService::class.java).setAction("play").putExtra("id",id))
        }
        @JavascriptInterface fun playerAction(action:String,position:Int) = runOnUiThread {
            if(action !in listOf("toggle","next","previous","seek","stop")||PlaybackService.state().optString("id").isBlank())return@runOnUiThread
            startService(Intent(this@MainActivity,PlaybackService::class.java).setAction(action).putExtra("position",position))
        }

        @JavascriptInterface fun signIn(site: String) {
            val target = when (site) {
                "x" -> "https://x.com/i/flow/login" to "X"
                "facebook" -> "https://m.facebook.com/login/" to "Facebook"
                "instagram" -> "https://www.instagram.com/accounts/login/" to "Instagram"
                else -> return
            }
            runOnUiThread {
                if (site == "facebook") facebookSignInLink = lastFacebookLink
                startActivity(Intent(this@MainActivity, BrowserActivity::class.java)
                    .putExtra("url", target.first).putExtra("account", target.second))
            }
        }

        @JavascriptInterface fun setting(name: String, value: String) {
            val editor = getSharedPreferences("quartz_ui", MODE_PRIVATE).edit()
            when {
                name == "defaultMode" && value in listOf("video", "audio") -> editor.putString(name, value)
                name == "askQuality" && value in listOf("true", "false") -> editor.putBoolean(name, value == "true")
                name == "parallelDownloads" && value in listOf("1", "2", "3") -> editor.putInt(name, value.toInt())
                name == "spotifyAudioFormat" && value in listOf("original", "mp3") -> editor.putString(name, value)
                name == "videoAudioFormat" && value in listOf("original", "mp3") -> editor.putString(name, value)
                else -> return
            }
            editor.apply()
            if (name == "parallelDownloads" && DownloadService.active) startForegroundService(Intent(this@MainActivity, DownloadService::class.java))
        }

        @JavascriptInterface fun paste(): String {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            return clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }

        @JavascriptInterface fun copy(id: String) {
            val job = DownloadStore.get(this@MainActivity, id) ?: return
            val url = job.optString("sourceUrl", job.optString("url"))
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Quartz link", url))
        }

        @JavascriptInterface fun open(id: String) {
            val job = DownloadStore.get(this@MainActivity, id) ?: return
            val uri = job.optString("uri").takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return
            val mime = MediaFiles.mime(job.optString("filename"))
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { Toast.makeText(this@MainActivity, "No app can open this file.", Toast.LENGTH_SHORT).show() }
            }
        }

        @JavascriptInterface fun delete(id: String) {
            val job = DownloadStore.get(this@MainActivity, id) ?: return
            if (job.optString("type") != "direct" && job.optString("state") in listOf("queued", "preparing", "downloading", "processing", "saving")) {
                startService(Intent(this@MainActivity, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL).putExtra(DownloadService.EXTRA_ID, id))
            }
            if (job.optString("state") == "paused" || job.optString("state") == "waiting") File(filesDir, "working/$id").deleteRecursively()
            try {
                if(PlaybackService.state().optString("id")==id)runOnUiThread { stopService(Intent(this@MainActivity,PlaybackService::class.java)) }
                if (job.optString("type") == "direct") {
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).remove(job.optLong("dmId"))
                } else if (job.optString("uri").isNotBlank()) {
                    contentResolver.delete(Uri.parse(job.optString("uri")), null, null)
                }
                DownloadStore.remove(this@MainActivity, id)
                File(filesDir,"covers/$id.jpg").delete()
            } catch (_: Exception) { error("This file could not be removed.") }
        }

        @JavascriptInterface fun theme(value: String) {
            if (value !in listOf("dark", "light")) return
            getSharedPreferences("quartz_ui", MODE_PRIVATE).edit().putString("theme", value).apply()
        }
    }

    private fun findSpotifyMatchesInternal(key: String?) {
        val state = PlaylistStore.get(this)
        if (state.optString("url").isBlank()) return
        SpotifyService.command(this, "match", state.getString("url"), key.orEmpty())
    }

    private fun retryJob(id: String) {
        val job = DownloadStore.get(this, id) ?: return
        if (job.optString("type") != "media" || job.optString("state") !in listOf("failed", "cancelled")) return
        DownloadStore.update(this, id, JSONObject().put("state", "queued").put("retryAt", 0).put("message", "Waiting to retry"))
        DownloadNotifications.dismiss(this, id)
        startJobs(listOf(id))
    }

    private fun startJobs(ids: List<String>) = runOnUiThread {
        if (ids.isEmpty()) return@runOnUiThread
        waitingJobs.addAll(ids)
        if (notificationRequest) return@runOnUiThread
        val prefs = getSharedPreferences("quartz_ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notificationAsked", false)) {
            notificationRequest = true
            prefs.edit().putBoolean("notificationAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        } else launchWaitingJobs()
    }

    private fun launchWaitingJobs() {
        val ids = waitingJobs.toList()
        waitingJobs.clear()
        for (id in ids) {
            try { startForegroundService(Intent(this, DownloadService::class.java).putExtra(DownloadService.EXTRA_ID, id)) }
            catch (_: Exception) { DownloadStore.update(this, id, JSONObject().put("state", "failed").put("message", "Could not start this download.")) }
        }
        if (ids.isNotEmpty()) {
            send("queued", JSONObject().put("count", ids.size))
            if (shareOnly) { Toast.makeText(this, "Download added to Quartz", Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) { notificationRequest = false; launchWaitingJobs() }
    }

    override fun onResume() {
        super.onResume()
        if (facebookSignInLink.isNotBlank() && BrowserCookies.status().optBoolean("facebook")) {
            val retry = facebookSignInLink; facebookSignInLink = ""
            send("sharedLink", JSONObject().put("url", retry).put("popup", true))
        }
        if(!shareOnly){AppUpdates.resume(this);AppUpdates.check(this,false)}
        try {
            if (!DownloadService.active) {
                DownloadStore.recoverInterrupted(this)
                val rows = DownloadStore.all(this)
                if ((0 until rows.length()).any { rows.getJSONObject(it).optString("type") != "direct" && rows.getJSONObject(it).optString("state") in DownloadService.RUNNING }) startForegroundService(Intent(this, DownloadService::class.java))
            }
            SpotifyService.restore(this)
        } catch (_: Exception) { /* Saved work remains available to Resume. */ }
        if (pageReady) web.evaluateJavascript("refresh()", null)
    }

    override fun onConfigurationChanged(config:android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        window.decorView.requestLayout()
        web.requestLayout()
        web.post {
            if(pageReady) web.evaluateJavascript("window.dispatchEvent(new Event('resize'));refresh()",null)
            web.postVisualStateCallback(0,object:WebView.VisualStateCallback(){override fun onComplete(id:Long){web.invalidate()}})
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!pageReady) { finish(); return }
        web.evaluateJavascript("window.Quartz.back()") { handled -> if (handled != "true") finish() }
    }

    override fun onDestroy() {
        pageReady = false
        previewGeneration.incrementAndGet()
        previewProcessId.takeIf { it.isNotBlank() }?.let { YoutubeDL.destroyProcessById(it) }
        worker.shutdownNow()
        web.removeJavascriptInterface("QuartzNative")
        web.destroy()
        super.onDestroy()
    }
}
