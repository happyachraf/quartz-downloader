package com.quartz.downloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Reuses successful searches for the same recording across playlist imports. */
object SpotifyMatchCache {
    private const val MAX_AGE = 7 * 24 * 60 * 60 * 1000L
    private fun file(context: Context, track: JSONObject): File {
        fun normalized(value: String) = value.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
        val artists = track.optJSONArray("artists") ?: JSONArray()
        val identity = JSONArray().put(track.optString("uri")).put(normalized(track.optString("name")))
            .put(JSONArray((0 until artists.length()).map { normalized(artists.optString(it)) }))
            .put(track.optDouble("duration")).toString()
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "spotify-match-cache-v1/$key.json")
    }
    @Synchronized fun get(context: Context, track: JSONObject): JSONArray? {
        val file = file(context, track)
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() !in 0..MAX_AGE) { file.delete(); return null }
        return try { JSONArray(file.readText()).takeIf { it.length() > 0 } } catch (_: Exception) { file.delete(); null }
    }
    @Synchronized fun save(context: Context, track: JSONObject, results: JSONArray) {
        if (results.length() == 0) return
        val file = file(context, track); file.parentFile!!.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(results.toString())
        check(temp.renameTo(file)) { "Could not save search cache" }
        val files = file.parentFile!!.listFiles().orEmpty().filter { it.extension == "json" }
        if (files.size > 1000) files.sortedByDescending { it.lastModified() }.drop(1000).forEach { it.delete() }
    }
    @Synchronized fun invalidate(context: Context, track: JSONObject) { file(context, track).delete() }
}
