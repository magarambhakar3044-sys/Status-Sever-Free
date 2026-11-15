package com.statussaver.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.statussaver.app.model.StatusMedia
import com.statussaver.app.model.StatusMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MediaSaver(private val context: Context) {

    /**
     * Copies a status file to public Pictures/StatusSaver using MediaStore on Android 10+
     * (zero storage permissions needed) or File + MediaScanner on Android 9 and below.
     */
    suspend fun saveToPublicPictures(media: StatusMedia): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(media)
            } else {
                saveLegacyPublicPictures(media)
            }
        }
    }

    private fun saveWithMediaStore(media: StatusMedia): String {
        val resolver = context.contentResolver
        val collection = when (media.type) {
            StatusMediaType.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            StatusMediaType.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val relativePath = "${Environment.DIRECTORY_PICTURES}/$STATUS_SAVER_SUBFOLDER"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, media.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val destinationUri = resolver.insert(collection, values)
            ?: error("Could not create MediaStore entry for ${media.displayName}")

        try {
            resolver.openInputStream(media.uri)?.use { input ->
                resolver.openOutputStream(destinationUri)?.use { output ->
                    input.copyTo(output)
                } ?: error("Could not open output stream")
            } ?: error("Could not read source status file")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(destinationUri, values, null, null)
            return "$relativePath/${media.displayName}"
        } catch (t: Throwable) {
            resolver.delete(destinationUri, null, null)
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacyPublicPictures(media: StatusMedia): String {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val targetDir = File(picturesDir, STATUS_SAVER_SUBFOLDER).apply {
            if (!exists()) mkdirs()
        }
        val targetFile = File(targetDir, media.displayName)

        context.contentResolver.openInputStream(media.uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: error("Could not read source status file")

        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            arrayOf(media.mimeType),
            null
        )
        return targetFile.absolutePath
    }

    /**
     * Copies the SAF status stream into an internal FileProvider cache folder and
     * launches the Android system share sheet with temporary read permission.
     */
    suspend fun shareStatus(media: StatusMedia): Result<Intent> = withContext(Dispatchers.IO) {
        runCatching {
            val shareDir = File(context.cacheDir, "shared_statuses").apply {
                if (!exists()) mkdirs()
            }
            val cachedFile = File(shareDir, media.displayName)
            context.contentResolver.openInputStream(media.uri)?.use { input ->
                FileOutputStream(cachedFile).use { output ->
                    input.copyTo(output)
                }
            } ?: error("Could not open status file for sharing")

            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cachedFile
            )

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = media.mimeType
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(sendIntent, "Share status")
        }
    }

    companion object {
        const val STATUS_SAVER_SUBFOLDER = "StatusSaver"
    }
}
