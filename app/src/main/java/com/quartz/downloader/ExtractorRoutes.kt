package com.quartz.downloader

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import java.io.File

object ExtractorRoutes {
    const val DEFAULT = "default"
    const val TWITTER_PUBLIC = "twitter-syndication"
    const val YOUTUBE_ANDROID = "youtube-android"
    const val X_HINT = "X did not reveal this video. Sign in to X in Settings, then preview the link again. The post must be available to your account."

    fun candidates(url: String, preferred: String = DEFAULT): List<String> {
        val routes = when {
            UrlPolicy.isTwitter(url) -> listOf(DEFAULT, TWITTER_PUBLIC)
            UrlPolicy.isYoutube(url) -> listOf(DEFAULT, YOUTUBE_ANDROID)
            else -> listOf(DEFAULT)
        }
        return if (preferred in routes) listOf(preferred) + routes.filter { it != preferred } else routes
    }

    fun configure(request: YoutubeDLRequest, url: String, route: String, cookies: File?, userAgent: String = ""): YoutubeDLRequest {
        request.addOption("--no-playlist").addOption("--socket-timeout", 30)
        if (UrlPolicy.isTwitter(url)) request.addOption("--playlist-items", "1")
        if (UrlPolicy.isFacebook(url)) {
            if (userAgent.isNotBlank()) request.addOption("--user-agent", userAgent)
            request.addOption("--referer", url)
        }
        when {
            route == TWITTER_PUBLIC && UrlPolicy.isTwitter(url) -> request.addOption("--extractor-args", "twitter:api=syndication")
            route == YOUTUBE_ANDROID && UrlPolicy.isYoutube(url) -> request.addOption("--extractor-args", "youtube:player_client=android")
        }
        // The public endpoint must be tried without an expired account cookie forcing the authenticated API.
        if (route != TWITTER_PUBLIC) cookies?.let { request.addOption("--cookies", it.absolutePath) }
        return request
    }

    fun inspect(url: String, cookies: File?, userAgent: String = ""): Pair<VideoInfo, String> {
        var lastError: Exception? = null
        for (route in candidates(url)) {
            try { return YoutubeDL.getInfo(configure(YoutubeDLRequest(url), url, route, cookies, userAgent)) to route }
            catch (error: Exception) {
                if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("This website did not expose a video.")
    }
}
