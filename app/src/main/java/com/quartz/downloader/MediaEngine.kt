package com.quartz.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL

object MediaEngine {
    @Synchronized fun prepare(context: Context) {
        YoutubeDL.init(context.applicationContext)
        val prefs = context.getSharedPreferences("quartz_engine", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastSuccessfulUpdate", 0) < 24 * 60 * 60 * 1000L) return
        try {
            YoutubeDL.updateYoutubeDL(context.applicationContext)
            prefs.edit().putLong("lastSuccessfulUpdate", now).apply()
        } catch (_: Exception) {
            // The bundled extractor can still work while an update is unavailable.
        }
    }
}
