package com.quartz.downloader

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import java.io.File
import org.json.JSONObject

data class MediaInspection(val info: VideoInfo, val route: String, val raw: JSONObject)

object ExtractorRoutes {
    const val DEFAULT = "default"
    const val TWITTER_PUBLIC = "twitter-syndication"
    const val YOUTUBE_ANDROID = "youtube-android"
    const val YOUTUBE_FAST = "youtube-visionos"
    const val YOUTUBE_EMBEDDED = "youtube-embedded"
    const val X_HINT = "X did not reveal this video. Sign in to X in Settings, then preview the link again. The post must be available to your account."

    fun candidates(url: String, preferred: String = ""): List<String> {
        val routes = when {
            UrlPolicy.isTwitter(url) -> listOf(DEFAULT, TWITTER_PUBLIC)
            UrlPolicy.isYoutube(url) -> listOf(YOUTUBE_FAST, YOUTUBE_EMBEDDED, DEFAULT, YOUTUBE_ANDROID)
            UrlPolicy.isFacebook(url) -> listOf(FacebookMedia.ROUTE)
            else -> listOf(DEFAULT)
        }
        return if (preferred in routes && !(UrlPolicy.isYoutube(url) && preferred == DEFAULT)) listOf(preferred) + routes.filter { it != preferred } else routes
    }

    fun configure(request: YoutubeDLRequest, url: String, route: String, cookies: File?, userAgent: String = ""): YoutubeDLRequest {
        request.addOption("--no-playlist").addOption("--socket-timeout", 30)
        if (UrlPolicy.isTwitter(url)) request.addOption("--playlist-items", "1")
        if (UrlPolicy.isFacebook(url)) {
            if (userAgent.isNotBlank()) request.addOption("--user-agent", userAgent)
            request.addOption("--referer", url)
        }
        when {
            route == YOUTUBE_FAST && UrlPolicy.isYoutube(url) -> request.addOption("--extractor-args", "youtube:player_client=visionos")
            route == YOUTUBE_EMBEDDED && UrlPolicy.isYoutube(url) -> request.addOption("--extractor-args", "youtube:player_client=web_embedded")
            route == TWITTER_PUBLIC && UrlPolicy.isTwitter(url) -> request.addOption("--extractor-args", "twitter:api=syndication")
            route == YOUTUBE_ANDROID && UrlPolicy.isYoutube(url) -> request.addOption("--extractor-args", "youtube:player_client=android")
        }
        // The public endpoint must be tried without an expired account cookie forcing the authenticated API.
        if (route != TWITTER_PUBLIC) cookies?.let { request.addOption("--cookies", it.absolutePath) }
        return request
    }

    fun inspect(url: String, cookies: File?, userAgent: String = "", processId: String? = null,
                allowed: () -> Boolean = { true }, status: (String) -> Unit = {}): MediaInspection {
        if (UrlPolicy.isYoutube(url)) PreviewCache.get(url)?.let { return it }
        var lastError: Exception? = null
        var best: MediaInspection? = null
        val youtube = UrlPolicy.isYoutube(url)
        fun height(row: MediaInspection) = Quality.offered(row.info.formats).maxOfOrNull { minOf(it.width.takeIf { w -> w > 0 } ?: it.height, it.height) } ?: 0
        for (route in candidates(url).filter { !youtube || it != YOUTUBE_ANDROID }) {
            if (!allowed() || Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                if (best != null) status("Checking higher-quality YouTube streams…")
                val request = configure(YoutubeDLRequest(url), url, route, cookies, userAgent)
                    .addOption("--socket-timeout", 10).addOption("--retries", 1).addOption("--extractor-retries", 1)
                val raw = MediaEngine.inspect(request, processId)
                val mappedAt = android.os.SystemClock.elapsedRealtime()
                val next = MediaInspection(FacebookMedia.videoInfo(raw), route, raw)
                android.util.Log.d("QuartzLookup", "map=${android.os.SystemClock.elapsedRealtime()-mappedAt}ms route=$route")
                if (!allowed()) throw InterruptedException()
                if (!youtube) return next
                if (best == null || height(next) > height(best!!)) best = next
                // A successful 360p-only response is not evidence that HD is unavailable.
                if (height(next) >= 720) { PreviewCache.put(url, next); return next }
            }
            catch (error: Exception) {
                if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error
                lastError = error
            }
        }
        best?.let { return it }
        throw lastError ?: IllegalStateException("This website did not expose a video.")
    }
}
