package com.quartz.downloader

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Import and matching survive destruction of every app screen. */
class SpotifyService : Service() {
    companion object {
        @Volatile var active = false; private set
        fun command(context: Context, action: String, url: String, key: String = "") {
            context.startForegroundService(Intent(context, SpotifyService::class.java).setAction(action).putExtra("url", url).putExtra("key", key))
        }
        fun restore(context: Context) {
            if (!active && PlaylistStore.all(context).any { it.optBoolean("wantMatching") || it.optString("status") == "loading" }) command(context, "restore", "")
        }
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val searches = Executors.newFixedThreadPool(2)
    private val searchIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val searchSession = java.util.UUID.randomUUID().toString()
    private fun cancelSearches() { for (id in searchIds) YoutubeDL.destroyProcessById(id) }
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var destroyed = false
    @Volatile private var currentUrl = ""
    @Volatile private var token = 0L
    private lateinit var wake: PowerManager.WakeLock
    private val later = Runnable { pump() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); active = true; DownloadNotifications.channels(this)
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Quartz:Spotify")
        wake.acquire(6 * 60 * 60 * 1000L)
        for (row in PlaylistStore.all(this)) if (row.optString("status") == "matching") PlaylistStore.updateFor(this, row.getString("url")) { it.put("wantMatching", true).put("status", "queued") }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url").orEmpty()
        when (intent?.action) {
            "pause" -> {
                PlaylistStore.updateFor(this, url) { it.put("wantMatching", false).put("importPaused", true).put("status", "paused").put("message", "Paused. Your YouTube matches are saved.") }
                if (currentUrl == url) { token++; cancelSearches() }
            }
            "load" -> if (PlaylistStore.getFor(this, url) == null) PlaylistStore.save(this, JSONObject().put("url", url).put("status", "loading").put("title", "Loading playlist…"))
            "match" -> PlaylistStore.updateFor(this, url) { row ->
                val key = intent?.getStringExtra("key").orEmpty()
                if (key.isNotBlank()) {
                    val tracks = row.optJSONArray("tracks") ?: JSONArray()
                    for (i in 0 until tracks.length()) if (tracks.getJSONObject(i).optString("key") == key) {
                        SpotifyMatchCache.invalidate(this, tracks.getJSONObject(i))
                        tracks.getJSONObject(i).put("searched", false).put("candidates", JSONArray())
                    }
                }
                row.put("wantMatching", true).put("importPaused", false).put("retryAt", 0).put("status", if (row.has("tracks")) "queued" else "loading").put("message", "Waiting to continue finding matches…")
            }
        }
        notifyProgress(); pump(); return START_STICKY
    }
    private fun pending() = PlaylistStore.all(this).filter { !it.optBoolean("importPaused") && (it.optBoolean("wantMatching") || it.optString("status") == "loading") }
    private fun pump() {
        if (busy || destroyed) return
        val next = pending().firstOrNull { it.optLong("retryAt") <= System.currentTimeMillis() }
        if (next == null) {
            if (pending().isNotEmpty()) { main.removeCallbacks(later); main.postDelayed(later, 15000); return }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        busy = true; currentUrl = next.getString("url"); val url = currentUrl; val generation = ++token
        worker.execute {
            try {
                var state = PlaylistStore.getFor(this, url) ?: return@execute
                if (!state.has("tracks")) {
                    val loaded = SpotifyCatalog.load(url)
                    if (generation != token) return@execute
                    PlaylistStore.updateFor(this, url) { old ->
                        val wants = old.optBoolean("wantMatching")
                        for (key in loaded.keys()) old.put(key, loaded.get(key))
                        old.put("wantMatching", wants).put("retryAt", 0)
                    }
                    state = PlaylistStore.getFor(this, url)!!
                }
                if (!state.optBoolean("wantMatching")) return@execute
                MediaEngine.prepare(this)
                if (generation != token || Thread.currentThread().isInterrupted) return@execute
                PlaylistStore.updateFor(this, url) { it.put("status", "matching").put("message", "Finding YouTube matches. You can leave Quartz.") }
                val tracks = state.getJSONArray("tracks")
                val pendingTracks = (0 until tracks.length()).map { tracks.getJSONObject(it) }
                    .filter { (it.optJSONArray("candidates")?.length() ?: 0) == 0 && !it.optBoolean("searched") }.iterator()
                val completions = java.util.concurrent.ExecutorCompletionService<Pair<JSONObject, JSONArray>>(searches)
                val futures = mutableListOf<java.util.concurrent.Future<Pair<JSONObject, JSONArray>>>()
                var inFlight = 0
                var sequence = 0
                try {
                    while (pendingTracks.hasNext() || inFlight > 0) {
                        if (generation != token || Thread.currentThread().isInterrupted) return@execute
                        while (inFlight < 2 && pendingTracks.hasNext()) {
                            val row = pendingTracks.next()
                            val processId = "spotify-match-$searchSession-$generation-${sequence++}"
                            futures.add(completions.submit(java.util.concurrent.Callable {
                                searchIds.add(processId)
                                try { row to SpotifyMatcher.find(row, processId, this) { generation == token } }
                                finally { searchIds.remove(processId) }
                            }))
                            inFlight++
                        }
                        val completed = completions.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                        inFlight--
                        val (row, result) = completed.get()
                        if (generation != token) return@execute
                        PlaylistStore.updateFor(this, url) { saved ->
                            val stored = saved.getJSONArray("tracks")
                            for (n in 0 until stored.length()) {
                                val target = stored.getJSONObject(n)
                                if (target.optString("key") != row.optString("key") || target.optBoolean("confirmed")) continue
                                val best = result.optJSONObject(0)
                                val strong = best != null && best.optDouble("score") >= .82 && best.optDouble("duration") > 0
                                target.put("candidates", result).put("selectedUrl", best?.optString("url").orEmpty()).put("confirmed", false).put("searched", true)
                                    .put("status", if (best == null) "unmatched" else if (strong) "matched" else "review").put("message", if (best == null) "No audio match found. Try search again." else "")
                            }
                            val done = (0 until stored.length()).count { stored.getJSONObject(it).optBoolean("searched") || (stored.getJSONObject(it).optJSONArray("candidates")?.length() ?: 0) > 0 }
                            saved.put("message", "Finding matches · $done / ${stored.length()} saved · 2 searches at once")
                        }
                        main.post { notifyProgress() }
                    }
                } finally {
                    futures.forEach { if (!it.isDone) it.cancel(true) }
                    cancelSearches()
                }
                if (generation == token) PlaylistStore.updateFor(this, url) { it.put("status", "ready").put("wantMatching", false).put("message", "Search complete. YouTube matches saved on this phone.") }
            } catch (_: Exception) {
                if (generation == token) PlaylistStore.updateFor(this, url) { it.put("status", if (it.has("tracks")) "waiting" else "loading").put("retryAt", System.currentTimeMillis() + 30000).put("message", "Waiting to retry. Your saved matches are kept.") }
            } finally { main.post { busy = false; currentUrl = ""; pump() } }
        }
    }
    private fun notifyProgress() {
        val row = PlaylistStore.getFor(this, currentUrl) ?: pending().firstOrNull() ?: PlaylistStore.get(this)
        val url = row.optString("url")
        val open = PendingIntent.getActivity(this, 400, Intent(this, MainActivity::class.java).putExtra("quartz_tab", "spotify").setData(Uri.parse("quartz://spotify")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 401, Intent(this, SpotifyService::class.java).setAction("pause").putExtra("url", url), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val tracks = row.optJSONArray("tracks") ?: JSONArray()
        val done = (0 until tracks.length()).count { tracks.getJSONObject(it).optBoolean("searched") || (tracks.getJSONObject(it).optJSONArray("candidates")?.length() ?: 0) > 0 }
        val notification = Notification.Builder(this, DownloadNotifications.PROGRESS).setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("Spotify · " + row.optString("title", "Finding matches"))
            .setContentText(row.optString("message", "Loading playlist…")).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(tracks.length().coerceAtLeast(1), done, tracks.length() == 0)
            .addAction(Notification.Action.Builder(null, "Pause search", pause).build()).build()
        startForeground(1044, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        token++; cancelSearches()
        for (row in pending()) PlaylistStore.updateFor(this, row.getString("url")) { it.put("importPaused", true).put("wantMatching", false).put("status", "paused").put("message", "Android paused background work. Resume to continue from saved matches.") }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        destroyed = true; active = false; token++; main.removeCallbacksAndMessages(null); cancelSearches(); worker.shutdownNow(); searches.shutdownNow()
        if (::wake.isInitialized && wake.isHeld) wake.release()
        super.onDestroy()
    }
}
