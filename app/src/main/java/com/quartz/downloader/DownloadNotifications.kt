package com.quartz.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import org.json.JSONObject

object DownloadNotifications {
    const val ACTIVE_ID = 1042
    const val PROGRESS = "quartz_progress_v2"
    private const val RESULTS = "quartz_results_v2"
    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun channels(context: Context) {
        manager(context).createNotificationChannels(listOf(
            NotificationChannel(PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress and controls for current downloads"
            },
            NotificationChannel(RESULTS, "Download results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Finished downloads and downloads that need attention"
            }
        ))
    }

    fun status(context: Context): JSONObject {
        val nm = manager(context)
        return JSONObject().put("enabled", nm.areNotificationsEnabled())
            .put("progress", nm.getNotificationChannel(PROGRESS)?.importance != NotificationManager.IMPORTANCE_NONE)
            .put("results", nm.getNotificationChannel(RESULTS)?.importance != NotificationManager.IMPORTANCE_NONE)
    }

    private fun activity(context: Context, id: String, extra: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(android.net.Uri.parse("quartz://downloads/${extra ?: "list"}/$id"))
        if (extra != null) intent.putExtra(extra, id)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun base(context: Context, channel: String, job: JSONObject): Notification.Builder =
        Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_download_notification)
            .setColor(Color.rgb(151, 106, 230)).setContentTitle(job.optString("title", "Quartz Downloader"))
            .setContentIntent(activity(context, job.optString("id")))
            .setVisibility(Notification.VISIBILITY_PRIVATE)

    fun progress(context: Context, job: JSONObject, waiting: Int): Notification {
        val id = job.optString("id")
        val cancel = PendingIntent.getService(context, 0,
            Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL)
                .setData(android.net.Uri.parse("quartz://cancel/$id")).putExtra(DownloadService.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val state = job.optString("state")
        val pause = PendingIntent.getService(context, 0,
            Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE)
                .setData(android.net.Uri.parse("quartz://pause/$id")).putExtra(DownloadService.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val percent = job.optInt("progress").coerceIn(0, 99)
        val message = when (state) {
            "downloading" -> "$percent%" + job.optString("transferDetail").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "saving" -> "Saving to Downloads…"
            "processing" -> "Preparing your audio / video…"
            "waiting" -> "Waiting to reconnect · progress saved"
            "paused" -> "Paused · progress saved"
            else -> "Preparing download…"
        }
        val builder = base(context, PROGRESS, job).setContentText(message)
            .setContentTitle(job.optString("title","Quartz Downloader")+if(state=="downloading") " · Downloading" else "")
            .setSubText(if (waiting > 0) "$waiting waiting" else "Quartz Downloader")
            .setCategory(Notification.CATEGORY_PROGRESS).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, percent, state != "downloading")
            .setWhen(job.optLong("createdAt", System.currentTimeMillis()))
            .addAction(Notification.Action.Builder(null, "Pause", pause).build())
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    fun update(context: Context, job: JSONObject, waiting: Int) = manager(context).notify(ACTIVE_ID, progress(context, job, waiting))

    fun result(context: Context, job: JSONObject) {
        val id = job.optString("id")
        val complete = job.optString("state") == "complete"
        val cancelled = job.optString("state") == "cancelled"
        val paused = job.optString("state") == "paused"
        val message = when {
            complete -> "Download complete · ${job.optString("filename") }"
            cancelled -> "Download cancelled"
            paused -> "Download paused · progress saved"
            else -> "Download failed · ${job.optString("message") }"
        }
        val builder = base(context, RESULTS, job).setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message)).setAutoCancel(true)
            .setCategory(if (complete) Notification.CATEGORY_STATUS else Notification.CATEGORY_ERROR)
        if(complete){
            builder.setContentTitle(job.optString("musicTitle",job.optString("title"))+" · Download complete")
                .setContentText(if(job.optString("mode")=="audio") { if(job.optString("audioFormat")=="original") "Original audio saved" else "MP3 · 320 kbps saved" } else "Video saved to Downloads")
        }else if(paused){builder.setContentTitle(job.optString("title") + " · Paused").setContentText("Progress saved. Tap Resume to continue.")}
        else if(!cancelled){builder.setContentTitle("Download needs attention").setContentText(job.optString("message")).setSubText(job.optString("title"))}
        job.optString("artworkPath").takeIf { it.isNotBlank() }?.let { android.graphics.BitmapFactory.decodeFile(it)?.let(builder::setLargeIcon) }
        if (complete) {
            val open = activity(context, id, "quartz_open_id")
            builder.setContentIntent(open).addAction(Notification.Action.Builder(null, "Open", open).build())
        } else if (paused) {
            val resume = PendingIntent.getForegroundService(context, 0,
                Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_RESUME)
                    .setData(android.net.Uri.parse("quartz://resume/$id")).putExtra(DownloadService.EXTRA_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, "Resume", resume).build())
        } else if (!cancelled) {
            builder.addAction(Notification.Action.Builder(null, "Retry", activity(context, id, "quartz_retry_id")).build())
        }
        builder.addAction(Notification.Action.Builder(null, "Downloads", activity(context, id)).build())
        // A tag for each job prevents one completion from replacing another.
        manager(context).notify(id, 1, builder.build())
    }

    fun dismiss(context: Context, id: String) = manager(context).cancel(id, 1)
}
