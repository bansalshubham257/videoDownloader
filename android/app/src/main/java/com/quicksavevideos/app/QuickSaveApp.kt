package com.quicksavevideos.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.quicksavevideos.app.ads.AdsManager

class QuickSaveApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "quicksave_downloads"
        const val NOTIFICATION_CHANNEL_NAME = "Download Status"
        const val FOREGROUND_NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AdsManager.init(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows video download progress and completion"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
