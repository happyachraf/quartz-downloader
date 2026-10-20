package com.quartz.downloader

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object MatchRanker {
    private fun words(value: String): Set<String> = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(Regex("\\s+"))
        .filter { it.isNotBlank() }.toSet()

    fun score(name: String, artist: String, seconds: Double, title: String, channel: String, duration: Double): Double {
        val source = words(name).filter { it !in setOf("feat", "featuring", "ft", "remastered", "remaster") }.toSet()
        val candidate = words("$title $channel")
        val titleWords = words(title)
        val artistWords = words(artist)
        val titleScore = if (source.isEmpty()) 0.0 else source.count { it in titleWords }.toDouble() / source.size
        val artistScore = if (artistWords.isEmpty()) 0.0 else artistWords.count { it in candidate }.toDouble() / artistWords.size
        val difference = if (seconds > 0 && duration > 0) abs(seconds - duration) else Double.MAX_VALUE
        val durationScore = when { difference <= 6 -> 1.0; difference <= 15 -> .8; difference <= 30 -> .4; else -> 0.0 }
        var score = .55 * titleScore + .25 * artistScore + .20 * durationScore
        val versions = setOf("live", "remix", "cover", "karaoke", "slowed", "sped", "instrumental", "nightcore", "reaction", "tutorial")
        if ((titleWords intersect versions).any { it !in words(name) }) score -= .35
        if (artistScore < .5) score -= .25
        if (artistScore < .5 && source.size <= 2 && titleWords.size > source.size + artistWords.size + 5) score -= .25
        if (difference > maxOf(30.0, seconds * .15) && duration > 0 && seconds > 0) score -= .2
        return score.coerceIn(0.0, 1.0)
    }
}
