package com.quartz.downloader

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.UUID

object PreviewCache {
    private data class Entry(val at: Long, val route: String, val raw: String)
    private val rows = linkedMapOf<String, Entry>()
    private val keyPattern = Regex("[a-f0-9-]{36}")
    private fun key(url: String): String {
        val uri = Uri.parse(url)
        val id = if (uri.host == "youtu.be") uri.pathSegments.firstOrNull() else uri.getQueryParameter("v")
            ?: uri.pathSegments.takeIf { it.firstOrNull() in listOf("shorts", "live", "embed") }?.getOrNull(1)
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: url
    }
    @Synchronized fun get(url: String): MediaInspection? {
        if (!UrlPolicy.isYoutube(url)) return null
        val row = rows[key(url)] ?: return null
        if (SystemClock.elapsedRealtime() - row.at > 5 * 60 * 1000) { rows.remove(key(url)); return null }
        val raw = JSONObject(row.raw)
        return MediaInspection(FacebookMedia.videoInfo(raw), row.route, raw)
    }
    @Synchronized fun put(url: String, result: MediaInspection) {
        rows.remove(key(url)); rows[key(url)] = Entry(SystemClock.elapsedRealtime(), result.route, result.raw.toString())
        while (rows.size > 8) rows.remove(rows.keys.first())
    }
    @Synchronized fun invalidate(url: String) { rows.remove(key(url)) }
    fun save(context: Context, raw: JSONObject): String {
        val folder = File(context.cacheDir, "preview-media").apply { mkdirs() }
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val key = UUID.randomUUID().toString()
        val temp = File(folder, "$key.tmp"); temp.writeText(raw.toString())
        check(temp.renameTo(File(folder, "$key.json"))) { "Could not save video formats" }
        return key
    }
    fun file(context: Context, key: String): File? = if (!keyPattern.matches(key)) null else
        File(context.cacheDir, "preview-media/$key.json").takeIf { it.isFile && it.length() in 1..8_000_000 && System.currentTimeMillis() - it.lastModified() < 20 * 60 * 1000L }
    fun remove(context: Context, key: String) { if (keyPattern.matches(key)) File(context.cacheDir, "preview-media/$key.json").delete() }
    @Synchronized fun clear() { rows.clear() }
}
