package com.quartz.downloader

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class DownloadService : Service() {
    companion object {
        const val EXTRA_ID = "job_id"
        const val ACTION_CANCEL = "com.quartz.downloader.CANCEL"
        const val ACTION_PAUSE = "com.quartz.downloader.PAUSE"
        const val ACTION_RESUME = "com.quartz.downloader.RESUME"
        const val ACTION_PAUSE_ALL = "com.quartz.downloader.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.quartz.downloader.RESUME_ALL"
        val RUNNING = listOf("queued", "preparing", "downloading", "processing", "saving", "waiting")
        @Volatile var active = false; private set
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var currentId = ""
    private var busy = false
    private var destroyed = false
    private var lastNotificationUpdate = 0L
    private lateinit var wake: android.os.PowerManager.WakeLock
    private val cancelled = mutableSetOf<String>()
    private val later = Runnable { pump() }
    private val publishProgress = Runnable {
        DownloadStore.get(this, currentId)?.let { DownloadNotifications.update(this, it, waiting()) }
        lastNotificationUpdate = SystemClock.elapsedRealtime()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); active = true
        DownloadNotifications.channels(this)
        DownloadStore.recoverInterrupted(this)
        wake = (getSystemService(POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Quartz:Downloads")
        wake.acquire(6 * 60 * 60 * 1000L)
    }
    private fun rows(): List<JSONObject> {
        val data = DownloadStore.all(this)
        return (0 until data.length()).map { data.getJSONObject(it) }.filter { it.optString("type") != "direct" }
            .sortedBy { it.optLong("createdAt") }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID).orEmpty()
        val action = intent?.action
        val affected = if (action == ACTION_PAUSE_ALL || action == ACTION_RESUME_ALL) rows() else listOfNotNull(DownloadStore.get(this, id))
        for (job in affected) {
            val jobId = job.optString("id")
            when (action) {
                ACTION_CANCEL, ACTION_PAUSE, ACTION_PAUSE_ALL -> if (job.optString("state") in RUNNING || job.optString("state") == "paused") {
                    synchronized(cancelled) {
                        if (jobId == currentId) cancelled.add(jobId)
                        DownloadStore.update(this, jobId, JSONObject().put("state", if (action == ACTION_CANCEL) "cancelled" else "paused")
                            .put("message", if (action == ACTION_CANCEL) "Cancelled" else "Paused. Progress saved. Tap Resume to continue."))
                    }
                    if (jobId == currentId) { YoutubeDL.destroyProcessById(jobId); AudioArtwork.cancel(jobId) }
                    DownloadStore.get(this, jobId)?.let { DownloadNotifications.result(this, it) }
                    if (action == ACTION_CANCEL && jobId != currentId) File(filesDir, "working/$jobId").deleteRecursively()
                }
                ACTION_RESUME, ACTION_RESUME_ALL -> if (job.optString("state") in (if (action == ACTION_RESUME_ALL) listOf("paused") else listOf("paused", "waiting", "failed", "cancelled"))) {
                    DownloadStore.update(this, jobId, JSONObject().put("state", "queued").put("retryAt", 0).put("attempts", 0).put("message", "Waiting to resume…"))
                    DownloadNotifications.dismiss(this, jobId)
                }
            }
        }
        // startForeground must run even when a notification action starts an idle service.
        val visible = DownloadStore.get(this, currentId) ?: rows().firstOrNull { it.optString("state") in RUNNING || it.optString("state") == "paused" } ?: JSONObject().put("title", "Quartz Downloader")
        startForeground(DownloadNotifications.ACTIVE_ID, DownloadNotifications.progress(this, visible, waiting()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        pump()
        return START_STICKY
    }
    private fun pump() {
        if (busy || destroyed) return
        main.removeCallbacks(later)
        val pending = rows().filter { it.optString("state") in RUNNING }
        val next = pending.firstOrNull { it.optLong("retryAt") <= System.currentTimeMillis() }
        if (next == null) {
            if (pending.isNotEmpty()) {
                currentId = pending.first().getString("id"); refreshProgress(); main.postDelayed(later, 15000); return
            }
            currentId = ""; main.removeCallbacks(publishProgress); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        busy = true; currentId = next.getString("id"); val id = currentId
        worker.execute {
            try { download(id) }
            finally { main.post { busy = false; currentId = ""; pump() } }
        }
    }

    private fun download(id: String) {
        val job = DownloadStore.get(this, id) ?: return
        var url = job.optString("url")
        val mode = job.optString("mode", "video")
        val quality = job.optString("quality", "best")
        val audioFormat = job.optString("audioFormat", "mp3")
        val dir = File(filesDir, "working/$id")
        var cookies: File? = null
        try {
            if (synchronized(cancelled) { id in cancelled } || job.optString("state") !in RUNNING) return
            currentId = id
            if (mode == "audio") DownloadStore.update(this, id, JSONObject().put("audioFormat", audioFormat).put("audioQuality", if (audioFormat == "mp3") 320 else 0))
            DownloadStore.update(this, id, JSONObject().put("state", "preparing").put("message", if (UrlPolicy.isFacebook(url)) "Opening Facebook with your saved session…" else "Preparing media tools…"))
            refreshProgress()
            val facebook = if (UrlPolicy.isFacebook(url)) FacebookSession.resolve(this, job.optString("resolvedUrl").takeIf { UrlPolicy.isFacebook(it) } ?: url) {
                !synchronized(cancelled) { id in cancelled } && !Thread.currentThread().isInterrupted
            } else null
            url = facebook?.url ?: url
            if (synchronized(cancelled) { id in cancelled }) return
            MediaEngine.prepare(this)
            FFmpeg.init(this)
            dir.mkdirs()
            cookies = BrowserCookies.create(this, url)
            fun request(route: String): YoutubeDLRequest {
                val output = if (job.has("spotifyTitle")) {
                    val name = "${job.optString("spotifyArtists")} - ${job.optString("spotifyTitle")}".replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").take(160).trim().trimEnd('.')
                    File(dir, name.replace("%", "%%") + " [%(id)s].%(ext)s")
                } else File(dir, "%(title).160s [%(id)s].%(ext)s")
                val next = ExtractorRoutes.configure(YoutubeDLRequest(url), url, route, cookies, facebook?.userAgent.orEmpty())
                    .addOption("--continue").addOption("--retries", 3).addOption("--fragment-retries", 3)
                    .addOption("--newline")
                    .addOption("--write-info-json")
                    .addOption("-o", output.absolutePath)
                if (mode == "audio") {
                    next.addOption("-f", "bestaudio/best")
                        .addOption("-x").addOption("--audio-format", if (audioFormat == "original") "best" else "mp3")
                    if (audioFormat == "mp3") next.addOption("--audio-quality", "320K")
                } else {
                    val selector = if (UrlPolicy.isTikTok(url)) {
                        job.optString("selector").takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException("No clean TikTok format")
                    } else Quality.selector(quality)
                    next.addOption("-f", selector)
                        .addOption("--merge-output-format", "mp4")
                }
                return next
            }
            DownloadStore.update(this, id, JSONObject().put("state", "downloading").put("message", "Downloading"))
            var lastUpdate = 0L
            val callback: (Float, Long, String) -> Unit = callback@ { progress, eta, line ->
                if (synchronized(cancelled) { id in cancelled }) return@callback
                val percent = progress.toInt().coerceIn(0, 99)
                val processing = line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") || line.startsWith("[VideoConvertor]")
                if (processing || (line.startsWith("[download]") && SystemClock.elapsedRealtime() - lastUpdate >= 1000)) {
                    lastUpdate = SystemClock.elapsedRealtime()
                    val speed = Regex("\\bat\\s+([^\\s]+/s)").find(line)?.groupValues?.get(1).orEmpty()
                    val detail = listOfNotNull(speed.takeIf { it.isNotBlank() }, eta.takeIf { it > 0 && speed.isNotBlank() }?.let { "${it}s left" }).joinToString(" · ")
                    synchronized(cancelled) {
                    if (id in cancelled) return@callback
                    DownloadStore.update(this, id, JSONObject().put("progress", percent).put("state", if (processing) "processing" else "downloading")
                        .put("message", if (processing) "Preparing your audio / video…" else "Downloading · $percent%${if (detail.isBlank()) "" else " · $detail"}").put("transferDetail", detail))
                    }
                    refreshProgress()
                }
            }
            var result: YoutubeDLResponse? = null
            var lastError: Exception? = null
            for (route in ExtractorRoutes.candidates(url, job.optString("extractorRoute", ExtractorRoutes.DEFAULT))) {
                if (synchronized(cancelled) { id in cancelled }) return
                if (route == ExtractorRoutes.TWITTER_PUBLIC) {
                    DownloadStore.update(this, id, JSONObject().put("message", "Trying X's public video endpoint…"))
                }
                try {
                    result = YoutubeDL.execute(request(route), id, callback)
                    DownloadStore.update(this, id, JSONObject().put("extractorRoute", route))
                    break
                } catch (error: Exception) {
                    if (synchronized(cancelled) { id in cancelled } || error is InterruptedException || Thread.currentThread().isInterrupted) throw error
                    lastError = error
                    lastUpdate = 0
                    // Keep partial streams: yt-dlp resumes compatible files by format ID.
                }
            }
            val response = result ?: throw (lastError ?: IllegalStateException("No saved file was produced."))
            if (synchronized(cancelled) { id in cancelled }) return
            if (response.exitCode != 0) throw IllegalStateException("The website could not finish this download.")
            val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 && it.extension.lowercase() in listOf("mp3","m4a","opus","ogg","flac","wav","aac","mp4","webm","mkv") && !Regex("\\.f\\d+\\.").containsMatchIn(it.name) }.orEmpty()
            val file = files.maxByOrNull { it.length() } ?: throw IllegalStateException("No saved file was produced.")
            if(mode=="audio") {
                DownloadStore.update(this,id,JSONObject().put("state","processing").put("message","Adding cover picture and music tags…"))
                refreshProgress()
                val info=dir.listFiles()?.find{it.name.endsWith(".info.json")}?.let { JSONObject(it.readText()) } ?: JSONObject()
                DownloadStore.update(this,id,AudioArtwork.embed(this,job,file,info) { synchronized(cancelled) { id in cancelled } || Thread.currentThread().isInterrupted })
            } else if(job.optString("thumbnail").isNotBlank()) {
                try { AudioArtwork.thumbnail(this,id,job.getString("thumbnail"))?.let { DownloadStore.update(this,id,JSONObject().put("artworkPath",it.absolutePath)) } } catch (_:Exception) { }
            }
            if (synchronized(cancelled) { id in cancelled }) return
            DownloadStore.update(this, id, JSONObject().put("state", "saving").put("message", "Saving to Downloads…"))
            refreshProgress()
            val uri = MediaFiles.publish(this, file, id)
            synchronized(cancelled) {
                if (id in cancelled) { contentResolver.delete(uri, null, null); DownloadStore.update(this, id, JSONObject().put("publishUri", "")); return }
                DownloadStore.update(this, id, JSONObject()
                    .put("state", "complete").put("progress", 100)
                    .put("filename", file.name).put("uri", uri.toString()).put("message", "Saved to Downloads / Quartz Downloader"))
                if (DownloadStore.get(this, id)?.optString("state") == "complete") { file.delete(); dir.delete() }
            }
            DownloadStore.get(this, id)?.let { DownloadNotifications.result(this, it) }
        } catch (error: Exception) {
            if (synchronized(cancelled) { id in cancelled }) return
            val text = error.message.orEmpty().lowercase()
            val temporary = Regex("no address associated|unable to resolve|network is unreachable|timed out|connection|network|temporary|http error 5|http error 429|remote end closed", RegexOption.IGNORE_CASE).containsMatchIn(text)
            if (temporary || error is InterruptedException) {
                val attempt = (DownloadStore.get(this, id)?.optInt("attempts") ?: 0) + 1
                DownloadStore.update(this, id, JSONObject().put("state", "waiting").put("attempts", attempt)
                    .put("retryAt", System.currentTimeMillis() + minOf(120000L, 5000L * attempt))
                    .put("message", "Connection interrupted. Progress saved; retrying automatically…"))
                return
            }
            val message = when {
                "no address associated" in text || "unable to resolve" in text || "network is unreachable" in text || "timed out" in text -> "Quartz could not reach this website. Check your internet connection and try again."
                "requested format" in text || "format is not available" in text -> "That quality is no longer available. Preview the link again."
                "cover" in text || "audio tags" in text -> "The cover picture could not be saved. Tap Retry to finish the download."
                UrlPolicy.isFacebook(url) && (error is FacebookSession.LoginRequired || "login" in text || "sign in" in text || "log in" in text) -> FacebookSession.LoginRequired().message!!
                UrlPolicy.isFacebook(url) -> "Facebook did not provide this video using your saved session. Check that the post is still available to your account, then tap Retry."
                UrlPolicy.isTwitter(url) -> ExtractorRoutes.X_HINT
                "sign in" in text || "login" in text || "cookie" in text -> "This website needs a login. Open its sign-in page inside Quartz and preview the link again."
                "403" in text || "429" in text -> "The website declined the download. Please try again later."
                else -> "This website could not provide a downloadable file."
            }
            DownloadStore.update(this, id, JSONObject().put("state", "failed").put("message", message))
            DownloadStore.get(this, id)?.let { DownloadNotifications.result(this, it) }
        } finally {
            BrowserCookies.release(cookies)
            if (DownloadStore.get(this, id)?.optString("state") in listOf(null, "complete", "cancelled")) dir.deleteRecursively()
            synchronized(cancelled) { cancelled.remove(id) }
        }
    }

    private fun waiting(): Int = rows().count { it.optString("id") != currentId && it.optString("state") in RUNNING }
    private fun refreshProgress() {
        main.post {
            main.removeCallbacks(publishProgress)
            main.postDelayed(publishProgress, (1000 - (SystemClock.elapsedRealtime() - lastNotificationUpdate)).coerceAtLeast(0))
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        for (row in rows().filter { it.optString("state") in RUNNING }) {
            val id = row.getString("id")
            synchronized(cancelled) { cancelled.add(id) }
            DownloadStore.update(this, id, JSONObject().put("state", "paused").put("message", "Android paused background work. Tap Resume; progress is saved."))
            YoutubeDL.destroyProcessById(id); AudioArtwork.cancel(id)
            DownloadStore.get(this, id)?.let { DownloadNotifications.result(this, it) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        destroyed = true; active = false; main.removeCallbacksAndMessages(null)
        synchronized(cancelled) { if (currentId.isNotBlank()) cancelled.add(currentId) }
        if (currentId.isNotBlank()) { YoutubeDL.destroyProcessById(currentId); AudioArtwork.cancel(currentId) }
        worker.shutdownNow()
        if (::wake.isInitialized && wake.isHeld) wake.release()
        super.onDestroy()
    }
}
