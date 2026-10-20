package com.quartz.downloader

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.io.ByteArrayOutputStream

/** Reads public playlist metadata through the same guest session used by Spotify's web player. */
object SpotifyCatalog {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private data class Session(val token: String, val client: String, val version: String, val hash: String, val expires: Long)
    private var session: Session? = null

    fun normalize(raw: String): String {
        val uri = android.net.Uri.parse(UrlPolicy.normalize(raw))
        require(uri.host == "open.spotify.com") { "Paste a Spotify playlist link." }
        val id = Regex("^/(?:intl-[a-z]{2}/)?playlist/([A-Za-z0-9]{22})/?$").find(uri.path.orEmpty())?.groupValues?.get(1)
            ?: throw IllegalArgumentException("This section accepts Spotify playlist links.")
        return "https://open.spotify.com/playlist/$id"
    }

    private fun http(url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): String {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", UA)
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Spotify response $code")
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 12 * 1024 * 1024) { "Spotify returned too much data." }
                    output.write(buffer, 0, count)
                }
                android.util.Log.d("QuartzSpotify", "${URL(url).host}${URL(url).path}: $code, ${output.size()} bytes")
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }

    private fun script(html: String, id: String): String = Regex("""<script[^>]*id="$id"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.get(1) ?: run {
            android.util.Log.w("QuartzSpotify", "Missing public page field $id, ${html.length} characters")
            throw IllegalStateException("Spotify did not expose playlist data.")
        }

    private fun jsString(raw: String): String = Regex("""\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|.)""").replace(raw) {
        val value = it.groupValues[1]
        when { value.startsWith("u") || value.startsWith("x") -> value.drop(1).toInt(16).toChar().toString()
            value == "n" -> "\n"; value == "r" -> "\r"; value == "t" -> "\t"; else -> value }
    }

    private fun otp(key: ByteArray, seconds: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(seconds / 30).array())
        val offset = digest.last().toInt() and 15
        val number = ByteBuffer.wrap(digest, offset, 4).int and 0x7fffffff
        return (number % 1_000_000).toString().padStart(6, '0')
    }

    private fun query(values: Map<String, String>) = values.entries.joinToString("&") { (key, value) ->
        "$key=${URLEncoder.encode(value, "UTF-8")}" }

    @Synchronized private fun guest(html: String): Session {
        session?.takeIf { it.expires > System.currentTimeMillis() + 60_000 }?.let { return it }
        val config = JSONObject(String(Base64.decode(script(html, "appServerConfig"), Base64.DEFAULT), Charsets.UTF_8))
        val jsUrl = Regex("""<script[^>]*src="(https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[^"]+\.js)"""")
            .find(html)?.groupValues?.get(1) ?: throw IllegalStateException("Spotify's web player changed. Try again later.")
        val js = http(jsUrl)
        val source = Regex("""secret:(['"])((?:\\.|(?!\1).)*)\1,version:(\d+)""").find(js)
            ?: throw IllegalStateException("Spotify's guest session changed. Try again later.")
        val secret = jsString(source.groupValues[2])
        val key = secret.mapIndexed { index, c -> (c.code xor ((index % 33) + 9)).toString() }.joinToString("").toByteArray(Charsets.UTF_8)
        val now = System.currentTimeMillis() / 1000
        val params = query(mapOf("reason" to "init", "productType" to "web-player", "totp" to otp(key, now),
            "totpServer" to otp(key, config.optLong("serverTime", now)), "totpVer" to source.groupValues[3]))
        val token = JSONObject(http("https://open.spotify.com/api/token?$params"))
        val version = config.getString("clientVersion")
        val clientData = JSONObject().put("client_version", version).put("client_id", token.getString("clientId"))
            .put("js_sdk_data", JSONObject().put("device_brand", "unknown").put("device_model", "unknown")
                .put("os", "windows").put("os_version", "NT 10.0").put("device_id", UUID.randomUUID().toString()).put("device_type", "computer"))
        val client = JSONObject(http("https://clienttoken.spotify.com/v1/clienttoken", JSONObject().put("client_data", clientData)))
            .getJSONObject("granted_token").getString("token")
        val hash = Regex(""""fetchPlaylist","query","([a-f0-9]{64})"""").find(js)?.groupValues?.get(1)
            ?: throw IllegalStateException("Spotify's playlist reader changed. Try again later.")
        return Session(token.getString("accessToken"), client, version, hash,
            minOf(token.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 900_000), System.currentTimeMillis() + 900_000))
            .also { session = it }
    }

    private fun page(id: String, offset: Int, access: Session): JSONObject {
        val variables = JSONObject().put("uri", "spotify:playlist:$id").put("offset", offset).put("limit", 100).put("enableWatchFeedEntrypoint", false)
        val extensions = JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", access.hash))
        val params = query(mapOf("operationName" to "fetchPlaylist", "variables" to variables.toString(), "extensions" to extensions.toString()))
        val result = JSONObject(http("https://api-partner.spotify.com/pathfinder/v1/query?$params", headers = mapOf(
            "Authorization" to "Bearer ${access.token}", "Client-Token" to access.client, "Spotify-App-Version" to access.version)))
        return result.optJSONObject("data")?.optJSONObject("playlistV2")?.takeIf { it.has("content") }
            ?: throw IllegalStateException("This playlist is unavailable. Check that it is public and still opens in Spotify.")
    }

    fun load(raw: String): JSONObject {
        val url = normalize(raw)
        val id = url.substringAfterLast('/')
        val html = http(url)
        var access = guest(html)
        var first: JSONObject
        try { first = page(id, 0, access) }
        catch (error: Exception) { session = null; access = guest(http(url)); first = page(id, 0, access) }
        val total = first.getJSONObject("content").getInt("totalCount")
        require(total <= 1000) { "This playlist has over 1,000 tracks. Try a smaller playlist." }
        val rows = JSONArray()
        var loaded = 0
        var unavailable = 0
        var currentPage = first
        while (loaded < total) {
            val content = currentPage.getJSONObject("content")
            val items = content.getJSONArray("items")
            require(items.length() > 0) { "Spotify returned an incomplete playlist. Please load it again." }
            for (i in 0 until items.length()) {
                val track = items.getJSONObject(i).optJSONObject("itemV2")?.optJSONObject("data")
                val uri = track?.optString("uri").orEmpty()
                if (!Regex("spotify:track:[A-Za-z0-9]{22}").matches(uri) || track?.optString("name").isNullOrBlank()) { unavailable++; continue }
                val artists = track!!.optJSONObject("artists")?.optJSONArray("items") ?: JSONArray()
                val names = (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optJSONObject("profile")?.optString("name")?.takeIf(String::isNotBlank) }
                val album=track.optJSONObject("albumOfTrack")
                val sources=album?.optJSONObject("coverArt")?.optJSONArray("sources") ?: JSONArray()
                val cover=(0 until sources.length()).mapNotNull{sources.optJSONObject(it)}.maxByOrNull{it.optInt("width") }?.optString("url").orEmpty()
                rows.put(JSONObject().put("key", (loaded + i).toString()).put("uri", uri).put("name", track.getString("name"))
                    .put("artists", JSONArray(names)).put("duration", (track.optJSONObject("trackDuration") ?: track.optJSONObject("duration"))?.optLong("totalMilliseconds", 0)?.div(1000.0) ?: 0)
                    .put("thumbnail", cover).put("album",album?.optString("name").orEmpty()).put("status", "pending").put("candidates", JSONArray()))
            }
            loaded += items.length()
            if (loaded < total) currentPage = page(id, loaded, access)
        }
        require(loaded == total) { "Spotify's playlist changed while loading. Please load it again." }
        val cover = first.optJSONObject("images")?.optJSONArray("items")?.optJSONObject(0)?.optJSONArray("sources")?.optJSONObject(0)?.optString("url").orEmpty()
        return JSONObject().put("url", url).put("title", first.optString("name", "Spotify playlist"))
            .put("cover", cover).put("total", total).put("unavailable", unavailable).put("tracks", rows).put("status", "ready")
    }
}
