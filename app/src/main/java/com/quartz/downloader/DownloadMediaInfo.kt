package com.quartz.downloader

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Transfers the current preview's formats to its chosen download, never to another preview. */
object DownloadMediaInfo {
    private val keyPattern = Regex("[a-f0-9-]{36}")
    fun save(context: Context, raw: JSONObject): String {
        val folder = File(context.cacheDir, "preview-media").apply { mkdirs() }
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val key = UUID.randomUUID().toString()
        val temp = File(folder, "$key.tmp"); temp.writeText(raw.toString())
        check(temp.renameTo(File(folder, "$key.json"))) { "Could not save video formats" }
        return key
    }
    fun file(context: Context, key: String): File? = if (!keyPattern.matches(key)) null else
        File(context.cacheDir, "preview-media/$key.json").takeIf { it.isFile && it.length() in 1..8_000_000 && System.currentTimeMillis() - it.lastModified() < 20 * 60 * 1000L }
    fun remove(context: Context, key: String) { if (keyPattern.matches(key)) File(context.cacheDir, "preview-media/$key.json").delete() }
}
