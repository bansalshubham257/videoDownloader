package com.quicksavevideos.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.quicksavevideos.app.QuickSaveApp
import com.quicksavevideos.app.R
import com.quicksavevideos.app.download.DownloadManager
import com.quicksavevideos.app.download.PreviewInfo
import com.quicksavevideos.app.ads.AdsManager
import com.quicksavevideos.app.service.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var etServerUrl: TextInputEditText
    private lateinit var etUrl: TextInputEditText
    private lateinit var urlInputLayout: TextInputLayout
    private lateinit var btnDownloadNow: Button
    private lateinit var tvDownloadStatus: TextView
    private lateinit var progressBarDownload: ProgressBar
    private lateinit var adContainer: FrameLayout
    private lateinit var downloadManager: DownloadManager
    private lateinit var switchDarkMode: SwitchCompat
    private lateinit var switchClipboardAsk: SwitchCompat
    
    // Preview views
    private lateinit var previewSection: LinearLayout
    private lateinit var previewThumbnail: AppCompatImageView
    private lateinit var previewTitle: TextView
    private lateinit var tvPreviewType: TextView
    private lateinit var previewDescription: TextView
    private lateinit var previewCopyBtn: MaterialButton
    private lateinit var btnDownloadFromPreview: Button
    private lateinit var captionSection: LinearLayout
    private lateinit var btnClearUrl: MaterialButton
    
    // State
    private var currentPreviewUrl: String? = null
    private var previewFetchJob: kotlinx.coroutines.Job? = null
    private var clipboardIntentHandled = false
    private var permissionQueue = mutableListOf<() -> Unit>()

    companion object {
        private const val PREFS_NAME = "quicksave_prefs"
    }

    private val requestOverlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkAndRequestNextPermission() }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { checkAndRequestNextPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("dark_mode", false)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        downloadManager = DownloadManager(this)

        btnToggle = findViewById(R.id.btnToggleOverlay)
        tvStatus = findViewById(R.id.tvStatus)
        etUrl = findViewById(R.id.etUrl)
        urlInputLayout = findViewById(R.id.urlInputLayout)
        btnDownloadNow = findViewById(R.id.btnDownloadNow)
        tvDownloadStatus = findViewById(R.id.tvDownloadStatus)
        progressBarDownload = findViewById(R.id.progressBarDownload)
        adContainer = findViewById(R.id.adContainer)
        switchDarkMode = findViewById(R.id.switchDarkMode)
        switchClipboardAsk = findViewById(R.id.switchClipboardAsk)
        
        // Preview views
        previewSection = findViewById(R.id.previewSection)
        previewThumbnail = findViewById(R.id.ivThumbnail)
        previewTitle = findViewById(R.id.tvPreviewTitle)
        tvPreviewType = findViewById(R.id.tvPreviewType)
        previewDescription = findViewById(R.id.tvCaption)
        previewCopyBtn = findViewById(R.id.btnCopyCaption)
        btnDownloadFromPreview = findViewById(R.id.btnDownloadFromPreview)
        captionSection = findViewById(R.id.captionSection)

        loadBannerAd()
        updateUI()

        requestAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        if (!clipboardIntentHandled) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        clipboardIntentHandled = false
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.getStringExtra("action")
        if (action == "clipboard_confirm") {
            clipboardIntentHandled = true
            showClipboardConfirmDialog()
        }
    }

    private fun showClipboardConfirmDialog() {
        val serverUrl = intent?.getStringExtra("server_url") ?: "https://quicksavevideos.com"
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_clipboard, null)
        dialog.setContentView(view)
        dialog.setCancelable(false)

        view.findViewById<View>(R.id.btnAllow).setOnClickListener {
            dialog.dismiss()
            val readerIntent = Intent(this, ClipboardReaderActivity::class.java).apply {
                putExtra("server_url", serverUrl)
                putExtra("auto_download", true)
            }
            startActivity(readerIntent)
        }
        view.findViewById<View>(R.id.btnDeny).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun loadBannerAd() {
        adContainer.post {
            AdsManager.loadBannerAd(adContainer, this)
        }
    }

    // ── Permission flow ────────────────────────────────────────────────

    private fun requestAllPermissions() {
        permissionQueue.clear()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            permissionQueue.add {
                AlertDialog.Builder(this)
                    .setTitle("Overlay Permission Required")
                    .setMessage("QuickSave needs this permission to show a floating bubble for one-tap downloads.\n\n" +
                            "What it does:\n" +
                            "\u2022 Shows a small bubble on top of other apps (Instagram, YouTube, etc.)\n" +
                            "\u2022 When tapped, it reads your clipboard to detect a copied video link\n" +
                            "\u2022 The video downloads automatically \u2014 no need to switch apps\n" +
                            "\u2022 Clipboard data is never stored or shared\n\n" +
                            "Tap Allow and toggle it ON in Settings.")
                    .setPositiveButton("Allow") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        requestOverlayPermission.launch(intent)
                    }
                    .setNegativeButton("Skip") { _, _ -> checkAndRequestNextPermission() }
                    .setCancelable(false)
                    .show()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionQueue.add {
                    AlertDialog.Builder(this)
                        .setTitle("Notification Permission")
                        .setMessage("QuickSave uses notifications to keep you updated on downloads.\n\n" +
                                "What it does:\n" +
                                "\u2022 Shows download progress (e.g., 'Downloading... 45%')\n" +
                                "\u2022 Alerts you when a video is saved to your gallery\n" +
                                "\u2022 Required by Android for the overlay service to run in background\n\n" +
                                "No spam or promotional notifications.")
                        .setPositiveButton("Allow") { _, _ ->
                            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Skip") { _, _ -> checkAndRequestNextPermission() }
                        .setCancelable(false)
                        .show()
                }
            }
        }

        checkAndRequestNextPermission()
    }

    private fun checkAndRequestNextPermission() {
        if (permissionQueue.isEmpty()) {
            return
        }
        permissionQueue.removeAt(0).invoke()
    }

    private fun areAllPermissionsGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return false
        }
        return true
    }

    // ── Preview Functions ────────────────────────────────────────────────

    private fun fetchPreviewDebounced(url: String) {
        previewFetchJob?.cancel()
        previewFetchJob = GlobalScope.launch(Dispatchers.IO) {
            delay(800)
            runOnUiThread { fetchPreview(url) }
        }
    }

    private fun fetchPreview(url: String) {
        if (url == currentPreviewUrl) return
        currentPreviewUrl = url
        
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val preview = downloadManager.fetchPreview("https://quicksavevideos.com", url)
                runOnUiThread { showPreview(preview) }
            } catch (e: Exception) {
                runOnUiThread { hidePreview() }
            }
        }
    }

    private fun showPreview(preview: PreviewInfo) {
        currentPreviewUrl = preview.url
        previewSection.visibility = View.VISIBLE
        
        // Load thumbnail
        if (preview.thumbnail.isNotBlank()) {
            Glide.with(this)
                .load(preview.thumbnail)
                .placeholder(R.color.border)
                .error(R.color.border)
                .into(previewThumbnail)
        }
        
        // Title
        previewTitle.text = preview.title
        
        // Type
        tvPreviewType.text = if (preview.isVideo) "📹 Video" else "📸 Photo"
        
        // Caption
        if (preview.description.isNotBlank()) {
            captionSection.visibility = View.VISIBLE
            previewDescription.text = preview.description
        } else {
            captionSection.visibility = View.GONE
        }
        
        // Download button in preview
        btnDownloadFromPreview.setOnClickListener { 
            etUrl.text?.clear()
            etUrl.append(preview.url)
            onDownloadNow() 
        }
    }

