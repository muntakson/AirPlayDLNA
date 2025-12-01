package com.airplay.dlna.model

import android.net.Uri

/**
 * Data class representing a selected video file.
 *
 * @property uri The content URI of the video file
 * @property name Display name of the video file
 * @property mimeType MIME type of the video (e.g., video/mp4)
 * @property size File size in bytes
 * @property duration Video duration in milliseconds (if available)
 */
data class VideoFile(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val duration: Long = 0
) {
    /**
     * Returns formatted file size string
     */
    fun getFormattedSize(): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Returns formatted duration string (HH:MM:SS)
     */
    fun getFormattedDuration(): String {
        if (duration <= 0) return "Unknown"

        val seconds = (duration / 1000) % 60
        val minutes = (duration / (1000 * 60)) % 60
        val hours = duration / (1000 * 60 * 60)

        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
