package com.quartz.downloader

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject

object SpotifyMatcher {
    fun find(track: JSONObject, processId: String, context: android.content.Context? = null, allowed: () -> Boolean = { true }): JSONArray {
        if (!allowed() || Thread.currentThread().isInterrupted) throw InterruptedException()
        context?.let { SpotifyMatchCache.get(it, track)?.let { hit -> return hit } }
        val artists = track.getJSONArray("artists")
        val artist = artists.optString(0)
        val query = "$artist ${track.getString("name")} audio"
        val initial = search(track, query, processId)
        if (initial.optJSONObject(0)?.optDouble("score", 0.0)?.let { it >= .82 } == true) {
            if (allowed()) context?.let { SpotifyMatchCache.save(it, track, initial) }
            return initial
        }
        if (!allowed() || Thread.currentThread().isInterrupted) throw InterruptedException()
        val artistNames = (0 until artists.length()).joinToString(" ") { artists.optString(it) }
        val extra = search(track, "$artistNames ${track.getString("name")}", processId)
        val combined = (0 until initial.length()).map { initial.getJSONObject(it) } + (0 until extra.length()).map { extra.getJSONObject(it) }
        val result = JSONArray(combined.distinctBy { it.getString("id") }.sortedByDescending { it.optDouble("score") }.take(3))
        if (allowed()) context?.let { SpotifyMatchCache.save(it, track, result) }
        return result
    }

    private fun search(track: JSONObject, query: String, processId: String): JSONArray {
        val artist = track.getJSONArray("artists").optString(0)
        val request = YoutubeDLRequest("ytsearch5:$query").addOption("--flat-playlist").addOption("--dump-single-json")
            .addOption("--skip-download").addOption("--no-warnings").addOption("--socket-timeout", 20)
            .addOption("--retries", 1).addOption("--extractor-retries", 1)
        val response = MediaEngine.execute(request, processId)
        val entries = JSONObject(response.out).optJSONArray("entries") ?: JSONArray()
        val ranked = (0 until entries.length()).mapNotNull { index ->
            val candidate = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = candidate.optString("id")
            if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) return@mapNotNull null
            val title = candidate.optString("title")
            val channel = candidate.optString("channel", candidate.optString("uploader"))
            val duration = candidate.optDouble("duration", 0.0).takeIf { it.isFinite() } ?: 0.0
            val score = MatchRanker.score(track.getString("name"), artist, track.optDouble("duration"), title, channel, duration)
            JSONObject().put("url", "https://www.youtube.com/watch?v=$id").put("id", id).put("title", title)
                .put("channel", channel).put("duration", duration).put("score", score)
        }.filter { it.optDouble("score") >= .4 }.sortedByDescending { it.optDouble("score") }.take(3)
        return JSONArray(ranked)
    }
}