private fun hidePreview() {
        previewSection.visibility = View.GONE
        currentPreviewUrl = null
    }

    private fun clearUrl() {
        etUrl.text?.clear()
        hidePreview()
        etUrl.requestFocus()
    }

    private fun copyCaption() {
        val caption = previewDescription.text.toString()
        if (caption.isNotBlank()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Caption", caption)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Caption copied!", Toast.LENGTH_SHORT).show()
        }
    }

    // ── In-app download ─────────────────────────────────────────────────

    private fun onDownloadNow() {
        val url = etUrl.text?.toString()?.trim() ?: ""
        if (url.isBlank() || !url.startsWith("http")) {
            tvDownloadStatus.visibility = View.VISIBLE
            tvDownloadStatus.text = "Please enter a valid video URL"
            return
        }

        tvDownloadStatus.visibility = View.VISIBLE
        tvDownloadStatus.text = "Starting download..."
        progressBarDownload.visibility = View.VISIBLE
        progressBarDownload.progress = 0
        btnDownloadNow.isEnabled = false

        val notificationId = System.currentTimeMillis().toInt()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, buildProgressNotification("Starting download...", 0))

        GlobalScope.launch(Dispatchers.IO) {
            val result = downloadManager.downloadVideo(
                serverUrl = "https://quicksavevideos.com",
                videoUrl = url,
                onProgress = { progress ->
                    runOnUiThread {
                        progressBarDownload.progress = progress
                        tvDownloadStatus.text = if (progress < 100) "Downloading... $progress%" else "Saving to gallery..."
                    }
                    val notif = buildProgressNotification(
                        if (progress < 100) "Downloading... $progress%" else "Saving to gallery...",
                        progress
                    )
                    notificationManager.notify(notificationId, notif)
                }
            )

            runOnUiThread {
                btnDownloadNow.isEnabled = true
                if (result.success) {
                    tvDownloadStatus.text = "\u2713 Saved to gallery"
                    progressBarDownload.progress = 100
                } else {
                    tvDownloadStatus.text = "\u2717 ${result.message}"
                    progressBarDownload.visibility = View.GONE
                }
            }

            val finalNotif = if (result.success) {
                NotificationCompat.Builder(this@MainActivity, QuickSaveApp.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Download complete \u2713")
                    .setContentText("Video saved to gallery")
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            } else {
                NotificationCompat.Builder(this@MainActivity, QuickSaveApp.NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Download failed \u2717")
                    .setContentText(result.message)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            }
            notificationManager.notify(notificationId, finalNotif)
        }
    }

    // ── Overlay toggle ──────────────────────────────────────────────────

    private fun onToggleOverlay() {
        if (OverlayService.isRunning(this)) {
            stopOverlay()
        } else {
            if (areAllPermissionsGranted()) {
                showRewardedAdThenStart()
            } else {
                requestAllPermissions()
            }
        }
    }

    private fun showRewardedAdThenStart() {
        Toast.makeText(this, "Loading ad…", Toast.LENGTH_SHORT).show()
        AdsManager.showRewardedAd(
            activity = this,
            onEarned = {
                runOnUiThread {
                    OverlayService.start(this@MainActivity)
                    updateUI()
                    Toast.makeText(this@MainActivity, "Overlay activated", Toast.LENGTH_SHORT).show()
                }
            },
            onDismissed = {
                runOnUiThread {
                    if (!OverlayService.isRunning(this@MainActivity)) {
                        OverlayService.start(this@MainActivity)
                        updateUI()
                        Toast.makeText(this@MainActivity, "Overlay activated", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun stopOverlay() {
        OverlayService.stop(this)
        updateUI()
        Toast.makeText(this, "Overlay deactivated", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (OverlayService.isRunning(this)) {
            btnToggle.text = getString(R.string.btn_stop_overlay)
            btnToggle.setBackgroundColor(
                ContextCompat.getColor(this, R.color.error)
            )
            tvStatus.text = getString(R.string.overlay_active)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            btnToggle.text = getString(R.string.btn_start_overlay)
            btnToggle.setBackgroundColor(
                ContextCompat.getColor(this, R.color.primary)
            )
            tvStatus.text = getString(R.string.overlay_inactive)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun buildProgressNotification(text: String, progress: Int): android.app.Notification {
        val builder = NotificationCompat.Builder(this, QuickSaveApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("QuickSave Download")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(progress < 100)
            .setAutoCancel(progress >= 100)

        if (progress > 0 && progress < 100) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }
}