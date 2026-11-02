package com.quartz.downloader

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Shared queue rules for single songs and playlist selections. */
object SpotifyDownloads {
    data class Queued(val jobs: List<JSONObject>, val assigned: Map<String, String>)

    @Synchronized fun queue(context: Context, tracks: List<JSONObject>, audioFormat: String, playlist: String = ""): Queued {
        require(audioFormat in listOf("original", "mp3"))
        val existing = DownloadStore.all(context)
        val jobs = mutableListOf<JSONObject>()
        val assigned = mutableMapOf<String, String>()
        for (row in tracks) {
            val candidates = row.optJSONArray("candidates") ?: continue
            val selected = (0 until candidates.length()).map { candidates.getJSONObject(it) }
                .find { it.optString("url") == row.optString("selectedUrl") } ?: continue
            if (!row.optBoolean("confirmed") && (selected.optDouble("score") < .82 || selected.optDouble("duration") <= 0)) continue
            val previous = (jobs + (0 until existing.length()).map { existing.getJSONObject(it) }).find {
                it.optString("spotifyTrackUri") == row.optString("uri") && it.optString("audioFormat", "mp3") == audioFormat &&
                    (audioFormat == "original" || it.optInt("audioQuality") == 320) &&
                    it.optString("state") in listOf("complete", "queued", "preparing", "downloading", "processing", "saving", "paused", "waiting")
            }
            val key = row.getString("key")
            if (previous != null) { assigned[key] = previous.getString("id"); continue }
            val id = UUID.randomUUID().toString()
            val artists = row.getJSONArray("artists")
            val artistText = (0 until artists.length()).joinToString(", ") { artists.getString(it) }
            jobs.add(JSONObject().put("id", id).put("url", selected.getString("url")).put("mode", "audio").put("quality", "best")
                .put("audioFormat", audioFormat).put("audioQuality", if (audioFormat == "mp3") 320 else 0)
                .put("title", "$artistText — ${row.getString("name")}").put("spotifyTitle", row.getString("name"))
                .put("spotifyArtists", artistText).put("spotifyTrackUri", row.getString("uri")).put("spotifyPlaylist", playlist)
                .put("thumbnail", row.optString("thumbnail")).put("duration", row.optDouble("duration")).put("spotifyAlbum", row.optString("album"))
                .put("sourceUrl", "https://open.spotify.com/track/${row.getString("uri").substringAfterLast(':')}")
                .put("extractorRoute", ExtractorRoutes.DEFAULT).put("type", "media").put("state", "queued").put("progress", 0)
                .put("message", "Waiting to download matched audio").put("createdAt", System.currentTimeMillis()))
            assigned[key] = id
        }
        DownloadStore.addAll(context, jobs)
        return Queued(jobs, assigned)
    }
}
