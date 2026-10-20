package com.quartz.downloader

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Only public GitHub Releases. No GitHub credential is stored in the app. */
object AppUpdates {
    const val REPOSITORY = "happyachraf/quartz-downloader"
    const val RELEASES = "https://github.com/$REPOSITORY/releases"
    private const val API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private const val PREFS = "quartz_app_updates"
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val generation = AtomicLong()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)
    private fun installed(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0)
    private fun read(context: Context): JSONObject = try { JSONObject(prefs(context).getString("state", "{}")!!) } catch (_: Exception) { JSONObject() }
    private fun save(context: Context, row: JSONObject) { prefs(context).edit().putString("state", row.toString()).commit() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val buffer=ByteArray(65536); while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun release(data: JSONObject): JSONObject {
        require(!data.optBoolean("draft") && !data.optBoolean("prerelease")) { "This is not a stable release." }
        val tag = data.getString("tag_name"); val version = tag.removePrefix("v")
        require(tag == "v$version" && UpdateVersions.valid(version)) { "Invalid update version." }
        val name = "Quartz-Downloader-Android-$version.apk"
        val assets = data.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
        require(matches.size == 1) { "This release does not include a Quartz Android installer." }
        val asset = matches.single()
        val url = "https://github.com/$REPOSITORY/releases/download/$tag/$name"
        require(asset.getString("browser_download_url") == url) { "Unexpected update download location." }
        val digest = asset.optString("digest").removePrefix("sha256:")
        require(Regex("[a-fA-F0-9]{64}").matches(digest)) { "The update checksum is not available yet. Try again shortly." }
        require(asset.getLong("size") in 1..350_000_000L) { "Unexpected installer size." }
        return JSONObject().put("version", version).put("url", url).put("name", name).put("sha256", digest.lowercase())
            .put("size", asset.getLong("size")).put("notes", data.optString("body").take(12000)).put("releaseUrl", "$RELEASES/tag/$tag")
    }
    private fun fetch(): JSONObject {
        val connection = URL(API).openConnection() as HttpURLConnection
        connection.connectTimeout=15000;connection.readTimeout=20000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Quartz-Downloader-Android")
        try {
            val code=connection.responseCode
            if(code==404)throw IllegalStateException("No published update is available yet.")
            if(code==403||code==429)throw IllegalStateException("GitHub is limiting update checks. Try again later.")
            check(code==200){"Could not reach GitHub. Try again later."}
            val bytes=connection.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=1_048_576){"Unexpected update response."};output.write(buffer,0,count)}
                output.toByteArray()
            }
            return release(JSONObject(bytes.toString(Charsets.UTF_8)))
        } finally { connection.disconnect() }
    }
    @Synchronized fun check(context: Context, force: Boolean) {
        val app=context.applicationContext
        val old=read(app)
        if(old.optString("status") in listOf("downloading","verifying"))return
        val interval=if(force) 15000 else 6*60*60*1000L
        if(System.currentTimeMillis()-prefs(app).getLong("lastCheck",0)<interval)return
        if(!busy.compareAndSet(false,true))return
        val token=generation.incrementAndGet()
        prefs(app).edit().putLong("lastCheck",System.currentTimeMillis()).apply()
        save(app, JSONObject(old.toString()).put("status","checking").put("message","Checking GitHub for updates…"))
        worker.execute {
            try {
                val next=fetch()
                if(token!=generation.get())return@execute
                val newer=UpdateVersions.compare(next.getString("version"),installed(app).versionName!!)>0
                if(newer&&old.optString("status")=="ready"&&old.optString("version")==next.getString("version")&&File(File(app.filesDir,"updates"),"Quartz-${old.getString("version")}.apk").length()==old.optLong("size"))save(app,old)
                else save(app,next.put("status",if(newer)"available" else "upToDate").put("message",if(newer)"A new Quartz Downloader version is available." else "You have the latest version."))
            }catch(error:Exception){
                if(token==generation.get()){
                    if(old.optString("status") in listOf("available","ready"))save(app,old.put("message","Could not refresh GitHub. Your previously checked update is still available."))
                    else save(app,JSONObject().put("status","error").put("message",error.message?:"Could not check for updates. Check your connection."))
                }
            }finally{busy.set(false)}
        }
    }
    @Synchronized fun download(context: Context) {
        val app=context.applicationContext;val row=read(app)
        if(row.optString("status")!in listOf("available","error"))return
        if(!UpdateVersions.valid(row.optString("version")))return
        val expected="https://github.com/$REPOSITORY/releases/download/v${row.getString("version")}/Quartz-Downloader-Android-${row.getString("version")}.apk"
        require(row.getString("url")==expected)
        generation.incrementAndGet()
        val directory=app.getExternalFilesDir("updates")?:throw IllegalStateException("Update storage is unavailable.")
        val target=File(directory,row.getString("name"));if(target.exists())target.delete()
        val request=DownloadManager.Request(Uri.parse(expected)).setTitle("Quartz Downloader update ${row.getString("version")}")
            .setDescription("Downloading the verified GitHub release")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(app,"updates",target.name)
        val id=manager(app).enqueue(request)
        save(app,row.put("downloadId",id).put("status","downloading").put("message","Downloading update…").put("progress",0))
    }
    fun validateApk(context: Context, file: File, row: JSONObject, installedCode: Long = installed(context).longVersionCode) {
        require(file.length()==row.getLong("size")){"The installer download is incomplete."}
        require(fileHash(file)==row.getString("sha256")){"The installer checksum does not match GitHub."}
        val candidate=context.packageManager.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES)?:error("Android could not read this installer.")
        require(candidate.packageName==context.packageName){"This installer is for a different app."}
        require(candidate.versionName==row.getString("version")&&candidate.longVersionCode>installedCode){"This installer is not a newer Quartz version."}
        val current=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val expected=current.signingInfo!!.apkContentsSigners.map{hash(it.toByteArray())}.toSet()
        val offered=candidate.signingInfo!!.apkContentsSigners.map{hash(it.toByteArray())}.toSet()
        require(expected.isNotEmpty()&&expected==offered){"The update was not signed with Quartz's original key."}
    }
    private fun verify(context: Context,row:JSONObject) {
        if(!busy.compareAndSet(false,true))return
        val token=generation.get()
        save(context,row.put("status","verifying").put("message","Verifying the update's checksum and Quartz signature…"))
        worker.execute {
            var temp:File?=null
            try{
                val source=File(context.getExternalFilesDir("updates"),row.getString("name"))
                validateApk(context,source,row)
                val folder=File(context.filesDir,"updates").apply{mkdirs()}
                val dest=File(folder,"Quartz-${row.getString("version")}.apk")
                temp=File(folder,dest.name+".tmp")
                source.inputStream().use{input->temp!!.outputStream().use{input.copyTo(it)}}
                require(fileHash(temp!!)==row.getString("sha256")){"The saved installer checksum is incorrect."}
                synchronized(this){
                    if(token!=generation.get())return@execute
                    check(temp!!.renameTo(dest)){"Could not save the update installer."}
                    save(context,row.put("status","ready").put("file",dest.name).put("progress",100).put("message","Update verified. Ready to install."))
                }
                source.delete()
            }catch(error:Exception){if(token==generation.get())save(context,row.put("status","available").put("message",error.message?:"The update could not be verified. Download it again."))}
            finally{temp?.delete();busy.set(false)}
        }
    }
    @Synchronized fun state(context: Context): JSONObject {
        var row=read(context)
        val current=installed(context).versionName!!
        if(UpdateVersions.valid(row.optString("version"))&&UpdateVersions.compare(row.getString("version"),current)<=0&&row.optString("status")!="checking"){
            row.put("status","upToDate").put("message","You have the latest version.")
        }
        if(row.optString("status")=="verifying"&&!busy.get()){
            row.put("status","downloading");save(context,row)
        }
        if(row.optString("status")=="downloading"){
            manager(context).query(DownloadManager.Query().setFilterById(row.optLong("downloadId",-1)))?.use{cursor->
                if(cursor.moveToFirst()){
                    val status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    row.put("progress",if(total>0)(100*bytes/total).toInt() else 0).put("bytes",bytes)
                    when(status){
                        DownloadManager.STATUS_SUCCESSFUL->{verify(context,row);row=read(context)}
                        DownloadManager.STATUS_FAILED->{row.put("status","available").put("message","Update download failed. Tap Download to try again.");save(context,row)}
                        DownloadManager.STATUS_PAUSED->row.put("message","Waiting for a connection. The update download will continue.")
                    }
                }else{row.put("status","available").put("message","Tap Download to get the update again.");save(context,row)}
            }
        }
        val dismissed=prefs(context).getString("dismissedVersion","")==row.optString("version")&&System.currentTimeMillis()-prefs(context).getLong("dismissedAt",0)<24*60*60*1000
        return row.put("installedVersion",current).put("prompt",!dismissed&&row.optString("status") in listOf("available","ready"))
    }
    @Synchronized fun cancel(context:Context){
        generation.incrementAndGet();val row=read(context)
        val id=row.optLong("downloadId",-1);if(id>=0)manager(context).remove(id)
        if(UpdateVersions.valid(row.optString("version")))File(context.getExternalFilesDir("updates"),row.optString("name")).takeIf{it.isFile}?.delete()
        save(context,row.put("status","available").put("progress",0).put("message","Update download cancelled."))
    }
    fun dismiss(context:Context,version:String){prefs(context).edit().putString("dismissedVersion",version).putLong("dismissedAt",System.currentTimeMillis()).apply()}
    fun readyFile(context:Context):File?{
        val row=read(context);if(row.optString("status")!="ready"||!UpdateVersions.valid(row.optString("version")))return null
        val file=File(File(context.filesDir,"updates"),"Quartz-${row.getString("version")}.apk")
        return file.takeIf{it.isFile&&it.length()==row.optLong("size")}
    }
    fun install(activity:Activity){
        val file=readyFile(activity)?:return
        if(!activity.packageManager.canRequestPackageInstalls()){
            prefs(activity).edit().putBoolean("installAfterPermission",true).apply()
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${activity.packageName}")));return
        }
        prefs(activity).edit().putBoolean("installAfterPermission",false).apply()
        val uri=Uri.parse("content://${activity.packageName}.updates/apk")
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    fun resume(activity:Activity){
        if(prefs(activity).getBoolean("installAfterPermission",false)){
            prefs(activity).edit().putBoolean("installAfterPermission",false).apply()
            if(activity.packageManager.canRequestPackageInstalls())install(activity)
        }
    }
}
