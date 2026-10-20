package com.quartz.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile
import kotlin.concurrent.read

object MediaEngine {
    private val gate = ReentrantReadWriteLock()
    private val maintenance = Executors.newSingleThreadScheduledExecutor()
    private val updating = AtomicBoolean()
    @Volatile private var app: Context? = null
    @Synchronized fun prepare(context: Context) {
        if (app == null) { YoutubeDL.init(context.applicationContext); app = context.applicationContext }
        val prefs = context.getSharedPreferences("quartz_engine", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastSuccessfulUpdate", 0) < TimeUnit.DAYS.toMillis(1) ||
            now - prefs.getLong("lastUpdateAttempt", 0) < TimeUnit.HOURS.toMillis(1) || !updating.compareAndSet(false, true)) return
        prefs.edit().putLong("lastUpdateAttempt", now).apply()
        // Network maintenance must never be part of a video lookup's critical path.
        maintenance.execute {
            try { stageUpdate(context.applicationContext) }
            catch (_: Exception) { updating.set(false) } // Keep the working bundled engine.
        }
    }

    fun execute(request: YoutubeDLRequest, id: String? = null, callback: ((Float, Long, String) -> Unit)? = null): YoutubeDLResponse = gate.read {
        app?.let { request.addOption("--cache-dir", File(it.cacheDir, "extractor").apply { mkdirs() }.absolutePath) }
        YoutubeDL.execute(request, id, callback)
    }
    fun inspect(request: YoutubeDLRequest, id: String?): JSONObject {
        val context = checkNotNull(app) { "Media tools are not initialized" }
        val folder = File(context.cacheDir, "inspection/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            // The library reads stdout one character at a time. Large format JSON is much faster as a buffered file.
            execute(request.addOption("--skip-download").addOption("--write-info-json").addOption("-f", "bv*+ba/b")
                .addOption("-o", File(folder, "media.%(ext)s").absolutePath), id)
            val extracted = android.os.SystemClock.elapsedRealtime()
            val file = File(folder, "media.info.json")
            check(file.isFile && file.length() in 1..8_000_000) { "The website did not return video formats" }
            val result = JSONObject(file.readText())
            android.util.Log.d("QuartzLookup", "extract=${extracted-started}ms read=${android.os.SystemClock.elapsedRealtime()-extracted}ms bytes=${file.length()}")
            return result
        } finally { folder.deleteRecursively() }
    }
    private fun download(url: String, target: File, limit: Long) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 4000; connection.readTimeout = 5000
        connection.setRequestProperty("User-Agent", "Quartz-Downloader-Android")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            check(connection.responseCode == 200 && connection.url.protocol == "https")
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; check(total <= limit); output.write(buffer, 0, n) }
            } }
        } finally { connection.disconnect() }
    }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun stageUpdate(context: Context) {
        val folder = File(context.noBackupFilesDir, "youtubedl-android")
        val metadata = File(folder, "release-check.tmp")
        val stage = File(folder, "engine-update.tmp")
        try {
            download("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest", metadata, 2_000_000)
            val release = JSONObject(metadata.readText())
            check(!release.optBoolean("draft") && !release.optBoolean("prerelease"))
            val tag = release.getString("tag_name"); check(tag.matches(Regex("[0-9A-Za-z._-]+")))
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.single { it.optString("name") == "yt-dlp" }
            val expected = asset.getString("digest").removePrefix("sha256:")
            check(expected.matches(Regex("[a-f0-9]{64}")))
            val url = "https://github.com/yt-dlp/yt-dlp/releases/download/$tag/yt-dlp"
            check(asset.getString("browser_download_url") == url && asset.getLong("size") in 1..12_000_000)
            val installed = File(folder, "yt-dlp/yt-dlp")
            if (sha(installed) == expected) { success(context); updating.set(false); return }
            download(url, stage, 12_000_000)
            check(stage.length() == asset.getLong("size") && sha(stage) == expected)
            ZipFile(stage).use { check(it.getEntry("yt_dlp/__main__.py") != null && it.getEntry("yt_dlp/version.py") != null) }
            installWhenIdle(context, stage, installed)
        } catch (error: Exception) { stage.delete(); throw error }
        finally { metadata.delete() }
    }
    private fun installWhenIdle(context: Context, stage: File, installed: File) {
        // Python lazily imports from this zip. Never replace it while any extractor is using it.
        if (!gate.writeLock().tryLock()) { maintenance.schedule({ installWhenIdle(context, stage, installed) }, 5, TimeUnit.SECONDS); return }
        try { check(stage.renameTo(installed)); success(context) }
        catch (_: Exception) { stage.delete() }
        finally { gate.writeLock().unlock(); updating.set(false) }
    }
    private fun success(context: Context) {
        context.getSharedPreferences("quartz_engine", Context.MODE_PRIVATE).edit().putLong("lastSuccessfulUpdate", System.currentTimeMillis()).apply()
    }
}
