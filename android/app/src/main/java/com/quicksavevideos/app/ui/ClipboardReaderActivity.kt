package com.quicksavevideos.app.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.quicksavevideos.app.QuickSaveApp
import com.quicksavevideos.app.R
import com.quicksavevideos.app.download.DownloadManager
import com.quicksavevideos.app.service.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class ClipboardReaderActivity : Activity() {

    private var downloadManager: DownloadManager? = null
    private var serverUrl: String = "https://quicksavevideos.com"
    private var autoDownload = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        serverUrl = intent.getStringExtra("server_url") ?: "https://quicksavevideos.com"
        downloadManager = DownloadManager(this)
        autoDownload = intent.getBooleanExtra("auto_download", false)

        if (autoDownload) {
            setContentView(R.layout.activity_clipboard_reader)
            window.decorView.setBackgroundColor(Color.TRANSPARENT)
            window.decorView.alpha = 0f
        } else {
            setContentView(R.layout.activity_clipboard_reader)
        }
    }

    override fun onResume() {
        super.onResume()
        if (autoDownload) {
            Handler(Looper.getMainLooper()).postDelayed({
                readClipboardAndDownload()
            }, 200)
        }
    }

    private fun readClipboardAndDownload() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var text = ""
        try {
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
            }
        } catch (e: Exception) {
            showNotification("Could not read clipboard")
            finish()
            return
        }

        if (text.isNotEmpty() && text.startsWith("http")) {
            val notificationId = System.currentTimeMillis().toInt()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, buildNotification("Downloading...", 0, ongoing = true))
            finish()

            val dm = downloadManager ?: return
            GlobalScope.launch(Dispatchers.IO) {
                val result = dm.downloadVideo(
                    serverUrl = serverUrl,
                    videoUrl = text,
                    onProgress = { progress ->
                        try {
                            val label = if (progress < 100) "Downloading... $progress%" else "Saving..."
                            OverlayService.updateStatus(label)
                            val notif = buildNotification(
                                label, progress, ongoing = progress < 100
                            )
                            notificationManager.notify(notificationId, notif)
                        } catch (_: Exception) {}
                    }
                )
                OverlayService.updateStatus(
                    if (result.success) "Downloaded \u2713" else "Failed"
                )
                try {
                    val finalNotif = if (result.success) {
                        NotificationCompat.Builder(this@ClipboardReaderActivity, QuickSaveApp.NOTIFICATION_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle("Download complete \u2713")
                            .setContentText("Video saved to gallery")
                            .setAutoCancel(true)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .build()
                    } else {
                        NotificationCompat.Builder(this@ClipboardReaderActivity, QuickSaveApp.NOTIFICATION_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle("Download failed \u2717")
                            .setContentText(result.message)
                            .setAutoCancel(true)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .build()
                    }
                    notificationManager.notify(notificationId, finalNotif)
                } catch (_: Exception) {}
            }
        } else {
            val msg = if (text.isEmpty()) "Clipboard is empty \u2014 copy a video link first" else "Not a valid link"
            showNotification(msg)
            finish()
        }
    }

    private fun showNotification(text: String) {
        try {
            val notif = NotificationCompat.Builder(this, QuickSaveApp.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("QuickSave")
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            getSystemService(Context.NOTIFICATION_SERVICE).let {
                (it as NotificationManager).notify(System.currentTimeMillis().toInt(), notif)
            }
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String, progress: Int, ongoing: Boolean): android.app.Notification {
        val builder = NotificationCompat.Builder(this, QuickSaveApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("QuickSave Download")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (progress > 0 && progress < 100) {
            builder.setProgress(100, progress, false)
        }
        return builder.build()
    }
}
