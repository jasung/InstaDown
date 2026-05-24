package com.codex.indown

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaDownloader(
    private val context: Context,
    private val client: OkHttpClient = InDownHttp.client,
) {
    suspend fun downloadAll(
        items: List<MediaItem>,
        onProgress: suspend (Int, Int) -> Unit,
    ): List<DownloadResult> = withContext(Dispatchers.IO) {
        items.mapIndexed { index, item ->
            onProgress(index + 1, items.size)
            runCatching { download(index, item) }
                .getOrElse { error ->
                    DownloadResult(
                        item = item,
                        error = error.message ?: "다운로드 실패",
                    )
                }
        }
    }

    private fun download(
        index: Int,
        item: MediaItem,
    ): DownloadResult {
        val request = Request.Builder()
            .url(item.url)
            .header("User-Agent", InstagramUserAgent)
            .header("Accept", "image/*,video/*,*/*;q=0.8")
            .header("Referer", "https://www.instagram.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }

            val body = response.body ?: error("파일 응답이 비어있어.")
            val contentType = response.header("Content-Type").orEmpty().substringBefore(";")
            if (!isMediaResponse(contentType, item)) {
                error("이미지나 영상 응답이 아니야: ${contentType.ifBlank { "unknown" }}")
            }
            val extension = extensionFor(item, contentType)
            val mimeType = mimeTypeFor(item, contentType, extension)
            val isVideo = isVideo(item, mimeType, extension)
            val fileName = fileName(index, extension)

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(fileName, mimeType, isVideo, body.byteStream())
                    .let { uri -> DownloadResult(item = item, fileName = fileName, uri = uri.toString()) }
            } else {
                @Suppress("DEPRECATION")
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(
                        if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
                    ),
                    "InstaDown",
                )
                if (!directory.exists()) directory.mkdirs()
                val file = File(directory, fileName)
                FileOutputStream(file).use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(mimeType),
                    null,
                )
                DownloadResult(item = item, fileName = fileName, uri = Uri.fromFile(file).toString())
            }
        }
    }

    private fun saveToMediaStore(
        fileName: String,
        mimeType: String,
        isVideo: Boolean,
        input: java.io.InputStream,
    ): Uri {
        val resolver = context.contentResolver
        val collectionUri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val relativeDirectory = if (isVideo) {
            Environment.DIRECTORY_MOVIES
        } else {
            Environment.DIRECTORY_PICTURES
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDirectory/InstaDown")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collectionUri, values)
            ?: error("저장 위치를 만들지 못했어.")

        runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                input.use { source -> source.copyTo(output) }
            } ?: error("저장 스트림을 열지 못했어.")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.onFailure {
            resolver.delete(uri, null, null)
            throw it
        }

        return uri
    }

    private fun fileName(
        index: Int,
        extension: String,
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "indown_${stamp}_${index + 1}.$extension"
    }

    private fun extensionFor(
        item: MediaItem,
        contentType: String,
    ): String {
        val lowerUrl = item.url.substringBefore("?").lowercase(Locale.US)
        return when {
            lowerUrl.endsWith(".mp4") -> "mp4"
            lowerUrl.endsWith(".mov") -> "mov"
            lowerUrl.endsWith(".webp") -> "webp"
            lowerUrl.endsWith(".png") -> "png"
            lowerUrl.endsWith(".jpeg") -> "jpg"
            lowerUrl.endsWith(".jpg") -> "jpg"
            contentType == "video/mp4" -> "mp4"
            contentType.startsWith("video/") -> "mp4"
            contentType == "image/jpeg" -> "jpg"
            contentType == "image/webp" -> "webp"
            contentType == "image/png" -> "png"
            item.kind == MediaKind.Video -> "mp4"
            else -> "jpg"
        }
    }

    private fun mimeTypeFor(
        item: MediaItem,
        contentType: String,
        extension: String,
    ): String = when {
        contentType.startsWith("image/") || contentType.startsWith("video/") -> contentType
        extension == "mov" -> "video/quicktime"
        extension == "mp4" || item.kind == MediaKind.Video -> "video/mp4"
        extension == "webp" -> "image/webp"
        extension == "png" -> "image/png"
        else -> "image/jpeg"
    }

    private fun isVideo(
        item: MediaItem,
        mimeType: String,
        extension: String,
    ): Boolean =
        item.kind == MediaKind.Video ||
            mimeType.startsWith("video/") ||
            extension == "mp4" ||
            extension == "mov"

    private fun isMediaResponse(
        contentType: String,
        item: MediaItem,
    ): Boolean {
        if (contentType.startsWith("image/") || contentType.startsWith("video/")) return true
        if (contentType.isBlank() || contentType == "application/octet-stream") {
            return item.kind == MediaKind.Image || item.kind == MediaKind.Video
        }
        return false
    }
}
