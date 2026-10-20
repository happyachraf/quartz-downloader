package com.quartz.downloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Remuxes tags and the source artwork while copying the encoded audio unchanged. */
object AudioArtwork {
    private val processes = ConcurrentHashMap<String, Process>()
    fun cancel(id: String) { processes.remove(id)?.destroyForcibly() }

    fun thumbnail(context: Context, id: String, source: String): File? {
        if (source.isBlank()) return null
        require(source.startsWith("https://"))
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        val bytes = try {
            require(connection.responseCode in 200..299 && connection.url.protocol == "https") { "Cover image unavailable" }
            connection.inputStream.use { input ->
                val buffer = ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val read = input.read(chunk); if (read < 0) break; buffer.write(chunk,0,read); require(buffer.size() <= 8*1024*1024) { "Cover image too large" } }
                buffer.toByteArray()
            }
        } finally { connection.disconnect() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Could not read cover image" }
        val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth,bounds.outHeight) / 900).coerceAtLeast(1) }
        val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("Could not read cover image")
        val folder = File(context.filesDir,"covers").apply { mkdirs() }
        val file = File(folder,"$id.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,92,it) }
        bitmap.recycle()
        return file
    }

    fun embed(context: Context, job: JSONObject, audio: File, info: JSONObject, cancelled: () -> Boolean = { false }): JSONObject {
        val id = job.getString("id")
        check(!cancelled()) { "Cancelled" }
        fun field(obj:JSONObject,key:String)=if(obj.isNull(key)) "" else obj.optString(key)
        val artist = field(job,"spotifyArtists").ifBlank { field(info,"artist").ifBlank { field(info,"uploader") } }
        var title = field(job,"spotifyTitle").ifBlank { field(info,"track").ifBlank { field(info,"title").ifBlank { field(job,"title") } } }
        var displayArtist = artist
        if (field(job,"spotifyTitle").isBlank() && field(info,"track").isBlank() && title.contains(" - ")) {
            val parts = title.split(" - ",limit=2)
            displayArtist = parts[0]; title = parts[1].replace(Regex("\\s*[\\[(](?:official|lyrics?|audio|video).*",RegexOption.IGNORE_CASE),"").trim()
        }
        val album = field(job,"spotifyAlbum").ifBlank { field(info,"album").ifBlank { field(job,"spotifyPlaylist") } }
        val url = job.optString("thumbnail").ifBlank { info.optString("thumbnail") }
        val cover = thumbnail(context,id,url)
        check(!cancelled()) { "Cancelled" }
        val changes = JSONObject().put("musicTitle",title).put("musicArtist",displayArtist).put("musicAlbum",album)
            .put("duration",info.optDouble("duration",job.optDouble("duration",0.0)))
            .put("artworkState",if(cover!=null && audio.extension.lowercase() in listOf("opus","ogg","flac","mp3","m4a","mp4")) "embedded" else if(cover!=null) "cached" else "unavailable")
        val metadata = File(audio.parentFile,"artwork-tags.txt")
        fun escaped(value: String) = value.replace("\\","\\\\").replace("=","\\=").replace(";","\\;").replace("#","\\#").replace("\r","").replace("\n"," ")
        val tags = StringBuilder(";FFMETADATA1\ntitle=${escaped(title)}\nartist=${escaped(displayArtist)}\nalbum=${escaped(album)}\n")
        if(cover!=null) {
            changes.put("artworkPath",cover.absolutePath)
            if(audio.extension.lowercase() in listOf("opus","ogg","flac")) {
                val picture=ByteArrayOutputStream()
                DataOutputStream(picture).use { out ->
                    val image=cover.readBytes(); val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(cover.absolutePath,bounds)
                    out.writeInt(3);val mime="image/jpeg".toByteArray();out.writeInt(mime.size);out.write(mime);out.writeInt(0)
                    out.writeInt(bounds.outWidth);out.writeInt(bounds.outHeight);out.writeInt(24);out.writeInt(0);out.writeInt(image.size);out.write(image)
                }
                tags.append("METADATA_BLOCK_PICTURE=${Base64.encodeToString(picture.toByteArray(),Base64.NO_WRAP)}\n")
            }
        }
        metadata.writeText(tags.toString())
        val result=File(audio.parentFile,"tagged.${audio.extension}")
        val command=mutableListOf(File(context.applicationInfo.nativeLibraryDir,"libffmpeg.so").absolutePath,"-hide_banner","-loglevel","error","-y","-i",audio.absolutePath,"-f","ffmetadata","-i",metadata.absolutePath)
        val attached=cover!=null && audio.extension.lowercase() in listOf("mp3","m4a","mp4")
        if(attached) command.addAll(listOf("-i",cover!!.absolutePath))
        command.addAll(listOf("-map","0:a:0","-map_metadata","1","-c:a","copy"))
        if(attached) command.addAll(listOf("-map","2:v:0","-c:v","mjpeg","-disposition:v:0","attached_pic","-metadata:s:v:0","title=Album cover","-metadata:s:v:0","comment=Cover (front)"))
        if(audio.extension=="mp3") command.addAll(listOf("-id3v2_version","3"))
        command.add(result.absolutePath)
        val builder=ProcessBuilder(command).redirectErrorStream(true).redirectOutput(File(audio.parentFile,"artwork-process.log"))
        val packages=File(context.noBackupFilesDir,"youtubedl-android/packages")
        builder.environment()["LD_LIBRARY_PATH"] = listOf("python","ffmpeg","aria2c").joinToString(":"){File(packages,"$it/usr/lib").absolutePath}
        val process=builder.start(); processes[id]=process
        try {
            check(!cancelled()) { "Cancelled" }
            check(process.waitFor(90,TimeUnit.SECONDS) && process.exitValue()==0 && result.length()>0) { "Could not embed the cover picture" }
            check(audio.delete() && result.renameTo(audio)) { "Could not save audio tags" }
        } finally { processes.remove(id); if(process.isAlive)process.destroyForcibly() }
        return changes
    }
}
