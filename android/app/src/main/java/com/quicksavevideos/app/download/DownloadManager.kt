package com.quicksavevideos.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class PreviewInfo(
    val url: String,
    val title: String,
    val description: String,
    val thumbnail: String,
    val isVideo: Boolean
)

class DownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class DownloadResult(
        val success: Boolean,
        val message: String,
        val fileName: String? = null,
        val uri: Uri? = null
    )

    suspend fun downloadVideo(
        serverUrl: String,
        videoUrl: String,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val baseUrl = serverUrl.trimEnd('/')

            Log.d(TAG, "Step 1: Calling /api/download with URL: $videoUrl")
            onProgress(10)

            val jsonBody = JSONObject().apply {
                put("url", videoUrl)
                put("content_type", "best")
                put("quality", "best")
            }.toString()

            val downloadResponse = client.newCall(
                Request.Builder()
                    .url("$baseUrl/api/download")
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            val responseBody = downloadResponse.body?.string() ?: ""
            Log.d(TAG, "Download API response: $responseBody")

            if (!downloadResponse.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).optString("error", "Download failed")
                } catch (e: Exception) {
                    "Download failed (HTTP ${downloadResponse.code})"
                }
                return@withContext DownloadResult(false, errorMsg)
            }

            val json = JSONObject(responseBody)
            if (!json.optBoolean("success", false)) {
                val errorMsg = json.optString("error", "Download failed")
                return@withContext DownloadResult(false, errorMsg)
            }

            val filename = json.optString("filename", "")
            if (filename.isBlank()) {
                return@withContext DownloadResult(false, "No filename in response")
            }

            Log.d(TAG, "Step 2: Downloading file: $filename")
            onProgress(40)

            val encodedFilename = URLEncoder.encode(filename, "UTF-8")
            val fileResponse = client.newCall(
                Request.Builder()
                    .url("$baseUrl/api/file/$encodedFilename")
                    .get()
                    .build()
            ).execute()

            if (!fileResponse.isSuccessful) {
                return@withContext DownloadResult(false, "Failed to download file (HTTP ${fileResponse.code})")
            }

            val fileBytes = fileResponse.body?.bytes() ?: ByteArray(0)
            if (fileBytes.isEmpty()) {
                return@withContext DownloadResult(false, "Empty file received")
            }

            Log.d(TAG, "Step 3: Saving ${fileBytes.size} bytes to gallery")
            onProgress(70)

            val result = saveToGallery(filename, fileBytes)
            onProgress(100)

            result
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            DownloadResult(false, "Error: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    private fun saveToGallery(fileName: String, bytes: ByteArray): DownloadResult {
        val extension = fileName.substringAfterLast('.', "mp4").lowercase()
        val isVideo = extension in listOf("mp4", "mkv", "webm", "mov", "avi", "3gp")
        val mimeType = if (isVideo) "video/mp4" else if (extension == "jpg" || extension == "jpeg") "image/jpeg" else "image/$extension"

        val displayName = "QuickSave_${System.currentTimeMillis()}_${fileName}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val collectionUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val itemUri = context.contentResolver.insert(collectionUri, contentValues)
                ?: return DownloadResult(false, "Failed to create media entry")

            context.contentResolver.openOutputStream(itemUri)?.use { outputStream ->
                outputStream.write(bytes)
            } ?: return DownloadResult(false, "Failed to write file")

            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(itemUri, contentValues, null, null)

            return DownloadResult(true, "Saved to gallery", displayName, itemUri)
        } else {
            val dir = if (isVideo)
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            else
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

            dir.mkdirs()
            val file = File(dir, displayName)
            FileOutputStream(file).use { it.write(bytes) }

            val uri = Uri.fromFile(file)
            return DownloadResult(true, "Saved to gallery", displayName, uri)
        }
    }

    companion object {
        private const val TAG = "DownloadManager"
    }

    suspend fun fetchPreview(serverUrl: String, videoUrl: String): PreviewInfo = withContext(Dispatchers.IO) {
        try {
            val baseUrl = serverUrl.trimEnd('/')
            val jsonBody = JSONObject().apply {
                put("url", videoUrl)
            }.toString()

            val response = client.newCall(
                Request.Builder()
                    .url("$baseUrl/api/preview")
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()

            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                return@withContext PreviewInfo(
                    url = videoUrl,
                    title = "Preview unavailable",
                    description = "",
                    thumbnail = "",
                    isVideo = false
                )
            }

            val json = JSONObject(responseBody)
            if (!json.optBoolean("success", false)) {
                return@withContext PreviewInfo(
                    url = videoUrl,
                    title = "Preview unavailable",
                    description = "",
                    thumbnail = "",
                    isVideo = false
                )
            }

            val preview = json.optJSONObject("preview")
            if (preview == null) {
                return@withContext PreviewInfo(
                    url = videoUrl,
                    title = "Preview unavailable",
                    description = "",
                    thumbnail = "",
                    isVideo = false
                )
            }

            PreviewInfo(
                url = preview.optString("url", videoUrl),
                title = preview.optString("title", "Video"),
                description = preview.optString("description", ""),
                thumbnail = preview.optString("thumbnail", ""),
                isVideo = preview.optBoolean("is_video", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Preview fetch error", e)
            PreviewInfo(
                url = videoUrl,
                title = "Preview unavailable",
                description = "",
                thumbnail = "",
                isVideo = false
            )
        }
    }
}
