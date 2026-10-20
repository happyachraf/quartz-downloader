package com.quartz.downloader

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File
import java.util.UUID
import org.json.JSONObject

object BrowserCookies {
    private val activeFiles=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Synchronized fun release(file:File?){if(file==null)return;file.delete();activeFiles.remove(file.absolutePath)}
    @Synchronized fun cleanInactive(context:Context){File(context.cacheDir,"sessions").listFiles()?.filter{it.absolutePath !in activeFiles}?.forEach{it.delete()}}
    private fun cookies(domain: String, extraUrl: String? = null): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (source in listOfNotNull("https://$domain/", "https://www.$domain/", extraUrl)) {
            for (cookie in CookieManager.getInstance().getCookie(source).orEmpty().split(';')) {
                val pair = cookie.trim().split('=', limit = 2)
                if (pair.size != 2 || pair[0].isBlank() || pair.any { it.contains('\n') || it.contains('\r') || it.contains('\t') }) continue
                values[pair[0]] = pair[1]
            }
        }
        return values
    }

    fun status(): JSONObject {
        val facebook = cookies("facebook.com")
        return JSONObject().put("facebook", facebook.containsKey("c_user") && facebook.containsKey("xs"))
            .put("x", cookies("x.com").containsKey("auth_token") || cookies("twitter.com").containsKey("auth_token"))
            .put("instagram", !cookies("instagram.com")["sessionid"].isNullOrBlank())
    }

    @Synchronized fun create(context: Context, url: String): File? {
        val host = Uri.parse(url).host?.lowercase() ?: return null
        val domains = when {
            host == "instagram.com" || host.endsWith(".instagram.com") -> listOf("instagram.com")
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" -> listOf("facebook.com")
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> listOf("tiktok.com")
            host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com") -> listOf("x.com", "twitter.com")
            else -> return null
        }
        val rows = domains.flatMap { domain ->
            val ownUrl = url.takeIf { host == domain || host.endsWith(".$domain") }
            cookies(domain, ownUrl).map { (name, value) -> ".$domain\tTRUE\t/\tTRUE\t0\t$name\t$value" }
        }
        if (rows.isEmpty()) return null
        val directory = File(context.cacheDir, "sessions").apply { mkdirs() }
        return File(directory, "${UUID.randomUUID()}.txt").apply {
            writeText("# Netscape HTTP Cookie File\n" + rows.joinToString("\n") + "\n")
            activeFiles.add(absolutePath)
        }
    }
}
