package com.betterclouddrive.android.util

import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object FormatUtil {
    private val sizeFormat = DecimalFormat("#.##")
    private val dateFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val fullDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun formatFileSize(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1_048_576 -> "${sizeFormat.format(bytes / 1024.0)} KB"
        bytes < 1_073_741_824 -> "${sizeFormat.format(bytes / 1_048_576.0)} MB"
        else -> "${sizeFormat.format(bytes / 1_073_741_824.0)} GB"
    }

    fun formatDate(isoString: String?): String {
        if (isoString.isNullOrBlank()) return ""
        return try {
            val instant = Instant.parse(isoString)
            val localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            dateFormatter.format(localDateTime)
        } catch (e: Exception) {
            isoString.take(16).replace("T", " ")
        }
    }

    fun formatFullDate(isoString: String?): String {
        if (isoString.isNullOrBlank()) return ""
        return try {
            val instant = Instant.parse(isoString)
            val localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            fullDateFormatter.format(localDateTime)
        } catch (e: Exception) {
            isoString.take(19).replace("T", " ")
        }
    }
}
