package com.quartz.downloader

import com.yausername.youtubedl_android.mapper.VideoFormat

data class VideoQuality(val height: Int, val width: Int, val fps: Int)

object Quality {
    private fun bytes(format: VideoFormat, duration: Int): Long =
        format.fileSize.takeIf { it > 0 } ?: format.fileSizeApproximate.takeIf { it > 0 }
        ?: (format.tbr.toLong() * duration * 1000 / 8)

    fun audioBytes(formats: List<VideoFormat>?, duration: Int): Long = formats.orEmpty()
        .filter { it.acodec != null && it.acodec != "none" && it.vcodec == "none" }
        .maxOfOrNull { bytes(it, duration) } ?: 0

    fun estimatedBytes(formats: List<VideoFormat>?, height: Int, duration: Int, noWatermark: Boolean): Long {
        val format = formats.orEmpty().filter { it.height == height && it.vcodec != "none" && (!noWatermark || cleanTikTok(it)) }
            .maxWithOrNull(compareBy<VideoFormat> { it.fps }.thenBy { it.tbr }) ?: return 0
        val video = bytes(format, duration)
        val audio = if (format.acodec == "none") audioBytes(formats, duration) else 0
        return if (video > 0 && (format.acodec != "none" || audio > 0)) video + audio else 0
    }
    fun offered(formats: List<VideoFormat>?, noWatermark: Boolean = false): List<VideoQuality> {
        val bestByHeight = linkedMapOf<Int, VideoQuality>()
        for (format in formats.orEmpty()) {
            val height = format.height
            if (height !in 1..16384 || format.vcodec == "none" || format.ext == "mhtml") continue
            if (noWatermark && !cleanTikTok(format)) continue
            val candidate = VideoQuality(height, format.width, format.fps)
            val current = bestByHeight[height]
            if (current == null || candidate.fps > current.fps) bestByHeight[height] = candidate
        }
        return bestByHeight.values.sortedByDescending { it.height }
    }

    private fun cleanTikTok(format: VideoFormat): Boolean =
        format.height > 0 && format.vcodec != "none" && format.acodec != "none" &&
        format.formatId != "download" && format.formatNote?.contains("watermark", true) != true &&
        format.formatId?.matches(Regex("[A-Za-z0-9_.-]+")) == true

    fun tikTokChoices(formats: List<VideoFormat>?): Map<String, String> {
        val clean = formats.orEmpty().filter(::cleanTikTok).sortedWith(
            compareByDescending<VideoFormat> { it.height }.thenByDescending { it.fps }
                .thenByDescending { it.tbr }.thenByDescending { it.formatId })
        if (clean.isEmpty()) return emptyMap()
        val choices = linkedMapOf("best" to clean.take(30).joinToString("/") { it.formatId!! })
        for ((height, rows) in clean.groupBy { it.height }) {
            choices[height.toString()] = rows.take(30).joinToString("/") { it.formatId!! }
        }
        return choices
    }

    fun selector(choice: String): String {
        if (choice == "best") return "bv*+ba/b"
        val height = choice.toIntOrNull()
        require(height != null && height in 1..16384) { "Choose an available quality." }
        return "bv*[height=$height]+ba/b[height=$height]"
    }
}
