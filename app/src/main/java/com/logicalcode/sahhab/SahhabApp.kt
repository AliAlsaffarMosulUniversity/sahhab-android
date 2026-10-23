package com.logicalcode.sahhab

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class SahhabApp : Application() {

    companion object {
        @Volatile var engineReady = false
        @Volatile var engineError: String? = null
        @Volatile var engineVersion: String? = null
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
    }

    override fun onCreate() {
        super.onCreate()
        Store.load(this)
        createChannels()
        // تجهيز محرك التنزيل (بايثون + yt-dlp + ffmpeg) في الخلفية
        Thread {
            try {
                YoutubeDL.init(this)
                FFmpeg.init(this)
                engineReady = true
                engineVersion = YoutubeDL.versionName(this)
                Store.notifyChanged()
                autoUpdate()
            } catch (e: Exception) {
                engineError = e.message ?: e.toString()
                Store.notifyChanged()
            }
        }.start()
    }

    // المواقع تغيّر طريقتها باستمرار، لذلك نحدّث المحرك كل ثلاثة أيام
    private fun autoUpdate() {
        val p = getSharedPreferences("sahhab", MODE_PRIVATE)
        val last = p.getLong("lastUpdate", 0L)
        if (System.currentTimeMillis() - last < 3L * 24 * 3600 * 1000) return
        try {
            YoutubeDL.updateYoutubeDL(this, YoutubeDL.UpdateChannel.STABLE)
            p.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
            engineVersion = YoutubeDL.versionName(this)
            Store.notifyChanged()
        } catch (_: Exception) {
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "التنزيلات الجارية", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "التنزيلات المكتملة", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}
