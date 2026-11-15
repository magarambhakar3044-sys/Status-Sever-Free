package com.statussaver.app.model

import android.net.Uri

enum class StatusSource(
    val displayName: String,
    val packageName: String,
    val safRelativePath: String,
    val legacyRelativePath: String
) {
    WHATSAPP(
        displayName = "WhatsApp",
        packageName = "com.whatsapp",
        safRelativePath = "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
        legacyRelativePath = "WhatsApp/Media/.Statuses"
    ),
    WHATSAPP_BUSINESS(
        displayName = "WhatsApp Business",
        packageName = "com.whatsapp.w4b",
        safRelativePath = "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses",
        legacyRelativePath = "WhatsApp Business/Media/.Statuses"
    )
}

enum class StatusMediaType {
    IMAGE,
    VIDEO
}

data class StatusMedia(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val type: StatusMediaType,
    val source: StatusSource,
    val sizeBytes: Long,
    val lastModified: Long,
    val isSaved: Boolean = false
)

enum class SourceFilter(val label: String) {
    ALL("All"),
    WHATSAPP("WhatsApp"),
    WHATSAPP_BUSINESS("WA Business")
}
