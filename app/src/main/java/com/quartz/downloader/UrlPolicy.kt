package com.quartz.downloader

import android.net.Uri

object UrlPolicy {
    fun normalize(raw: String): String {
        var value = raw.trim().removePrefix("<").removeSuffix(">")
        if (value.startsWith("youtu.be/", true) || value.startsWith("www.", true)) value = "https://$value"
        require(value.isNotEmpty() && value.length <= 8192 && value.none { it.isWhitespace() || it.code < 32 }) {
            "Paste a complete website link."
        }
        val uri = Uri.parse(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Paste a secure https:// link."
        }
        return uri.toString()
    }

    fun isYoutube(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }

    fun isTikTok(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: return false
        return host == "tiktok.com" || host.endsWith(".tiktok.com")
    }

    fun isSpotify(url: String): Boolean = Uri.parse(url).host?.lowercase() == "open.spotify.com"

    fun isTwitter(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com")
    }
}
