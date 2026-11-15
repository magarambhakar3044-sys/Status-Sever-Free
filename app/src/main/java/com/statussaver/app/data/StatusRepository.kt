package com.statussaver.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.statussaver.app.model.StatusMedia
import com.statussaver.app.model.StatusMediaType
import com.statussaver.app.model.StatusSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class StatusRepository(
    private val context: Context,
    private val safUriHelper: SafUriHelper
) {

    suspend fun loadRecentStatuses(): List<StatusMedia> = withContext(Dispatchers.IO) {
        val savedNames = queryAlreadySavedFilenames()
        val results = mutableListOf<StatusMedia>()

        if (safUriHelper.requiresSaf()) {
            // Android 11+ (API 30+): Scan granted SAF DocumentFile trees
            for (source in StatusSource.entries) {
                val treeUri = safUriHelper.getPersistedTreeUri(source) ?: continue
                results += scanSafDirectory(treeUri, source, savedNames)
            }
        } else {
            // Android 10 and below fallback: Direct File API in external storage
            for (source in StatusSource.entries) {
                val treeUri = safUriHelper.getPersistedTreeUri(source)
                if (treeUri != null) {
                    results += scanSafDirectory(treeUri, source, savedNames)
                } else {
                    results += scanLegacyDirectory(source, savedNames)
                }
            }
        }

        results.sortedByDescending { it.lastModified }
    }

    private fun scanSafDirectory(
        treeUri: Uri,
        source: StatusSource,
        savedNames: Set<String>
    ): List<StatusMedia> {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        if (!rootDoc.exists() || !rootDoc.canRead()) return emptyList()

        return rootDoc.listFiles()
            .asSequence()
            .filter { file ->
                val name = file.name ?: return@filter false
                file.isFile && name != ".nomedia" && !name.startsWith(".")
            }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                val mediaType = classifyMediaType(name, file.type) ?: return@mapNotNull null
                val mimeType = file.type ?: inferMimeType(name, mediaType)
                StatusMedia(
                    id = "${source.name}_$name",
                    uri = file.uri,
                    displayName = name,
                    mimeType = mimeType,
                    type = mediaType,
                    source = source,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    isSaved = savedNames.contains(name)
                )
            }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun scanLegacyDirectory(
        source: StatusSource,
        savedNames: Set<String>
    ): List<StatusMedia> {
        val externalRoot = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            File(externalRoot, source.legacyRelativePath),
            File(externalRoot, source.safRelativePath)
        )
        val statusDir = candidates.firstOrNull { it.exists() && it.isDirectory && it.canRead() }
            ?: return emptyList()

        val files = statusDir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.name != ".nomedia" && !it.name.startsWith(".") }
            .mapNotNull { file ->
                val mediaType = classifyMediaType(file.name, null) ?: return@mapNotNull null
                StatusMedia(
                    id = "${source.name}_${file.name}",
                    uri = Uri.fromFile(file),
                    displayName = file.name,
                    mimeType = inferMimeType(file.name, mediaType),
                    type = mediaType,
                    source = source,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    isSaved = savedNames.contains(file.name)
                )
            }
            .toList()
    }

    private fun queryAlreadySavedFilenames(): Set<String> {
        val saved = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%${MediaSaver.STATUS_SAVER_SUBFOLDER}%")

            listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).forEach { collection ->
                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        cursor.getString(nameIdx)?.let { saved.add(it) }
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                MediaSaver.STATUS_SAVER_SUBFOLDER
            )
            dir.listFiles()?.forEach { saved.add(it.name) }
        }
        return saved
    }

    private fun classifyMediaType(fileName: String, mimeType: String?): StatusMediaType? {
        if (mimeType != null) {
            if (mimeType.startsWith("image/")) return StatusMediaType.IMAGE
            if (mimeType.startsWith("video/")) return StatusMediaType.VIDEO
        }
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp") -> StatusMediaType.IMAGE
            lower.endsWith(".mp4") || lower.endsWith(".3gp") ||
                lower.endsWith(".mkv") || lower.endsWith(".webm") -> StatusMediaType.VIDEO
            else -> null
        }
    }

    private fun inferMimeType(fileName: String, type: StatusMediaType): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            type == StatusMediaType.IMAGE -> "image/jpeg"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mkv") -> "video/x-matroska"
            else -> "video/mp4"
        }
    }
}
