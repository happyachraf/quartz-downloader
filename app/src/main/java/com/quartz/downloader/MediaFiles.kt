package com.quartz.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

object MediaFiles {
    fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "opus" || ext == "ogg") return "audio/ogg"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) { "mp3" -> "audio/mpeg"; "mkv" -> "video/x-matroska"; else -> "application/octet-stream" }
    }

    fun publish(context: Context, source: File, jobId: String? = null): Uri {
        val resolver = context.contentResolver
        val savedUri = jobId?.let { DownloadStore.get(context, it)?.optString("publishUri") }?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        var reusable: Uri? = null
        if (savedUri != null) {
            try {
                resolver.query(savedUri, arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        if (cursor.getInt(0) == 0) return savedUri
                        reusable = savedUri
                    }
                }
            } catch (_: Exception) { }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, mime(source.name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Quartz Downloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = reusable ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create a file in Downloads.")
        if (jobId != null) DownloadStore.update(context, jobId, org.json.JSONObject().put("publishUri", uri.toString()))
        try {
            resolver.openOutputStream(uri)?.use { target -> source.inputStream().use { it.copyTo(target) } }
                ?: throw IllegalStateException("Could not write the downloaded file.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            if (jobId != null) DownloadStore.update(context, jobId, org.json.JSONObject().put("publishUri", ""))
            throw error
        }
    }
}
