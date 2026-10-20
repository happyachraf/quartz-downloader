package com.quartz.downloader

import android.app.DownloadManager
import android.content.Context
import android.content.Context.DOWNLOAD_SERVICE
import org.json.JSONArray
import org.json.JSONObject

object DownloadStore {
    private const val PREFS = "quartz_downloads"
    private const val KEY = "jobs"
    private var cachedText: String? = null
    private var cachedRows = JSONArray()

    @Synchronized fun all(context: Context): JSONArray {
        refreshDirect(context)
        return JSONArray(read(context).toString())
    }

    @Synchronized fun get(context: Context, id: String): JSONObject? {
        val rows = read(context)
        for (i in 0 until rows.length()) if (rows.getJSONObject(i).optString("id") == id) return JSONObject(rows.getJSONObject(i).toString())
        return null
    }

    @Synchronized fun add(context: Context, job: JSONObject) {
        addAll(context, listOf(job))
    }

    @Synchronized fun addAll(context: Context, jobs: List<JSONObject>) {
        val rows = read(context)
        val next = JSONArray(JSONArray(jobs).toString())
        var keptFinished = 0
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            // Saved files remain in Music and Downloads across large playlist imports.
            if (row.optString("state") in listOf("complete", "queued", "preparing", "downloading", "processing", "saving", "paused", "waiting") || keptFinished++ < 150) next.put(row)
        }
        write(context, next)
    }

    @Synchronized fun update(context: Context, id: String, changes: JSONObject) {
        val rows = read(context)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("id") != id) continue
            val nextState = changes.optString("state")
            // A late native callback must not undo a user's Pause/Cancel.
            if (row.optString("state") in listOf("paused", "cancelled") && nextState in listOf("preparing", "downloading", "processing", "saving", "waiting", "complete", "failed")) return
            if (row.optString("state") == "complete" && nextState in listOf("paused", "cancelled")) return
            for (key in changes.keys()) row.put(key, changes.get(key))
            write(context, rows, nextState == "downloading" || nextState == "processing")
            return
        }
    }

    @Synchronized fun remove(context: Context, id: String) {
        val rows = read(context)
        val next = JSONArray()
        for (i in 0 until rows.length()) if (rows.getJSONObject(i).optString("id") != id) next.put(rows.getJSONObject(i))
        write(context, next)
    }

    @Synchronized fun updateMany(context: Context, ids: Set<String>, change: (JSONObject) -> Unit) {
        val rows = read(context)
        for (i in 0 until rows.length()) if (rows.getJSONObject(i).optString("id") in ids) change(rows.getJSONObject(i))
        write(context, rows)
    }

    @Synchronized fun recoverInterrupted(context: Context) {
        val rows = read(context)
        var changed = false
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("type") == "direct") continue
            if (row.optString("state") == "failed" && row.optString("message").startsWith("Download was interrupted.")) {
                row.put("state", "paused").put("message", "Previous download interrupted. Tap Resume to retry.")
                changed = true
                continue
            }
            if (row.optString("state") !in listOf("queued", "preparing", "downloading", "processing", "saving")) continue
            row.put("state", "queued").put("message", "Restored. Resuming saved progress…")
            changed = true
        }
        if (changed) write(context, rows)
    }

    private fun read(context: Context): JSONArray {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        if (text != cachedText) {
            cachedRows = try { JSONArray(text) } catch (_: Exception) { JSONArray() }
            cachedText = text
        }
        return cachedRows
    }

    private fun write(context: Context, rows: JSONArray, progressOnly: Boolean = false) {
        val text = rows.toString()
        cachedRows = rows; cachedText = text
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text)
        if (progressOnly) editor.apply() else editor.commit()
    }

    private fun refreshDirect(context: Context) {
        val rows = read(context)
        val manager = context.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        var changed = false
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.optString("type") != "direct" || row.optString("state") in listOf("complete", "failed")) continue
            val dmId = row.optLong("dmId", -1)
            if (dmId < 0) continue
            manager.query(DownloadManager.Query().setFilterById(dmId))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                row.put("progress", if (total > 0) (bytes * 100 / total).toInt() else 0)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        row.put("state", "complete")
                        row.put("progress", 100)
                        row.put("uri", manager.getUriForDownloadedFile(dmId)?.toString() ?: "")
                    }
                    DownloadManager.STATUS_FAILED -> { row.put("state", "failed"); row.put("message", "The website could not finish this download.") }
                    else -> row.put("state", "downloading")
                }
                changed = true
            }
        }
        if (changed) write(context, rows)
    }
}
