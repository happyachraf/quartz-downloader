package com.quartz.downloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Each scope has explicit semantics; clearing history never goes through file deletion. */
object BulkActions {
    fun apply(context: Context, scope: String, keys: Set<String>, deleteFiles: Boolean, delete: (String) -> Boolean): JSONObject {
        require(keys.size in 1..10000)
        val stored = DownloadStore.all(context)
        val jobs = (0 until stored.length()).map { stored.getJSONObject(it) }
        var done = 0; var failed = 0
        when (scope) {
            "musicPlaylists" -> {
                val rows = jobs.filter { it.optString("state") == "complete" && it.optString("mode") == "audio" && !it.optBoolean("musicPlaylistHidden") && it.optString("spotifyPlaylist").ifBlank { "Other audio" } in keys }
                if (deleteFiles) for (row in rows) { if (delete(row.getString("id"))) done++ else failed++ }
                else {
                    DownloadStore.updateMany(context, rows.map { it.getString("id") }.toSet()) { it.put("musicPlaylistHidden", true) }
                    done = rows.map { it.optString("spotifyPlaylist").ifBlank { "Other audio" } }.distinct().size
                }
            }
            "musicSongs" -> {
                require(deleteFiles)
                for (row in jobs.filter { it.optString("id") in keys && it.optString("state") == "complete" && it.optString("mode") == "audio" }) {
                    if (delete(row.getString("id"))) done++ else failed++
                }
            }
            "downloads" -> {
                val selected = jobs.filter { it.optString("id") in keys }
                val hide = selected.filter { !deleteFiles && it.optString("state") in listOf("complete", "failed", "cancelled") }.map { it.getString("id") }.toSet()
                DownloadStore.updateMany(context, hide) { it.put("historyHidden", true) }; done += hide.size
                for (row in selected.filter { it.optString("id") !in hide }) { if (delete(row.getString("id"))) done++ else failed++ }
            }
            "spotifyHistory" -> for (url in keys) { if (PlaylistStore.clearHistory(context, url)) done++ else failed++ }
            "spotifyTracks" -> {
                // URL and track IDs are captured together, never from a later screen selection.
                error("Use removeTracks with an explicit playlist URL")
            }
            else -> error("Unknown selection list")
        }
        return JSONObject().put("done", done).put("failed", failed)
    }
    fun removeTracks(context: Context, url: String, keys: Set<String>): JSONObject {
        require(keys.size in 1..10000)
        if (PlaylistStore.getFor(context, url) == null) return JSONObject().put("done", 0).put("failed", keys.size)
        var removed = 0
        PlaylistStore.updateFor(context, url) { state ->
            val rows = state.optJSONArray("tracks") ?: JSONArray()
            val kept = JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.optString("key") in keys) removed++ else kept.put(row)
            }
            state.put("tracks", kept)
        }
        return JSONObject().put("done", removed).put("failed", 0)
    }
}
