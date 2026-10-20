package com.quartz.downloader

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Device checks run against the release APK. This class is only in the separate test APK. */
class DownloadFlowInstrumentation : Instrumentation() {
    private var phase = "share"
    private lateinit var evidence: File
    private val checks = JSONArray()
    private var activity: MainActivity? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        phase = arguments?.getString("phase") ?: "share"
        start()
    }
    override fun onStart() {
        evidence = File(targetContext.getExternalFilesDir(null), "tests-030").apply { mkdirs() }
        try {
            when (phase) {
                "spotify-history" -> spotifyHistory()
                "bulk-selection" -> bulkSelection()
                "app-updates" -> appUpdates()
                "share" -> share()
                "notifications" -> notifications()
                "download" -> download()
                "permission" -> permission()
                "controls" -> controls()
                "denied" -> denied()
                "chooser" -> chooser()
                "video" -> video()
                "ui" -> ui()
                "browser" -> browser()
                "artwork" -> artwork()
                "spotify" -> spotify()
                "playback" -> playback()
                "providers" -> providers()
                "persistence" -> persistence()
                "rotation" -> rotation()
                "playlist-share" -> playlistShare()
                "matching-first" -> matchingFirst()
                "high-video" -> highVideo()
                "state" -> {save("latest-jobs.json",DownloadStore.all(targetContext));save("latest-notification-status.json",DownloadNotifications.status(targetContext));snapshot("latest-notifications.json")}
                "slow-progress" -> video()
            }
            save("$phase-results.json", JSONObject().put("passed", true).put("checks", checks))
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", "$phase passed: $checks") })
        } catch (error: Throwable) {
            save("$phase-results.json", JSONObject().put("passed", false).put("checks", checks).put("error", error.stackTraceToString()))
            screenshot("$phase-failed")
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("error", error.stackTraceToString()) })
        }
    }
    private fun check(name: String, condition: Boolean) { if (!condition) error(name); checks.put(name) }
    private fun appUpdates() {
        val version="0.3.4"
        val name="Quartz-Downloader-Android-$version.apk"
        val asset=JSONObject().put("name",name).put("browser_download_url","https://github.com/${AppUpdates.REPOSITORY}/releases/download/v$version/$name").put("size",123).put("digest","sha256:"+"a".repeat(64))
        val metadata=JSONObject().put("tag_name","v$version").put("draft",false).put("prerelease",false).put("assets",JSONArray().put(asset)).put("body","Update notes")
        check("Stable GitHub release accepted",AppUpdates.release(metadata).getString("version")==version)
        fun rejects(change:(JSONObject)->Unit):Boolean { val copy=JSONObject(metadata.toString());change(copy);return runCatching{AppUpdates.release(copy)}.isFailure }
        check("Draft releases rejected",rejects{it.put("draft",true)})
        check("Prereleases rejected",rejects{it.put("prerelease",true)})
        check("Malformed version rejected",rejects{it.put("tag_name","v../bad")})
        check("Download from other repository rejected",rejects{it.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://github.com/other/app/releases/download/v0.3.4/$name")})
        check("Missing checksum rejected",rejects{it.getJSONArray("assets").getJSONObject(0).remove("digest")})
        check("Oversized installer rejected",rejects{it.getJSONArray("assets").getJSONObject(0).put("size",999999999L)})
        check("Duplicate installer assets rejected",rejects{it.getJSONArray("assets").put(JSONObject(asset.toString()))})
        val candidate=File(targetContext.getExternalFilesDir(null),"update-test/release.apk")
        val debug=File(targetContext.getExternalFilesDir(null),"update-test/debug.apk")
        fun digest(file:File):String {val sha=java.security.MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val buf=ByteArray(65536);while(true){val n=input.read(buf);if(n<0)break;sha.update(buf,0,n)}};return sha.digest().joinToString(""){"%02x".format(it)}}
        val row=JSONObject().put("version",version).put("size",candidate.length()).put("sha256",digest(candidate))
        AppUpdates.validateApk(targetContext,candidate,row,10)
        check("Actual signed APK accepted for an older installation",true)
        check("Same version code rejected",runCatching{AppUpdates.validateApk(targetContext,candidate,row,11)}.isFailure)
        check("Corrupt checksum rejected",runCatching{AppUpdates.validateApk(targetContext,candidate,JSONObject(row.toString()).put("sha256","0".repeat(64)),10)}.isFailure)
        check("Wrong version rejected",runCatching{AppUpdates.validateApk(targetContext,candidate,JSONObject(row.toString()).put("version","0.3.5"),10)}.isFailure)
        val debugRow=JSONObject(row.toString()).put("size",debug.length()).put("sha256",digest(debug))
        check("Different signing key rejected",runCatching{AppUpdates.validateApk(targetContext,debug,debugRow,10)}.isFailure)
        val preferences=targetContext.getSharedPreferences("quartz_app_updates",0)
        val backup=preferences.all
        val privateFile=File(targetContext.filesDir,"updates/Quartz-$version.apk").apply{parentFile!!.mkdirs()}
        try {
            candidate.copyTo(privateFile,true)
            preferences.edit().putString("state",JSONObject(row.toString()).put("status","ready").toString()).putLong("lastCheck",System.currentTimeMillis()).commit()
            val uri=android.net.Uri.parse("content://${targetContext.packageName}.updates/apk")
            targetContext.contentResolver.openInputStream(uri)!!.use{check("Verified APK readable through temporary-grant provider",it.read()>=0)}
            check("Provider rejects write access",runCatching{targetContext.contentResolver.openFileDescriptor(uri,"w")!!.close()}.isFailure)
            check("Provider rejects arbitrary paths",runCatching{targetContext.contentResolver.openInputStream(android.net.Uri.parse("content://${targetContext.packageName}.updates/other"))!!.close()}.isFailure)
            val provider=targetContext.packageManager.getProviderInfo(android.content.ComponentName(targetContext,UpdateApkProvider::class.java),0)
            check("Update provider is explicitly non-exported",!provider.exported&&provider.grantUriPermissions)
            val ui=JSONObject().put("status","available").put("version","0.3.5").put("size",115000000).put("notes","A verified GitHub update.\nYour playlists stay saved.").put("message","A new Quartz Downloader version is available.")
            preferences.edit().putString("state",ui.toString()).remove("dismissedVersion").commit()
            launch("",false)
            waitUntil(15){js("typeof AppUpdateUI==='object'")=="true"}
            waitUntil(15){js("document.getElementById('app-update-dialog').open")=="true"}
            check("Available update opens purple in-app prompt",js("document.getElementById('app-update-title').textContent")=="\"Update available\"")
            check("Prompt shows version and release notes",js("document.getElementById('app-update-version').textContent.includes('0.3.5')&&document.getElementById('app-update-notes').textContent.includes('playlists')")=="true")
            screenshot("app-update-prompt")
            js("document.getElementById('app-update-later').click();refresh()")
            check("Later dismisses prompt without installing",js("!document.getElementById('app-update-dialog').open")=="true")
            check("Later remembered for this version",!AppUpdates.state(targetContext).optBoolean("prompt"))
            js("AppUI.navigate('settings');document.getElementById('update-check').click()")
            check("Manual update control opens details",js("document.getElementById('app-update-dialog').open")=="true")
        }finally{
            activity?.let{runOnMainSync{it.finish()}};activity=null
            val editor=preferences.edit().clear()
            for((key,value)in backup)when(value){is String->editor.putString(key,value);is Long->editor.putLong(key,value);is Boolean->editor.putBoolean(key,value);is Int->editor.putInt(key,value)}
            editor.commit();privateFile.delete()
        }
    }
    private fun bulkSelection() {
        targetContext.stopService(Intent(targetContext, DownloadService::class.java))
        targetContext.stopService(Intent(targetContext, SpotifyService::class.java))
        val prefs = targetContext.getSharedPreferences("quartz_downloads", 0)
        val originalJobs = prefs.getString("jobs", "[]")
        val library = File(targetContext.filesDir, "spotify-library.json")
        val originalLibrary = if (library.exists()) library.readBytes() else null
        val uris = mutableListOf<android.net.Uri>()
        val urls = listOf("https://open.spotify.com/playlist/2QGJRF9Tf537Z5JXbB16Kd", "https://open.spotify.com/playlist/62ppawlNpdv2tRMzJj01NV")
        fun readable(index: Int) = try { targetContext.contentResolver.openInputStream(uris[index])?.use { it.readBytes().isNotEmpty() } == true } catch (_: Exception) { false }
        try {
            val jobs = JSONArray()
            for (i in 0..2) {
                val source = File(targetContext.cacheDir, "quartz-bulk-test-$i.txt").apply { writeText("bulk sentinel $i") }
                val uri = MediaFiles.publish(targetContext, source); uris.add(uri); source.delete()
                jobs.put(JSONObject().put("id", "bulk-test-$i").put("title", "Test song $i").put("mode", "audio").put("state", "complete").put("type", "media").put("uri", uri.toString()).put("filename", "quartz-bulk-test-$i.txt").put("url", "https://www.youtube.com/watch?v=2z6pCa_xehY").put("spotifyPlaylist", if (i < 2) "Batch A" else "Batch B"))
            }
            prefs.edit().putString("jobs", jobs.toString()).commit()
            for ((index, url) in urls.withIndex()) {
                val tracks = JSONArray()
                for (i in 0..1) tracks.put(JSONObject().put("key", "bulk-track-$i").put("name", "Test track $i").put("artists", JSONArray().put("Artist")).put("duration", 120).put("uri", "spotify:track:bulk$i").put("status", "unmatched").put("candidates", JSONArray()))
                PlaylistStore.save(targetContext, JSONObject().put("url", url).put("title", "Batch history $index").put("status", "ready").put("tracks", tracks))
            }
            launch("", false)
            waitUntil(20) { js("typeof BulkUI==='object'") == "true" }
            js("AppUI.navigate('music');document.getElementById('music-playlists').click()")
            waitUntil(10) { js("document.querySelectorAll('.playlist-row').length") == "2" }
            val point = JSONObject(js("(()=>{const r=document.querySelector('.playlist-row').getBoundingClientRect();return {x:(r.left+r.width/2)*devicePixelRatio,y:(r.top+r.height/2)*devicePixelRatio}})()"))
            val location = IntArray(2)
            runOnMainSync { val field=MainActivity::class.java.getDeclaredField("web").apply{isAccessible=true};(field.get(activity) as WebView).getLocationOnScreen(location) }
            val x = location[0]+point.getInt("x"); val y=location[1]+point.getInt("y")
            shell("input swipe $x $y $x $y 700")
            waitUntil(10) { js("BulkUI.scope==='musicPlaylists'") == "true" }
            check("Real long press selects a Music playlist", js("document.getElementById('bulk-count').textContent") == "\"1 selected\"")
            check("Long press does not open playlist", js("document.querySelectorAll('.playlist-row').length") == "2")
            js("document.getElementById('bulk-all').click()")
            check("Select all selects both playlists", js("document.getElementById('bulk-count').textContent") == "\"2 selected\"")
            screenshot("bulk-playlists-selected")
            js("document.getElementById('bulk-delete').click()")
            check("Playlist confirmation offers keeping songs", js("document.getElementById('bulk-confirm-actions').firstElementChild.textContent") == "\"Remove playlists\"")
            check("Deleting files is an explicit separate choice", js("document.getElementById('bulk-confirm-actions').children[1].textContent") == "\"Delete files too\"")
            js("document.getElementById('bulk-confirm-actions').firstElementChild.click()")
            waitUntil(10) { js("!document.getElementById('bulk-dialog').open && BulkUI.scope===''") == "true" }
            check("Selected playlists removed from Music groups", js("document.querySelectorAll('.playlist-row').length") == "0")
            check("Playlist removal preserves all song records", DownloadStore.all(targetContext).length()==3)
            check("Playlist removal preserves all saved files", (0..2).all { readable(it) })
            js("document.getElementById('all-songs').click()")
            check("Songs remain in All songs", js("document.querySelectorAll('.music-row').length") == "3")
            js("BulkUI.begin(document.querySelector('.music-row'));document.getElementById('bulk-all').click();document.getElementById('bulk-all').click()")
            check("Deselect all clears selection", js("document.getElementById('bulk-count').textContent") == "\"0 selected\"")
            check("Empty selection disables removal", js("document.getElementById('bulk-delete').disabled") == "true")
            js("document.getElementById('bulk-all').click();document.getElementById('bulk-delete').click();document.getElementById('bulk-confirm-actions').lastElementChild.click()")
            check("Cancel keeps files and records", DownloadStore.all(targetContext).length()==3 && (0..2).all { readable(it) })
            js("BulkUI.cancel();BulkUI.begin(document.querySelector('.music-row'));document.getElementById('bulk-delete').click();document.getElementById('bulk-confirm-actions').firstElementChild.click()")
            waitUntil(10) { DownloadStore.get(targetContext,"bulk-test-0")==null }
            check("Bulk song delete removes selected record", DownloadStore.all(targetContext).length()==2)
            check("Bulk song delete removes actual selected file", !readable(0))
            check("Unselected audio files remain", readable(1)&&readable(2))
            js("AppUI.navigate('downloads');BulkUI.begin(document.querySelector('#jobs [data-select-id]'));document.getElementById('bulk-all').click();document.getElementById('bulk-delete').click();document.getElementById('bulk-confirm-actions').firstElementChild.click()")
            waitUntil(10) { js("BulkUI.scope===''") == "true" }
            check("Downloads support select all and bulk history removal", js("document.querySelectorAll('#jobs [data-select-id]').length") == "0")
            check("Download history removal preserves saved music", DownloadStore.all(targetContext).length()==2 && readable(1)&&readable(2))
            js("AppUI.navigate('spotify');BackgroundUI.library();BulkUI.begin(document.querySelector('#spotify-library [data-select-id]'));document.getElementById('bulk-all').click();document.getElementById('bulk-delete').click();document.getElementById('bulk-confirm-actions').firstElementChild.click()")
            waitUntil(10) { PlaylistStore.summaries(targetContext).length()==0 }
            check("Spotify history supports bulk clearing", PlaylistStore.summaries(targetContext).length()==0)
            js("native('selectPlaylist',"+JSONObject.quote(urls[0])+")")
            waitUntil(10) { js("document.querySelectorAll('#spotify-tracks [data-select-id]').length") == "2" }
            js("BulkUI.begin(document.querySelector('#spotify-tracks [data-select-id]'));document.getElementById('bulk-all').click();document.getElementById('bulk-delete').click();document.getElementById('bulk-confirm-actions').firstElementChild.click()")
            waitUntil(10) { PlaylistStore.getFor(targetContext,urls[0])!!.getJSONArray("tracks").length()==0 }
            check("Spotify track list supports select all and removal", PlaylistStore.getFor(targetContext,urls[0])!!.getJSONArray("tracks").length()==0)
            check("Removing Spotify tracks preserves downloaded files", DownloadStore.all(targetContext).length()==2 && readable(1)&&readable(2))
        } finally {
            activity?.let { runOnMainSync { it.finish() } };activity=null
            prefs.edit().putString("jobs",originalJobs).commit()
            if(originalLibrary!=null)library.writeBytes(originalLibrary) else library.delete()
            for(uri in uris)try{targetContext.contentResolver.delete(uri,null,null)}catch(_:Exception){}
        }
    }
    private fun spotifyHistory() {
        val file = File(targetContext.filesDir, "spotify-library.json")
        val backup = if (file.exists()) file.readBytes() else null
        val downloads = targetContext.getSharedPreferences("quartz_downloads", 0).getString("jobs", "[]")
        val savedFile = File(targetContext.cacheDir, "history-preserves-file.txt").apply { writeText("saved audio sentinel") }
        val first = "https://open.spotify.com/playlist/2QGJRF9Tf537Z5JXbB16Kd"
        val second = "https://open.spotify.com/playlist/62ppawlNpdv2tRMzJj01NV"
        try {
            val matches = JSONArray().put(JSONObject().put("url", "https://www.youtube.com/watch?v=2z6pCa_xehY"))
            val track = JSONObject().put("key", "history-test").put("candidates", matches).put("selectedUrl", "https://www.youtube.com/watch?v=2z6pCa_xehY").put("confirmed", true)
            PlaylistStore.save(targetContext, JSONObject().put("url", second).put("title", "Second workspace").put("status", "ready").put("tracks", JSONArray()))
            PlaylistStore.save(targetContext, JSONObject().put("url", first).put("title", "Completed workspace").put("status", "ready").put("tracks", JSONArray().put(track)))
            check("Completed Spotify history can be cleared", PlaylistStore.clearHistory(targetContext, first))
            val visible = PlaylistStore.summaries(targetContext)
            check("Cleared playlist card disappears", (0 until visible.length()).none { visible.getJSONObject(it).optString("url") == first })
            check("Other playlist stays visible", (0 until visible.length()).any { visible.getJSONObject(it).optString("url") == second })
            check("Cleared selected workspace is closed", PlaylistStore.get(targetContext).optString("status") == "empty")
            check("Music and download records untouched", targetContext.getSharedPreferences("quartz_downloads", 0).getString("jobs", "[]") == downloads)
            check("Saved file untouched", savedFile.readText() == "saved audio sentinel")
            check("Saved match retained in cache", PlaylistStore.getFor(targetContext, first)!!.getJSONArray("tracks").getJSONObject(0).optString("selectedUrl") == "https://www.youtube.com/watch?v=2z6pCa_xehY")
            PlaylistStore.select(targetContext, first)
            check("Reopening the same link restores its cached workspace", !PlaylistStore.get(targetContext).optBoolean("historyHidden"))
            PlaylistStore.updateFor(targetContext, first) { it.put("status", "matching").put("wantMatching", true) }
            check("Running search cannot be cleared accidentally", !PlaylistStore.clearHistory(targetContext, first))
        } finally {
            if (backup != null) file.writeBytes(backup) else file.delete()
            savedFile.delete()
        }
    }
    private fun waitUntil(seconds: Int = 180, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) { if (predicate()) return; Thread.sleep(350) }
        error("Timed out after ${seconds}s")
    }
    private fun launch(url: String, share: Boolean = true): MainActivity {
        shell("cmd statusbar collapse")
        activity?.let { runOnMainSync { it.finish() } }
        waitForIdleSync()
        val intent = Intent(targetContext, if (share) ShareActivity::class.java else MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (share) intent.setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Watch this video: $url")
        else intent.putExtra("quartz_url", url)
        activity = startActivitySync(intent) as MainActivity
        waitUntil(20) { js("typeof window.DownloadSheet === 'object'") == "true" }
        return activity!!
    }
    private fun js(code: String): String {
        val latch = CountDownLatch(1); var value = ""
        runOnMainSync {
            val field = MainActivity::class.java.getDeclaredField("web").apply { isAccessible = true }
            (field.get(activity) as WebView).evaluateJavascript(code) { value = it; latch.countDown() }
        }
        check(latch.await(15, TimeUnit.SECONDS)) { "WebView did not reply" }
        return value
    }
    private fun ready() = waitUntil { js("typeof current!=='undefined' && !!current && !document.getElementById('download-sheet-confirm').disabled") == "true" }
    private fun save(name: String, data: Any) = File(evidence, name).writeText(data.toString())
    private fun screenshot(name: String) {
        val rendered=CountDownLatch(1)
        runOnMainSync{
            val currentActivity=activity
            if(currentActivity!=null && !currentActivity.isDestroyed && currentActivity.hasWindowFocus()){
                try{val f=MainActivity::class.java.getDeclaredField("web").apply{isAccessible=true};val view=f.get(currentActivity) as WebView;view.postVisualStateCallback(2,object:WebView.VisualStateCallback(){override fun onComplete(id:Long){view.invalidate();rendered.countDown()}})}catch(_:Exception){rendered.countDown()}
            }else rendered.countDown()
        }
        rendered.await(10,TimeUnit.SECONDS)
        Thread.sleep(450) // Let the WebView compositor present the last DOM change before capture.
        uiAutomation.takeScreenshot()?.let { bitmap -> File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun share() {
        launch("https://youtu.be/2z6pCa_xehY")
        ready()
        check("Share opens options automatically", js("document.getElementById('download-sheet').open") == "true")
        check("Share uses transparent activity", activity is ShareActivity)
        check("Both audio options present", js("!!document.querySelector('[data-option=\"audio:original\"]') && !!document.querySelector('[data-option=\"audio:mp3\"]')") == "true")
        check("Only discovered video resolutions listed", js("[...document.querySelectorAll('[data-option]')].filter(x=>x.dataset.option.startsWith('video:')&&x.dataset.option!=='video:best').every(x=>qualities.some(q=>String(q.height)===x.dataset.option.split(':')[1]))") == "true")
        save("share-preview.json", js("JSON.stringify({title:document.getElementById('title').textContent,qualities})"))
        screenshot("share-options")
        js("document.querySelector('[data-option=\"audio:original\"]').click()")
        check("Original audio can be selected", js("document.querySelector('[data-option=\"audio:original\"]').getAttribute('aria-checked')") == "\"true\"")
        screenshot("share-original-audio")
        js("document.querySelector('[data-option=\"audio:mp3\"]').click()")
        check("MP3 selection updates button", js("document.getElementById('download-sheet-confirm').textContent") == "\"Download audio\"")
        js("document.querySelector('.download-more')?.click()")
        screenshot("share-more-formats")
        check("More formats lists discovered heights", js("document.querySelectorAll('[data-option]').length===qualities.length+3") == "true")
        js("window.Quartz.back()")
        check("Back closes the share activity", activity!!.isFinishing)
        launch("invalid-video-link")
        waitUntil(20) { js("document.getElementById('download-sheet-content').textContent.includes('Could not load')") == "true" }
        check("Bad share has inline retry and disabled download", js("document.getElementById('download-sheet-confirm').disabled") == "true")
        screenshot("share-invalid-link")
    }
    private fun nm() = targetContext.getSystemService(NotificationManager::class.java)
    private fun snapshot(name: String) {
        val rows = JSONArray()
        nm().activeNotifications.filter { it.notification.channelId.startsWith("quartz_") }.forEach { item ->
            val n = item.notification
            rows.put(JSONObject().put("id", item.id).put("tag", item.tag).put("channel", n.channelId)
                .put("title", n.extras.getCharSequence("android.title")).put("text", n.extras.getCharSequence("android.text"))
                .put("progress", n.extras.getInt("android.progress")).put("progressMax",n.extras.getInt("android.progressMax"))
                .put("indeterminate", n.extras.getBoolean("android.progressIndeterminate")).put("flags", n.flags)
                .put("actions", JSONArray(n.actions?.map { it.title.toString() }.orEmpty())))
        }
        save(name, rows)
    }
    private fun notifications() {
        DownloadNotifications.channels(targetContext)
        val prefix = "test-021-"
        fun job(id: String, state: String) = JSONObject().put("id",prefix+id).put("state",state).put("title","Quartz notification check $id").put("filename","check.opus").put("message","Check your connection")
        DownloadNotifications.result(targetContext,job("one","complete"))
        DownloadNotifications.result(targetContext,job("two","complete"))
        DownloadNotifications.result(targetContext,job("error","failed"))
        Thread.sleep(1000)
        check("Each completion has a distinct persistent notification",nm().activeNotifications.count { it.tag?.startsWith(prefix)==true } == 3)
        val fail = nm().activeNotifications.first { it.tag == prefix+"error" }.notification
        check("Failure has Retry action",fail.actions.any { it.title == "Retry" })
        check("Result has no stale progress bar",fail.extras.getInt("android.progressMax") == 0)
        snapshot("notification-model.json")
        listOf("one","two","error").forEach { DownloadNotifications.dismiss(targetContext,prefix+it) }
    }
    private fun download() {
        nm().cancelAll()
        val before = DownloadStore.all(targetContext)
        val ids = (0 until before.length()).map { before.getJSONObject(it).optString("id") }.toSet()
        launch("https://youtu.be/UreOobGvswo")
        ready()
        js("document.querySelector('[data-option=\"audio:original\"]').click();document.getElementById('download-sheet-confirm').click()")
        var id = ""
        waitUntil(20) { val jobs = DownloadStore.all(targetContext); id = (0 until jobs.length()).map { jobs.getJSONObject(it).optString("id") }.firstOrNull { it !in ids }.orEmpty(); id.isNotBlank() }
        check("Share downloads requested original audio",DownloadStore.get(targetContext,id)?.optString("audioFormat") == "original")
        waitUntil(20) { activity!!.isFinishing }
        check("Share returns to watching app after queueing",activity!!.isFinishing)
        waitUntil { DownloadStore.get(targetContext,id)?.optString("state") in listOf("downloading","processing","complete","failed") }
        snapshot("live-progress.json")
        shell("cmd statusbar expand-notifications")
        Thread.sleep(1000); screenshot("live-notification")
        waitUntil(240) { DownloadStore.get(targetContext,id)?.optString("state") in listOf("complete","failed") }
        val job = DownloadStore.get(targetContext,id)!!; save("live-download.json",job)
        check("Real background original download completes",job.optString("state") == "complete")
        targetContext.contentResolver.openInputStream(android.net.Uri.parse(job.getString("uri")))!!.use { input ->
            File(evidence,"live-original."+job.getString("filename").substringAfterLast('.')).outputStream().use { input.copyTo(it) }
        }
        waitUntil(10) { nm().activeNotifications.any { it.tag == id } }
        snapshot("live-completion.json");screenshot("completed-notification")
        check("Completion still present after service ends",nm().activeNotifications.any { it.tag == id && it.notification.actions.any { a -> a.title == "Open" } })
        shell("cmd statusbar collapse")
        nm().activeNotifications.first { it.tag==id }.notification.actions.first { it.title=="Open" }.actionIntent.send()
        waitUntil(20) { uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.google.android.apps.youtube.music" }
        screenshot("notification-open-player")
        check("Notification Open launches Android audio player", uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.google.android.apps.youtube.music")
    }
    private fun permission() {
        targetContext.getSharedPreferences("quartz_ui",0).edit().putBoolean("notificationAsked",false).commit()
        val before = DownloadStore.all(targetContext)
        val previousIds = (0 until before.length()).map { before.getJSONObject(it).optString("id") }.toSet()
        launch("https://youtu.be/UreOobGvswo")
        ready();js("document.querySelector('[data-option=\"audio:mp3\"]').click();document.getElementById('download-sheet-confirm').click()")
        waitUntil(20) { uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("permissioncontroller") == true }
        screenshot("notification-permission")
        check("Permission prompt appears before download starts",!DownloadService.active)
        val root = uiAutomation.rootInActiveWindow
        val allow = root.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_allow_button").firstOrNull() ?: error("Allow button missing")
        allow.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        waitUntil(20) { DownloadService.active }
        check("Grant permission starts queued download",nm().areNotificationsEnabled())
        snapshot("permission-granted-notification.json")
        var id = ""
        waitUntil(20) { val jobs = DownloadStore.all(targetContext); id = (0 until jobs.length()).map { jobs.getJSONObject(it).optString("id") }.firstOrNull { it !in previousIds }.orEmpty();id.isNotBlank() }
        waitUntil(240) { DownloadStore.get(targetContext,id)?.optString("state") in listOf("complete","failed") }
        val job = DownloadStore.get(targetContext,id)!!; save("permission-mp3-download.json",job)
        check("Granted permission download completes as MP3",job.optString("state")=="complete" && job.optString("audioFormat")=="mp3")
        targetContext.contentResolver.openInputStream(android.net.Uri.parse(job.getString("uri")))!!.use { input ->
            File(evidence,"live-320.mp3").outputStream().use { input.copyTo(it) }
        }
    }

    private fun controls() {
        val before = DownloadStore.all(targetContext)
        val previous = (0 until before.length()).map { before.getJSONObject(it).optString("id") }.toSet()
        launch("https://youtu.be/UreOobGvswo", false);ready()
        js("QuartzNative.download(current,'best','video','original');QuartzNative.download(current,'best','audio','mp3')")
        var first = ""; var second = ""
        waitUntil(20) {
            val rows = DownloadStore.all(targetContext)
            val added = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("id") !in previous }
            first = added.firstOrNull { it.optString("mode")=="video" }?.optString("id").orEmpty()
            second = added.firstOrNull { it.optString("mode")=="audio" }?.optString("id").orEmpty()
            first.isNotBlank() && second.isNotBlank() && nm().activeNotifications.any { it.id==DownloadNotifications.ACTIVE_ID }
        }
        snapshot("queued-progress.json")
        waitUntil(15) { nm().activeNotifications.any { it.id==DownloadNotifications.ACTIVE_ID && it.notification.extras.getCharSequence("android.subText").toString().contains("1 waiting") } }
        val progress = nm().activeNotifications.first { it.id==DownloadNotifications.ACTIVE_ID }.notification
        check("Ongoing notification displays queued count",progress.extras.getCharSequence("android.subText").toString().contains("1 waiting"))
        progress.actions.first { it.title=="Cancel" }.actionIntent.send()
        waitUntil(20) { DownloadStore.get(targetContext,first)?.optString("state")=="cancelled" }
        check("Notification Cancel cancels active job",DownloadStore.get(targetContext,first)?.optString("state")=="cancelled")
        waitUntil(240) { DownloadStore.get(targetContext,second)?.optString("state") in listOf("complete","failed") }
        check("Next queued download completes after cancel",DownloadStore.get(targetContext,second)?.optString("state")=="complete")
        save("cancelled-job.json",DownloadStore.get(targetContext,first)!!)
        save("after-cancel-mp3-job.json",DownloadStore.get(targetContext,second)!!)
        val job = DownloadStore.get(targetContext,second)!!
        targetContext.contentResolver.openInputStream(android.net.Uri.parse(job.getString("uri")))!!.use { input ->
            File(evidence,"after-cancel-320.mp3").outputStream().use { input.copyTo(it) }
        }
        val failureId = "unavailable-021-"+System.currentTimeMillis()
        nm().cancelAll()
        val failure = JSONObject().put("id",failureId).put("url","https://www.youtube.com/watch?v=___________")
            .put("title","Unavailable video notification check").put("mode","audio").put("audioFormat","original")
            .put("quality","best").put("state","queued").put("type","media").put("createdAt",System.currentTimeMillis())
        DownloadStore.add(targetContext,failure)
        runOnMainSync { targetContext.startForegroundService(Intent(targetContext,DownloadService::class.java).putExtra(DownloadService.EXTRA_ID,failureId)) }
        waitUntil(180) { DownloadStore.get(targetContext,failureId)?.optString("state")=="failed" }
        waitUntil(20) { !DownloadService.active && nm().activeNotifications.any { it.tag==failureId } }
        check("Real extraction failure notification persists after service ends",nm().activeNotifications.any { it.tag==failureId })
        snapshot("real-failure-notifications.json")
        shell("cmd statusbar expand-notifications"); screenshot("failure-and-results-notifications")
        val failedNotice = nm().activeNotifications.first { it.tag==failureId }.notification
        // Reuse the failed job with a reachable video to verify the notification retry keeps the same job and audio choice.
        DownloadStore.update(targetContext,failureId,JSONObject().put("url","https://www.youtube.com/watch?v=UreOobGvswo"))
        check("Real failure notification contains Retry",failedNotice.actions.any { it.title=="Retry" })
        shell("cmd statusbar collapse")
        failedNotice.actions.first { it.title=="Retry" }.actionIntent.send()
        waitUntil(240) { DownloadStore.get(targetContext,failureId)?.optString("state")=="complete" }
        check("Notification Retry completes same job ID and original audio mode",DownloadStore.get(targetContext,failureId)?.optString("audioFormat")=="original")
        save("notification-retry-job.json",DownloadStore.get(targetContext,failureId)!!)
    }

    private fun denied() {
        targetContext.getSharedPreferences("quartz_ui",0).edit().putBoolean("notificationAsked",true).commit()
        launch("https://youtu.be/UreOobGvswo",false);ready()
        check("Settings reports disabled notifications",js("document.getElementById('notification-status').textContent.includes('off')")=="true")
        js("document.querySelector('[data-option=\"audio:original\"]').click();document.getElementById('download-sheet-confirm').click()")
        check("Previously denied permission does not block a download",!nm().areNotificationsEnabled())
        var job: JSONObject? = null
        waitUntil(240) {
            val rows=DownloadStore.all(targetContext);job=rows.optJSONObject(0)
            job?.optString("state") in listOf("complete","failed")
        }
        check("Download completes with notifications disabled",job?.optString("state")=="complete")
        js("showSettings(true)");screenshot("notifications-disabled-settings")
        js("document.getElementById('notification-enable').click()")
        waitUntil(20) { uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.settings" }
        check("Enable notifications opens Android controls after denial",uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.settings")
        screenshot("android-notification-settings")
    }

    private fun chooser() {
        launch("https://youtu.be/2z6pCa_xehY",false);ready()
        js("window.Quartz.back()")
        val monitor = addMonitor(ShareActivity::class.java.name,null,false)
        runOnMainSync {
            activity!!.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT,"https://youtu.be/2z6pCa_xehY"),"Share video"))
        }
        waitUntil(20) { uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Quartz Downloader")?.isNotEmpty()==true }
        screenshot("android-share-menu")
        var node = uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Quartz Downloader").first()
        while (!node.isClickable && node.parent != null) node = node.parent
        check("One Quartz share destination is registered",targetContext.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain"),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).count { it.activityInfo.packageName==targetContext.packageName }==1)
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        activity=monitor.waitForActivityWithTimeout(20000) as? MainActivity ?: error("Share destination did not open")
        ready()
        screenshot("share-menu-options")
        check("Tapping Quartz in Android Sharesheet opens the download popup",js("document.getElementById('download-sheet').open")=="true")
        js("window.Quartz.back()")
    }

    private fun video() {
        val before=DownloadStore.all(targetContext)
        val previous=(0 until before.length()).map { before.getJSONObject(it).optString("id") }.toSet()
        launch(if(phase=="slow-progress") "https://x.com/BrooklynNets/status/1349794411333394432" else "https://youtu.be/2z6pCa_xehY");ready()
        js("document.querySelector('[data-option=\"video:360\"]').click();document.getElementById('download-sheet-confirm').click()")
        var id=""
        waitUntil(20) { val rows=DownloadStore.all(targetContext);id=(0 until rows.length()).map{rows.getJSONObject(it).optString("id")}.firstOrNull{it !in previous}.orEmpty();id.isNotBlank() }
        waitUntil(180) { nm().activeNotifications.any { it.id==DownloadNotifications.ACTIVE_ID && it.notification.extras.getInt("android.progress") in 1..99 && !it.notification.extras.getBoolean("android.progressIndeterminate") } || DownloadStore.get(targetContext,id)?.optString("state") in listOf("complete","failed") }
        val progressSeen=nm().activeNotifications.any { it.id==DownloadNotifications.ACTIVE_ID && it.notification.extras.getInt("android.progress") in 1..99 && !it.notification.extras.getBoolean("android.progressIndeterminate") }
        if(phase=="slow-progress")check("Real slow video shows determinate progress above zero",progressSeen)
        else check("Real video publishes progress or completes before the next update",progressSeen || DownloadStore.get(targetContext,id)?.optString("state")=="complete")
        snapshot("video-progress.json")
        shell("cmd statusbar expand-notifications");Thread.sleep(1000);screenshot("video-progress-notification")
        waitUntil(300) { DownloadStore.get(targetContext,id)?.optString("state") in listOf("complete","failed") }
        val job=DownloadStore.get(targetContext,id)!!;save("video-360-job.json",job)
        check("Selected 360p video completes",job.optString("state")=="complete" && job.optString("quality")=="360")
        targetContext.contentResolver.openInputStream(android.net.Uri.parse(job.getString("uri")))!!.use { input ->
            File(evidence,"live-video-360.mp4").outputStream().use { input.copyTo(it) }
        }
        snapshot("video-completion.json");Thread.sleep(1000);screenshot("video-completed-notification")
    }

    private fun ui(){
        launch("",false)
        waitUntil(20){js("typeof AppUI === 'object'")=="true"}
        js("QuartzNative.setting('videoAudioFormat','original');QuartzNative.setting('spotifyAudioFormat','original');QuartzNative.theme('dark');refresh()")
        check("Four bottom tabs are present",js("document.querySelectorAll('.bottom-nav [data-tab]').length===4")=="true")
        check("Source logo is loaded with transparent asset",js("document.querySelector('.brand img').complete && document.querySelector('.brand img').naturalWidth>0")=="true")
        for(tab in listOf("browse","downloads","music","settings")){
            js("AppUI.navigate('$tab')")
            check("$tab has one selected navigation item",js("document.querySelectorAll('.bottom-nav .active').length===1 && document.getElementById('page-title').textContent.toLowerCase()==='$tab'")=="true")
            screenshot("ui-$tab")
            check("$tab has no horizontal overflow",js("document.documentElement.scrollWidth<=innerWidth")=="true")
        }
        check("Only X Facebook Instagram account actions",js("document.querySelectorAll('.account-row').length===3 && !document.body.textContent.includes('Sign in to Twitter')")=="true")
        js("document.querySelector('[name=best-audio][value=mp3]').click();refresh()")
        check("Audio preference applies to video and Spotify",js("JSON.parse(QuartzNative.state()).videoAudioFormat==='mp3'&&JSON.parse(QuartzNative.state()).spotifyAudioFormat==='mp3'")=="true")
        js("document.querySelector('[name=best-audio][value=original]').click();refresh()")
        js("document.getElementById('theme-light').click();refresh()")
        check("Light mode still applies",js("document.body.classList.contains('light')")=="true")
        screenshot("ui-settings-light")
        js("document.getElementById('theme-dark').click();AppUI.navigate('music')")
        waitUntil(10){js("JSON.parse(QuartzNative.state()).theme==='dark'")=="true"}
        js("document.getElementById('music-search').value='zzzz-no-song';document.getElementById('music-search').dispatchEvent(new Event('input'))")
        check("Music search has an honest empty result",js("document.getElementById('music-list').textContent.includes('No songs found')")=="true")
        js("document.getElementById('music-search').value='';document.getElementById('music-search').dispatchEvent(new Event('input'))")
        check("Back returns to Browse",js("Quartz.back() && AppUI.tab==='browse'")=="true")
    }
    private fun browserJs(browser:BrowserActivity,field:String,code:String):String{
        val latch=CountDownLatch(1);var value=""
        runOnMainSync{val f=BrowserActivity::class.java.getDeclaredField(field).apply{isAccessible=true};(f.get(browser) as WebView).evaluateJavascript(code){value=it;latch.countDown()}}
        check(latch.await(15,TimeUnit.SECONDS)){"Browser did not reply"};return value
    }
    private fun browser(){
        launch("",false)
        val base=activity!!
        val b=startActivitySync(Intent(targetContext,BrowserActivity::class.java).putExtra("provider","youtube").putExtra("query","project zomboid").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BrowserActivity
        waitUntil(30){browserJs(b,"header","typeof BrowserUI === 'object'")=="true"}
        waitUntil(75){browserJs(b,"web","document.readyState==='complete' && document.body.innerText.length>80")=="true"}
        check("Real YouTube search page loaded",browserJs(b,"web","location.href.includes('youtube.com/results')")=="true")
        check("Website has no native application bridge",browserJs(b,"web","typeof QuartzNative==='undefined' && typeof BrowserNative==='undefined'")=="true")
        screenshot("browser-search-live")
        browserJs(b,"header","BrowserNative.search('web','project zomboid')")
        waitUntil(75){browserJs(b,"web","location.hostname.includes('google.com') && document.readyState==='complete'")=="true"}
        save("browser-google-location.json",browserJs(b,"web","location.href"))
        check("Provider tab opens actual Google videos search",browserJs(b,"web","location.search.includes('tbm=vid')||location.search.includes('udm=7')||location.search.includes('udm=vids')")=="true")
        screenshot("browser-google-live")
        browserJs(b,"header","BrowserNative.search('youtube','https://youtu.be/2z6pCa_xehY')")
        waitUntil(45){browserJs(b,"web","/youtube\\.com|youtu\\.be/.test(location.hostname)")=="true"}
        waitUntil(30){browserJs(b,"footer","!document.getElementById('browser-download').classList.contains('hidden')")=="true"}
        waitUntil(75){browserJs(b,"web","document.readyState==='complete' && document.body.innerText.length>100")=="true"}
        screenshot("browser-video-live")
        val monitor=addMonitor(ShareActivity::class.java.name,null,false)
        browserJs(b,"footer","BrowserNative.action('download')")
        activity=monitor.waitForActivityWithTimeout(20000) as? MainActivity ?: error("Browser download sheet did not open")
        ready();screenshot("browser-download-sheet")
        check("Browser download uses share options",activity is ShareActivity&&js("document.getElementById('download-sheet').open")=="true")
        check("Sheet keeps actual browser beneath it",!b.isFinishing)
        js("Quartz.back()")
        runOnMainSync{b.finish();base.finish()};waitForIdleSync()
        for(site in listOf("x","facebook","instagram")){
            launch("",false)
            val m=addMonitor(BrowserActivity::class.java.name,null,false);js("QuartzNative.signIn('$site')")
            val login=m.waitForActivityWithTimeout(20000) as? BrowserActivity ?: error("Sign in browser missing")
            waitUntil(30){browserJs(login,"header","typeof BrowserUI === 'object'")=="true"}
            check("$site sign-in opens actual website",login.intent.getStringExtra("url")!!.contains(if(site=="x")"x.com" else "$site.com"))
            waitUntil(45){browserJs(login,"web","document.readyState==='complete' && document.body.innerText.length>20")=="true"}
            screenshot("browser-login-$site")
            check("$site website cannot invoke app controls",browserJs(login,"web","typeof BrowserNative==='undefined'")=="true")
            browserJs(login,"header","BrowserNative.action('done')");waitUntil(10){login.isFinishing}
        }
    }
    private fun artwork(){
        val jobs=DownloadStore.all(targetContext);val row=(0 until jobs.length()).map{jobs.getJSONObject(it)}.firstOrNull{it.optString("state")=="complete"&&it.optString("artworkState")=="embedded"&&it.optString("audioFormat")=="original"} ?: error("Original audio with art missing")
        val retriever=android.media.MediaMetadataRetriever();retriever.setDataSource(targetContext,android.net.Uri.parse(row.getString("uri")))
        check("Android reads the artwork embedded in original audio",(retriever.embeddedPicture?.size ?: 0)>1000)
        check("Android reads the tagged song title",!retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE).isNullOrBlank())
        retriever.embeddedPicture?.let{File(evidence,"embedded-original-cover.jpg").writeBytes(it)};retriever.release()
        check("Cover cache belongs to this download",File(row.getString("artworkPath")).length()>1000)
        save("artwork-original-job.json",row)
        // Remux a source without art and keep both files for encoded packet comparison.
        MediaEngine.prepare(targetContext);com.yausername.ffmpeg.FFmpeg.init(targetContext)
        val dir=File(targetContext.filesDir,"artwork-test").apply{mkdirs()};val before=File(dir,"before.opus")
        val old=(0 until jobs.length()).map{jobs.getJSONObject(it)}.firstOrNull{it.optString("state")=="complete"&&it.optString("mode")=="audio"&&it.optString("filename").endsWith(".opus")&&!it.has("artworkState")} ?: row
        targetContext.contentResolver.openInputStream(android.net.Uri.parse(old.getString("uri")))!!.use{input->before.outputStream().use{input.copyTo(it)}}
        before.copyTo(File(evidence,"artwork-before.opus"),true)
        val job=JSONObject().put("id","art-packet-test").put("title",row.optString("title")).put("thumbnail",row.getString("thumbnail"))
        AudioArtwork.embed(targetContext,job,before,JSONObject().put("title",row.optString("title")).put("artist",row.optString("musicArtist")))
        before.copyTo(File(evidence,"artwork-after.opus"),true);dir.deleteRecursively()
        check("Artwork remux produces playable output",File(evidence,"artwork-after.opus").length()>1000)
    }
    private fun spotify(){
        launch("",false)
        val summary=JSONArray()
        for(id in listOf("2QGJRF9Tf537Z5JXbB16Kd","62ppawlNpdv2tRMzJj01NV")){
            js("AppUI.navigate('downloads');QuartzNative.loadPlaylist('https://open.spotify.com/playlist/$id')")
            waitUntil(180){PlaylistStore.get(targetContext).optString("status") in listOf("ready","empty")}
            val p=PlaylistStore.get(targetContext);check("Playlist $id imports all tracks",p.optString("status")=="ready"&&p.getJSONArray("tracks").length()+p.optInt("unavailable")==p.getInt("total"))
            check("Playlist $id has actual song artwork",p.getJSONArray("tracks").getJSONObject(0).getString("thumbnail").startsWith("https://"))
            summary.put(JSONObject().put("id",id).put("title",p.optString("title")).put("total",p.optInt("total")))
            if(id=="62ppawlNpdv2tRMzJj01NV"){
                js("QuartzNative.findSpotifyMatches()")
                waitUntil(600){PlaylistStore.get(targetContext).optString("status")=="ready"}
                val matched=PlaylistStore.get(targetContext);val tracks=matched.getJSONArray("tracks")
                check("Every song in second playlist receives candidates",(0 until tracks.length()).all{tracks.getJSONObject(it).getJSONArray("candidates").length()>0})
                val oldJobs=DownloadStore.all(targetContext)
                val first=(0 until tracks.length()).map{tracks.getJSONObject(it)}.first{t->(0 until oldJobs.length()).none{oldJobs.getJSONObject(it).optString("spotifyTrackUri")==t.getString("uri")&&oldJobs.getJSONObject(it).optString("state")=="complete"}};val url=first.getJSONArray("candidates").getJSONObject(0).getString("url")
                val sampleKeys=JSONArray()
                for(sample in (0 until tracks.length()).map{tracks.getJSONObject(it)}.filter{t->(0 until oldJobs.length()).none{oldJobs.getJSONObject(it).optString("spotifyTrackUri")==t.getString("uri")&&oldJobs.getJSONObject(it).has("artworkState")}}.take(3)){
                    val selected=sample.getJSONArray("candidates").getJSONObject(0).getString("url");js("QuartzNative.chooseSpotifyMatch('${sample.getString("key")}','$selected')");sampleKeys.put(sample.getString("key"))
                }
                val key=first.getString("key");js("QuartzNative.chooseSpotifyMatch('$key','$url');QuartzNative.downloadSpotify(${JSONObject.quote(sampleKeys.toString())},'original')")
                var job:JSONObject?=null
                waitUntil(300){val rows=DownloadStore.all(targetContext);job=(0 until rows.length()).map{rows.getJSONObject(it)}.firstOrNull{it.optString("spotifyTrackUri")==first.getString("uri")&&it.optString("state") in listOf("complete","failed")&&it.has("artworkState")};job!=null}
                check("Spotify matched download includes embedded album art",job!!.optString("state")=="complete"&&job!!.optString("artworkState")=="embedded")
                check("Spotify album art used rather than YouTube thumbnail",job!!.getString("thumbnail")==first.getString("thumbnail"))
                val meta=android.media.MediaMetadataRetriever();meta.setDataSource(targetContext,android.net.Uri.parse(job!!.getString("uri")));check("Spotify artwork is inside the actual audio file",(meta.embeddedPicture?.size ?:0)>1000);meta.release()
                save("spotify-artwork-job.json",job!!);targetContext.contentResolver.openInputStream(android.net.Uri.parse(job!!.getString("uri")))!!.use{input->File(evidence,"spotify-artwork."+job!!.getString("filename").substringAfterLast('.')).outputStream().use{input.copyTo(it)}}
                screenshot("spotify-playlist-live")
                waitUntil(600){!DownloadService.active}
                val completed=DownloadStore.all(targetContext)
                check("Spotify sample batch all completes with artwork",(0 until completed.length()).map{completed.getJSONObject(it)}.filter{it.optString("spotifyPlaylist")==matched.getString("title")&&it.has("artworkState")}.all{it.optString("state")=="complete"&&it.optString("artworkState")=="embedded"})
            }
        };save("spotify-current-imports.json",summary)
    }
    private fun playback(){
        launch("",false)
        js("AppUI.navigate('music')")
        check("Completed songs populate real library",js("document.querySelectorAll('[data-music-id]').length>1")=="true")
        val jobs=DownloadStore.all(targetContext);val row=(0 until jobs.length()).map{jobs.getJSONObject(it)}.first{it.optString("state")=="complete"&&it.optString("mode")=="audio"&&it.has("artworkState")}
        val id=row.getString("id");js("QuartzNative.play('$id')")
        waitUntil(30){PlaybackService.state().optString("state")=="playing"}
        val start=PlaybackService.state().optInt("position");waitUntil(20){PlaybackService.state().optInt("position")>start+800}
        check("Built-in player actually advances",PlaybackService.state().optInt("position")>start+800)
        check("Player has Android media notification",nm().activeNotifications.any{it.id==1200 && it.notification.actions.size==3})
        js("document.getElementById('mini-open').click()")
        check("Mini player opens full player",js("document.getElementById('player-sheet').open")=="true");screenshot("music-player-live")
        js("document.getElementById('player-toggle').click()")
        waitUntil(10){PlaybackService.state().optString("state")=="paused"}
        val paused=PlaybackService.state().optInt("position");Thread.sleep(1100);check("Pause stops actual position",kotlin.math.abs(PlaybackService.state().optInt("position")-paused)<150)
        js("QuartzNative.playerAction('seek',30000)");waitUntil(10){PlaybackService.state().optInt("position") in 29800..30200};check("Seek moves playback to selected time",true)
        nm().activeNotifications.first{it.id==1200}.notification.actions.first{it.title=="Play"}.actionIntent.send()
        waitUntil(10){PlaybackService.state().optString("state")=="playing"};check("Android music notification Play works",true)
        nm().activeNotifications.first{it.id==1200}.notification.actions.first{it.title=="Next"}.actionIntent.send()
        waitUntil(30){PlaybackService.state().optString("id")!=id && PlaybackService.state().optString("state")=="playing"};check("Android music notification Next changes song",true)
        js("document.getElementById('player-close').click();refresh()")
        screenshot("music-library-playing")
        val current=PlaybackService.state().optString("id")
        val saved=nm().activeNotifications.first{it.id==1200}.notification.contentIntent
        js("AppUI.navigate('downloads')");saved.send();waitUntil(10){js("AppUI.tab==='music'")=="true"};check("Music notification opens Music tab",true)
        val audio=targetContext.getSystemService(android.media.AudioManager::class.java)
        val competing=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()).setOnAudioFocusChangeListener{}.build()
        runOnMainSync{audio.requestAudioFocus(competing)}
        waitUntil(10){PlaybackService.state().optString("state")=="paused"};check("Another audio owner pauses playback",true)
        runOnMainSync{audio.abandonAudioFocusRequest(competing)}
        js("QuartzNative.playerAction('stop',0)");waitUntil(10){PlaybackService.state().optString("state")=="stopped"};check("Stop removes player and music notification",nm().activeNotifications.none{it.id==1200})
        js("document.getElementById('music-playlists').click()")
        check("Library groups actual Spotify playlist songs",js("document.querySelectorAll('.playlist-row').length>0")=="true");screenshot("music-playlists")
    }
    private fun providers(){
        launch("https://x.com/Tsmith007z/status/2105719499240722909",false)
        waitUntil(180){js("!!current || document.getElementById('download-sheet-content').textContent.includes('Could not load')")=="true"}
        if(js("!!current")=="false"){
            check("Unavailable user X post gives sign-in recovery",js("document.getElementById('download-sheet-content').textContent.includes('Sign in to X')&&document.getElementById('download-sheet-confirm').disabled")=="true")
            save("user-x-post-availability.json",js("JSON.stringify({url:document.getElementById('link').value,message:document.getElementById('download-sheet-content').textContent})"));screenshot("user-x-post-sign-in-recovery")
        }
        val links=listOf("x" to "https://x.com/BrooklynNets/status/1349794411333394432", "tiktok" to "https://www.tiktok.com/@ikirax_/video/7676888627007311124", "facebook" to "https://www.facebook.com/100033620354545/videos/106560053808006/", "instagram" to "https://www.instagram.com/reel/Chunk8-jurw/")
        val results=JSONArray()
        for((site,url) in links){
            val previous=DownloadStore.all(targetContext);val ids=(0 until previous.length()).map{previous.getJSONObject(it).getString("id")}.toSet()
            launch(url,false);ready();screenshot("provider-$site-options")
            if(site=="instagram")check("Silent Instagram clip does not offer unusable audio choices",js("document.querySelectorAll('[data-option^=\"audio:\"]').length===0")=="true")
            if(site=="x")js("document.querySelector('[data-option=\"video:360\"]')?.click()")
            js("document.getElementById('download-sheet-confirm').click()")
            var row:JSONObject?=null
            waitUntil(300){val jobs=DownloadStore.all(targetContext);row=(0 until jobs.length()).map{jobs.getJSONObject(it)}.firstOrNull{it.getString("id") !in ids && it.optString("state") in listOf("complete","failed")};row!=null}
            check("$site actual video downloads and saves",row!!.optString("state")=="complete")
            if(site=="tiktok")check("TikTok selector excludes watermark download variant",!row!!.optString("selector").split('/').contains("download"))
            targetContext.contentResolver.openInputStream(android.net.Uri.parse(row!!.getString("uri")))!!.use{input->File(evidence,"provider-$site."+row!!.getString("filename").substringAfterLast('.')).outputStream().use{input.copyTo(it)}}
            results.put(row);snapshot("provider-$site-notifications.json")
        };save("providers-downloads.json",results)
    }
    private fun persistence(){
        launch("",false)
        val prefix="retention-030-";val fixtures=(0 until 170).map{JSONObject().put("id",prefix+it).put("mode","audio").put("type","media").put("state","complete").put("title","Retention test $it")}
        try{
            DownloadStore.addAll(targetContext,fixtures)
            DownloadStore.add(targetContext,JSONObject().put("id",prefix+"new").put("type","media").put("state","failed"))
            check("Library retains more than 150 saved songs",(0 until 170).all{DownloadStore.get(targetContext,prefix+it)!=null})
        }finally{(fixtures.map{it.getString("id")}+(prefix+"new")).forEach{DownloadStore.remove(targetContext,it)}}
        val prefs=targetContext.getSharedPreferences("quartz_ui",0);val original=prefs.getString("videoAudioFormat","original")
        js("QuartzNative.setting('videoAudioFormat','mp3')")
        launch("",false)
        check("Audio preference survives Activity recreation",js("JSON.parse(QuartzNative.state()).videoAudioFormat==='mp3'")=="true")
        js("QuartzNative.setting('videoAudioFormat','$original')")
        val interrupted="interrupted-030";DownloadStore.add(targetContext,JSONObject().put("id",interrupted).put("type","media").put("state","processing"))
        DownloadStore.recoverInterrupted(targetContext)
        check("Interrupted processing returns a recoverable failure",DownloadStore.get(targetContext,interrupted)?.optString("state")=="failed");DownloadStore.remove(targetContext,interrupted)
        val cookie=android.webkit.CookieManager.getInstance()
        val latch=CountDownLatch(3)
        runOnMainSync{for((url,value) in listOf("https://x.com/" to "auth_token=quartz-test-not-an-account; Domain=.x.com; Path=/; Secure; HttpOnly", "https://www.facebook.com/" to "c_user=quartz-test-not-an-account; Domain=.facebook.com; Path=/; Secure; HttpOnly", "https://www.instagram.com/" to "sessionid=quartz-test-not-an-account; Domain=.instagram.com; Path=/; Secure; HttpOnly")){cookie.setCookie(url,value){latch.countDown()}}}
        check(latch.await(10,TimeUnit.SECONDS)){"Cookie fixture did not save"}
        try{
            runOnMainSync{cookie.flush()}
            for(url in listOf("https://x.com/test/status/1","https://www.facebook.com/videos/1","https://www.instagram.com/reel/test/")){
                val file=BrowserCookies.create(targetContext,url) ?: error("Session export missing")
                check("HttpOnly session passes to ${android.net.Uri.parse(url).host} extractor",file.readText().contains("quartz-test-not-an-account")&&file.parentFile?.canonicalPath==File(targetContext.cacheDir,"sessions").canonicalPath)
                BrowserCookies.cleanInactive(targetContext);check("Active session survives a second activity startup for ${android.net.Uri.parse(url).host}",file.exists());BrowserCookies.release(file)
            }
        }finally{
            val cleanup=CountDownLatch(3)
            runOnMainSync{for((url,name) in listOf("https://x.com/" to "auth_token","https://www.facebook.com/" to "c_user","https://www.instagram.com/" to "sessionid")){val domain=android.net.Uri.parse(url).host!!.removePrefix("www.");cookie.setCookie(url,"$name=; Domain=.$domain; Path=/; Max-Age=0; Secure"){cleanup.countDown()}}}
            cleanup.await(10,TimeUnit.SECONDS);runOnMainSync{cookie.flush()}
        }
        check("Temporary session files are deleted",File(targetContext.cacheDir,"sessions").listFiles().orEmpty().isEmpty())
    }
    private fun rotation(){
        launch("https://youtu.be/2z6pCa_xehY",false);ready()
        val original=activity!!
        try{
            runOnMainSync{original.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            waitUntil(15){targetContext.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            waitUntil(15){js("innerWidth>innerHeight")=="true"}
            check("Rotation preserves the loaded format sheet",!original.isDestroyed&&js("document.getElementById('download-sheet').open && !!current")=="true")
            check("Landscape sheet has no horizontal overflow",js("document.documentElement.scrollWidth<=innerWidth")=="true")
            check("Landscape keeps Download button visible",js("document.getElementById('download-sheet-confirm').getBoundingClientRect().bottom<=innerHeight+1")=="true")
            save("rotation-layout.json",js("JSON.stringify({width:innerWidth,height:innerHeight,density:devicePixelRatio,sheet:document.getElementById('download-sheet').getBoundingClientRect().toJSON(),button:document.getElementById('download-sheet-confirm').getBoundingClientRect().toJSON()})"))
            val drawn=CountDownLatch(1)
            runOnMainSync{val field=MainActivity::class.java.getDeclaredField("web").apply{isAccessible=true};(field.get(original) as WebView).postVisualStateCallback(1,object:WebView.VisualStateCallback(){override fun onComplete(id:Long){(field.get(original) as WebView).invalidate();drawn.countDown()}})}
            check(drawn.await(10,TimeUnit.SECONDS)){"Landscape render did not commit"};Thread.sleep(1700)
            screenshot("share-options-landscape")
            runOnMainSync{original.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            waitUntil(15){targetContext.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
            waitUntil(15){js("innerHeight>innerWidth")=="true"}
            js("Quartz.back();AppUI.navigate('settings')")
            check("Returning to portrait retains navigation",js("AppUI.tab==='settings'")=="true")
        }finally{runOnMainSync{original.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}}
    }
    private fun playlistShare(){
        launch("https://youtu.be/2z6pCa_xehY",false);ready()
        js("Quartz.receive('sharedLink',{url:'https://open.spotify.com/playlist/62ppawlNpdv2tRMzJj01NV'})")
        waitUntil(180){PlaylistStore.get(targetContext).optString("status")=="ready"}
        waitUntil(10){js("!document.getElementById('download-sheet').open && !document.getElementById('spotify-panel').classList.contains('hidden')")=="true"}
        check("Incoming playlist replaces an existing video sheet",js("document.getElementById('spotify-title').textContent==='Summer'")=="true")
        launch("https://youtu.be/2z6pCa_xehY",false);ready();js("Quartz.back();document.getElementById('download').click()")
        check("Preview reopens the approved format sheet",js("document.getElementById('download-sheet').open && !document.getElementById('quality-sheet').open")=="true")
        js("Quartz.back();QuartzNative.setting('askQuality','false');refresh();document.getElementById('link').value='https://youtu.be/UreOobGvswo';inspect(false)")
        waitUntil(180){js("typeof current!=='undefined' && !!current")=="true"}
        check("Manual preview honors disabled automatic options",js("!document.getElementById('download-sheet').open && !document.getElementById('preview-card').classList.contains('hidden')")=="true")
        js("document.getElementById('download').click()")
        check("Manual preview retains usable discovered format data",js("document.getElementById('download-sheet').open && !document.getElementById('download-sheet-confirm').disabled")=="true")
        js("Quartz.back();QuartzNative.setting('askQuality','true');refresh();inspect()")
        ready();check("Automatic options preference actually opens the sheet",js("document.getElementById('download-sheet').open")=="true")
        js("QuartzNative.setting('askQuality','false')")
    }
    private fun matchingFirst(){
        launch("",false);js("QuartzNative.loadPlaylist('https://open.spotify.com/playlist/2QGJRF9Tf537Z5JXbB16Kd')")
        waitUntil(180){PlaylistStore.get(targetContext).optString("status")=="ready"}
        js("QuartzNative.findSpotifyMatches()")
        waitUntil(1200){val state=PlaylistStore.get(targetContext);save("matching-first-progress.json",JSONObject().put("status",state.optString("status")).put("message",state.optString("message")));state.optString("status")=="ready"}
        val state=PlaylistStore.get(targetContext);val rows=state.getJSONArray("tracks")
        val counts=JSONObject().put("total",rows.length())
        for(status in listOf("matched","review","unmatched"))counts.put(status,(0 until rows.length()).count{rows.getJSONObject(it).optString("status")==status})
        save("matching-first-counts.json",counts);save("matching-first-results.json",JSONObject().put("passed",true).put("checks",checks).put("counts",counts))
        check("All 110 first-playlist songs receive a search result",rows.length()==110&&(0 until rows.length()).all{rows.getJSONObject(it).optString("status") in listOf("matched","review","unmatched")})
        check("First-playlist misses are shown explicitly",(0 until rows.length()).all{val row=rows.getJSONObject(it);row.optString("status")!="unmatched"||row.optString("message").isNotBlank()})
    }
    private fun highVideo(){
        val before=DownloadStore.all(targetContext);val old=(0 until before.length()).map{before.getJSONObject(it).getString("id")}.toSet()
        launch("https://youtu.be/2z6pCa_xehY");ready()
        check("User video reveals up to 1440p60",js("qualities.some(q=>q.height===1440 && q.fps===60)")=="true")
        js("document.querySelector('.download-more').click();document.querySelector('[data-option=\"video:1080\"]').click();document.getElementById('download-sheet-confirm').click()")
        var row:JSONObject?=null
        waitUntil(600){val rows=DownloadStore.all(targetContext);row=(0 until rows.length()).map{rows.getJSONObject(it)}.firstOrNull{it.getString("id") !in old&&it.optString("state") in listOf("complete","failed")};row!=null}
        check("Actual 1080p download completes",row!!.optString("state")=="complete"&&row!!.getString("quality")=="1080")
        val meta=android.media.MediaMetadataRetriever();meta.setDataSource(targetContext,android.net.Uri.parse(row!!.getString("uri")));check("Android sees real 1080p resolution",meta.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)=="1080");meta.release()
        targetContext.contentResolver.openInputStream(android.net.Uri.parse(row!!.getString("uri")))!!.use{input->File(evidence,"live-video-1080.mp4").outputStream().use{input.copyTo(it)}};save("video-1080-job.json",row!!)
    }
}
