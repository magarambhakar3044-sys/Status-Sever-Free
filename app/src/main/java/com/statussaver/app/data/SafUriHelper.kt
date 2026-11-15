package com.statussaver.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.statussaver.app.model.StatusSource

class SafUriHelper(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Builds the initial Document URI passed to EXTRA_INITIAL_URI so the Android 11+
     * SAF folder picker opens directly inside the target .Statuses directory.
     */
    fun buildInitialStatusesUri(source: StatusSource): Uri {
        val documentId = "$EXTERNAL_STORAGE_PRIMARY${source.safRelativePath}"
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
    }

    /**
     * Persists the granted SAF tree URI across device reboots using takePersistableUriPermission.
     */
    fun persistTreeUri(source: StatusSource, treeUri: Uri): Boolean {
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        return try {
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            val docFile = DocumentFile.fromTreeUri(context, treeUri)
            if (docFile != null && docFile.canRead()) {
                prefs.edit {
                    putString(prefKey(source), treeUri.toString())
                }
                true
            } else {
                false
            }
        } catch (_: SecurityException) {
            false
        }
    }

    fun getPersistedTreeUri(source: StatusSource): Uri? {
        val raw = prefs.getString(prefKey(source), null) ?: return null
        val uri = Uri.parse(raw)
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        return if (hasPermission) uri else {
            prefs.edit { remove(prefKey(source)) }
            null
        }
    }

    fun revokeTreeUri(source: StatusSource) {
        val raw = prefs.getString(prefKey(source), null)
        if (raw != null) {
            val uri = Uri.parse(raw)
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Ignore if already revoked by system settings
            }
            prefs.edit { remove(prefKey(source)) }
        }
    }

    fun isWhatsAppInstalled(source: StatusSource): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    source.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(source.packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun requiresSaf(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasAnyGrantedSource(): Boolean {
        return StatusSource.entries.any { getPersistedTreeUri(it) != null }
    }

    private fun prefKey(source: StatusSource): String = "tree_uri_${source.name}"

    companion object {
        private const val PREFS_NAME = "status_saver_saf_prefs"
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val EXTERNAL_STORAGE_PRIMARY = "primary:"
    }
}
