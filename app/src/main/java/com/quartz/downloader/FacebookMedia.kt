package com.quartz.downloader

import android.content.Context
import android.net.Uri
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.youtubedl_android.mapper.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Only media delivery data is retained. Account cookies and page HTML are never stored here. */
object FacebookMedia {
    const val ROUTE = "facebook-browser-media"
    private val keyPattern = Regex("[a-f0-9-]{36}")
    fun safeUrl(raw: String): Boolean {
        val uri=Uri.parse(raw);val host=uri.host?.lowercase()?:return false
        return uri.scheme=="https"&&uri.userInfo==null&&listOf("fbcdn.net","fbsbx.com").any { host==it||host.endsWith(".$it") }
    }
    fun validate(raw: JSONObject?, origin: String): JSONObject? {
        if(raw==null||!UrlPolicy.isFacebook(origin))return null
        val id=raw.optString("id");if(!id.matches(Regex("[0-9]+")))return null
        val input=raw.optJSONArray("formats")?:return null
        val rows=JSONArray();val seen=hashSetOf<String>()
        val headers=JSONObject().put("User-Agent","facebookexternalhit/1.1").put("Referer",origin)
        for(i in 0 until minOf(input.length(),64)) {
            val f=input.optJSONObject(i)?:continue;val url=f.optString("url")
            if(!safeUrl(url)||!seen.add(url))continue
            val row=JSONObject().put("format_id",f.optString("format_id").takeIf{it.matches(Regex("[A-Za-z0-9_.-]{1,100}"))}?:"fb-$i")
                .put("url",url).put("ext",if(f.optString("ext")=="m4a")"m4a" else "mp4").put("protocol","https")
                .put("width",f.optInt("width").coerceIn(0,16384)).put("height",f.optInt("height").coerceIn(0,16384))
                .put("fps",f.optDouble("fps",0.0).takeIf{it.isFinite()}?.coerceIn(0.0,240.0)?:0)
                .put("tbr",f.optDouble("tbr",0.0).takeIf{it.isFinite()}?.coerceIn(0.0,1_000_000.0)?:0)
                .put("vcodec",f.optString("vcodec").takeIf{it.matches(Regex("[A-Za-z0-9_.-]{1,100}"))}?:"h264")
                .put("acodec",f.optString("acodec").takeIf{it.matches(Regex("[A-Za-z0-9_.-]{1,100}"))}?:"aac")
                .put("quality",f.optInt("quality",0).coerceIn(-10,10)).put("http_headers",headers)
            rows.put(row)
        }
        if(rows.length()==0)return null
        return JSONObject().put("id",id).put("title",raw.optString("title","Facebook video").take(500))
            .put("duration",raw.optDouble("duration",0.0).takeIf{it.isFinite()}?.coerceIn(0.0,604800.0)?:0)
            .put("thumbnail",raw.optString("thumbnail").takeIf(::safeUrl).orEmpty())
            .put("webpage_url","https://www.facebook.com/watch/?v=$id").put("extractor","facebook:browser").put("extractor_key","FacebookBrowser")
            .put("http_headers",headers).put("formats",rows)
    }
    fun videoInfo(info: JSONObject): VideoInfo = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false).readValue(info.toString(),VideoInfo::class.java)
    fun save(context: Context, info: JSONObject, existing: String = ""): String {
        val folder=File(context.filesDir,"facebook-media").apply{mkdirs()}
        folder.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>24*60*60*1000L }?.forEach{it.delete()}
        val key=existing.takeIf{keyPattern.matches(it)}?:UUID.randomUUID().toString()
        val temp=File(folder,"$key.tmp");temp.writeText(info.toString())
        check(temp.renameTo(File(folder,"$key.json"))){"Could not save Facebook media details."}
        return key
    }
    fun file(context: Context, key: String): File? {
        if(!keyPattern.matches(key))return null
        return File(context.filesDir,"facebook-media/$key.json").takeIf{it.isFile&&it.length() in 1..1_000_000&&System.currentTimeMillis()-it.lastModified()<20*60*1000L}
    }
    fun remove(context: Context,key: String){if(keyPattern.matches(key))File(context.filesDir,"facebook-media/$key.json").delete()}
}
