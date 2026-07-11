package com.quicksavevideos.app.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.quicksavevideos.app.R

class InfoPageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info_page)

        val page = intent.getStringExtra("page") ?: "terms"

        val titleView = findViewById<TextView>(R.id.tvPageTitle)
        val webView = findViewById<WebView>(R.id.webView)
        val closeBtn = findViewById<ImageButton>(R.id.btnClose)

        titleView.text = if (page == "terms") "Terms of Service" else "Privacy Policy"

        closeBtn.setOnClickListener { finish() }

        webView.settings.apply {
            javaScriptEnabled = false
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        val isDark = ContextCompat.getColor(this, R.color.bg_primary) == -1610612436 ||
                resources.configuration.uiMode and 48 == 32

        val html = if (page == "terms") TERMS_HTML else PRIVACY_HTML
        val styled = html.replace(
            "</head>",
            """<style>
body { background: ${if (isDark) "#0F172A" else "#F8FAFC"}; color: ${if (isDark) "#F1F5F9" else "#0F172A"}; }
.card { background: ${if (isDark) "#1E293B" else "#FFFFFF"}; border-color: ${if (isDark) "#334155" else "#E2E8F0"}; }
.muted { color: ${if (isDark) "#94A3B8" else "#475569"}; }
a { color: #818CF8; }
</style></head>""",
            false
        )

        webView.loadDataWithBaseURL(null, styled, "text/html", "UTF-8", null)
    }

    companion object {
        private const val TERMS_HTML = """
<!DOCTYPE html>
<html>
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, sans-serif; line-height: 1.7; padding: 16px; }
.wrap { max-width: 860px; margin: 0 auto; }
.card { border-radius: 12px; padding: 20px; }
h1 { font-size: 24px; margin-bottom: 8px; }
h2 { font-size: 17px; margin-top: 24px; margin-bottom: 8px; }
p { font-size: 14px; margin-bottom: 12px; }
.muted { font-size: 13px; }
a { text-decoration: none; }
</style></head>
<body><div class="wrap"><div class="card">
<h1>Terms of Service</h1>
<p class="muted">Last updated: July 5, 2026</p>
<h2>Acceptance of Terms</h2>
<p>By using QuickSaveVideos, you agree to these terms and applicable laws.</p>
<h2>Permitted Use</h2>
<p>You may use this service only for lawful purposes and for content you own or are authorized to download.</p>
<h2>Prohibited Use</h2>
<p>You must not use the platform to violate copyrights, terms of third-party services, or local regulations.</p>
<h2>Service Availability</h2>
<p>We may modify, limit, or discontinue parts of the service at any time to maintain reliability and security.</p>
<h2>Disclaimer</h2>
<p>The service is provided "as is" without warranties of any kind. Users are responsible for how they use downloaded content.</p>
<h2>Contact</h2>
<p>For terms-related questions, contact <a href="mailto:support@quicksavevideos.com">support@quicksavevideos.com</a>.</p>
</div></div></body></html>
"""

        private const val PRIVACY_HTML = """
<!DOCTYPE html>
<html>
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, sans-serif; line-height: 1.7; padding: 16px; }
.wrap { max-width: 860px; margin: 0 auto; }
.card { border-radius: 12px; padding: 20px; }
h1 { font-size: 24px; margin-bottom: 8px; }
h2 { font-size: 17px; margin-top: 24px; margin-bottom: 8px; }
p { font-size: 14px; margin-bottom: 12px; }
.muted { font-size: 13px; }
ul { padding-left: 20px; margin-bottom: 12px; }
li { font-size: 14px; margin-bottom: 4px; }
a { text-decoration: none; }
</style></head>
<body><div class="wrap"><div class="card">
<h1>Privacy Policy</h1>
<p class="muted">Last updated: July 5, 2026</p>
<p>QuickSaveVideos provides a video downloading tool for supported platforms. This policy explains how our Android app handles data, permissions, and third-party services.</p>
<h2>1. Data We Collect</h2>
<p>We collect <strong>no personal data</strong> on our servers. Video URLs you paste are sent to our server solely to process the download and are not stored permanently. The app does not require registration or account creation.</p>
<h2>2. Permissions Used</h2>
<ul>
<li><strong>Internet</strong> — Required to send download requests and fetch video files.</li>
<li><strong>System Alert Window (Overlay)</strong> — Shows the floating bubble for one-tap downloads.</li>
<li><strong>Notifications</strong> — Shows download progress and completion.</li>
<li><strong>Clipboard</strong> — Read on overlay tap to detect copied video URLs. Not stored.</li>
<li><strong>Storage</strong> — Used on Android 9 and below to save videos. Android 10+ uses MediaStore.</li>
</ul>
<h2>3. Third-Party Services</h2>
<p>This app uses Google AdMob for ads. AdMob may collect Advertising ID, device type, OS version, and ad interactions. See <a href="https://policies.google.com/privacy">Google's Privacy Policy</a>.</p>
<p>Download requests are processed by our backend using yt-dlp. No personal data is retained on the server.</p>
<h2>4. Data Storage &amp; Retention</h2>
<p>Downloaded videos are saved to your device gallery. Temporary server files are deleted within 1 hour. Your server URL setting is stored locally on your device.</p>
<h2>5. Children's Privacy</h2>
<p>This app is not directed at children under 13. We do not knowingly collect information from children.</p>
<h2>6. Your Rights</h2>
<p>Under GDPR, CCPA, and similar laws, you may request information about or deletion of your data. Since we collect no personal data, these rights are inherently satisfied.</p>
<h2>7. Contact</h2>
<p>For privacy inquiries: <a href="mailto:support@quicksavevideos.com">support@quicksavevideos.com</a></p>
</div></div></body></html>
"""
    }
}
