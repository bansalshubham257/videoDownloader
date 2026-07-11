package com.quicksavevideos.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.quicksavevideos.app.QuickSaveApp
import com.quicksavevideos.app.R
import com.quicksavevideos.app.ui.ClipboardReaderActivity
import com.quicksavevideos.app.ui.MainActivity

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var dismissOverlay: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var isMoving = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isInDismissZone = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_NOTIFICATION_ID, createNotification())
        if (overlayView == null) {
            createOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        removeDismissOverlay()
        super.onDestroy()
    }

    private fun createOverlay() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_bubble, null)

        val density = resources.displayMetrics.density
        val bubbleWidth = (56 * density).toInt()

        statusTextView = overlayView?.findViewById(R.id.tvStatus)

        params = WindowManager.LayoutParams(
            bubbleWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (200 * density).toInt()
        }

        windowManager.addView(overlayView, params)

        overlayView?.setOnTouchListener { _, event ->
            handleTouch(event)
        }

        currentStatus?.let { updateStatusText(it) }
    }

    private fun updateStatusText(text: String?) {
        statusTextView?.let { tv ->
            tv.post {
                if (text != null) {
                    tv.text = text
                    tv.visibility = View.VISIBLE
                } else {
                    tv.visibility = View.GONE
                }
            }
        }
    }

    private fun createDismissOverlay() {
        if (dismissOverlay != null) return

        val density = resources.displayMetrics.density
        val dismissHeight = (80 * density).toInt()
        val dismissWidth = (80 * density).toInt()

        dismissOverlay = FrameLayout(this).apply {
            setBackgroundDrawable(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setSize(dismissWidth, dismissHeight)
                    setColor(0x80E74C3C.toInt())
                }
            )

            val icon = ImageView(this@OverlayService).apply {
                setImageDrawable(ContextCompat.getDrawable(this@OverlayService, R.drawable.ic_close))
                layoutParams = FrameLayout.LayoutParams(
                    (32 * density).toInt(),
                    (32 * density).toInt()
                ).apply {
                    gravity = Gravity.CENTER
                }
            }
            addView(icon)

            val label = TextView(this@OverlayService).apply {
                text = "Drop to dismiss"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
            }
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = (40 * density).toInt()
            })
        }

        dismissParams = WindowManager.LayoutParams(
            dismissWidth,
            dismissHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            y = -(48 * density).toInt()
        }

        windowManager.addView(dismissOverlay, dismissParams)
    }

    private fun removeDismissOverlay() {
        dismissOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing dismiss overlay", e)
            }
            dismissOverlay = null
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val p = params ?: return false
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val dismissZoneThreshold = (screenHeight * 0.80).toInt()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isMoving = false
                isInDismissZone = false
                initialX = p.x
                initialY = p.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                    isMoving = true
                    val newY = initialY + dy
                    val bubbleCenterY = newY + (56 * density).toInt() / 2

                    p.x = initialX + dx
                    p.y = newY
                    windowManager.updateViewLayout(overlayView, p)

                    if (bubbleCenterY > dismissZoneThreshold) {
                        if (!isInDismissZone) {
                            isInDismissZone = true
                            overlayView?.alpha = 0.5f
                            createDismissOverlay()
                        }
                    } else {
                        if (isInDismissZone) {
                            isInDismissZone = false
                            overlayView?.alpha = 1.0f
                            removeDismissOverlay()
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isMoving) {
                    onOverlayTapped()
                } else if (isInDismissZone) {
                    dismissOverlay()
                }
                overlayView?.alpha = 1.0f
                removeDismissOverlay()
                return true
            }
        }
        return false
    }

    private fun onOverlayTapped() {
        val prefs = getSharedPreferences("quicksave_prefs", Context.MODE_PRIVATE)
        val clipboardMode = prefs.getString("clipboard_mode", "always") ?: "always"

        if (clipboardMode == "ask") {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("action", "clipboard_confirm")
                putExtra("server_url", getServerUrl())
            }
            startActivity(intent)
        } else {
            Log.d(TAG, "Overlay tapped — reading clipboard")
            updateStatus("Downloading...")
            val intent = Intent(this, ClipboardReaderActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("server_url", getServerUrl())
                putExtra("auto_download", true)
            }
            startActivity(intent)
        }
    }

    private fun dismissOverlay() {
        Log.d(TAG, "Dismissing overlay")
        stopSelf()
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay", e)
            }
            overlayView = null
        }
        statusTextView = null
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, QuickSaveApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_active))
            .setContentText(getString(R.string.overlay_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getServerUrl(): String {
        val prefs = getSharedPreferences("quicksave_prefs", Context.MODE_PRIVATE)
        return prefs.getString("server_url", "https://quicksavevideos.com") ?: "https://quicksavevideos.com"
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val FOREGROUND_NOTIFICATION_ID = 1001

        @Volatile
        var currentStatus: String? = null
        private var statusTextView: TextView? = null

        fun updateStatus(status: String?) {
            currentStatus = status
            statusTextView?.let { tv ->
                tv.post {
                    if (status != null) {
                        tv.text = status
                        tv.visibility = View.VISIBLE
                    } else {
                        tv.visibility = View.GONE
                    }
                }
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return false
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (service.service.className == OverlayService::class.java.name) {
                    return true
                }
            }
            return false
        }
    }
}
