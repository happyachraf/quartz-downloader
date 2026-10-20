package com.quartz.downloader

import android.content.Context
import org.json.JSONObject
import java.io.File

object PlaylistStore {
    private var cachedPath = ""
    private var cachedStamp = -1L
    private var cachedSize = -1L
    private var cachedLibrary: JSONObject? = null
    private fun read(context: Context): JSONObject {
        val file = File(context.filesDir, "spotify-library.json")
        if (file.exists()) {
            if (cachedPath != file.absolutePath || cachedStamp != file.lastModified() || cachedSize != file.length() || cachedLibrary == null) {
                cachedLibrary = JSONObject(file.readText()); cachedPath = file.absolutePath
                cachedStamp = file.lastModified(); cachedSize = file.length()
            }
            return cachedLibrary!!
        }
        cachedLibrary = null
        val old = File(context.filesDir, "spotify-playlist.json")
        val library = JSONObject().put("active", "").put("playlists", JSONObject())
        if (old.exists()) {
            val row = JSONObject(old.readText())
            val url = row.optString("url")
            if (url.isNotBlank()) { library.put("active", url); library.getJSONObject("playlists").put(url, row) }
        }
        return library
    }
    private fun write(context: Context, library: JSONObject) {
        val temp = File(context.filesDir, "spotify-library.tmp")
        java.io.FileOutputStream(temp).use { it.write(library.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temp.renameTo(File(context.filesDir, "spotify-library.json"))) { "Could not save playlist progress." }
        val file = File(context.filesDir, "spotify-library.json")
        cachedLibrary = library; cachedPath = file.absolutePath; cachedStamp = file.lastModified(); cachedSize = file.length()
    }
    @Synchronized fun get(context: Context): JSONObject {
        val library = read(context)
        return JSONObject((library.getJSONObject("playlists").optJSONObject(library.optString("active")) ?: JSONObject().put("status", "empty")).toString())
    }
    @Synchronized fun getFor(context: Context, url: String): JSONObject? = read(context).getJSONObject("playlists").optJSONObject(url)?.let { JSONObject(it.toString()) }
    @Synchronized fun all(context: Context): List<JSONObject> {
        val rows = read(context).getJSONObject("playlists")
        return rows.keys().asSequence().map { JSONObject(rows.getJSONObject(it).toString()) }.toList()
    }
    @Synchronized fun summaries(context: Context): org.json.JSONArray = org.json.JSONArray(read(context).getJSONObject("playlists").let { rows -> rows.keys().asSequence().map { rows.getJSONObject(it) }.toList() }.filter { !it.optBoolean("historyHidden") }.map { row ->
        val tracks = row.optJSONArray("tracks") ?: org.json.JSONArray()
        JSONObject().put("url", row.optString("url")).put("title", row.optString("title", "Loading playlist…"))
            .put("cover", row.optString("cover")).put("status", row.optString("status"))
            .put("count", tracks.length()).put("searched", (0 until tracks.length()).count { tracks.getJSONObject(it).optJSONArray("candidates")?.length()?.let { n -> n > 0 } == true || tracks.getJSONObject(it).optBoolean("searched") })
            .put("message", row.optString("message"))
    })
    @Synchronized fun select(context: Context, url: String) {
        val library = read(context)
        if (library.getJSONObject("playlists").has(url)) {
            library.getJSONObject("playlists").getJSONObject(url).put("historyHidden", false)
            library.put("active", url); write(context, library)
        }
    }
    /** Clears only the Spotify workspace's history card; download/music records are separate. */
    @Synchronized fun clearHistory(context: Context, url: String): Boolean {
        val library = read(context)
        val row = library.getJSONObject("playlists").optJSONObject(url) ?: return false
        if (row.optBoolean("wantMatching") || row.optString("status") in listOf("loading", "matching", "queued", "waiting")) return false
        row.put("historyHidden", true)
        if (library.optString("active") == url) library.put("active", "")
        write(context, library)
        return true
    }
    @Synchronized fun save(context: Context, state: JSONObject) {
        val library = read(context)
        val url = state.optString("url", library.optString("active"))
        if (url.isBlank()) return
        state.put("url", url)
        library.getJSONObject("playlists").put(url, JSONObject(state.toString()))
        library.put("active", url)
        write(context, library)
    }
    @Synchronized fun update(context: Context, change: (JSONObject) -> Unit): JSONObject = updateFor(context, get(context).optString("url"), change)
    @Synchronized fun updateFor(context: Context, url: String, change: (JSONObject) -> Unit): JSONObject {
        val library = read(context)
        val state = library.getJSONObject("playlists").optJSONObject(url) ?: JSONObject().put("url", url)
        change(state)
        if (url.isNotBlank()) { library.getJSONObject("playlists").put(url, state); write(context, library) }
        return JSONObject(state.toString())
    }
    fun recover(context: Context) { /* Workers own recovery, never the screen. */ }
}
