package com.airplay.dlna.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Utility class for managing runtime permissions.
 */
object PermissionUtils {

    /**
     * Returns the list of permissions required for the app to function.
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+): Use READ_MEDIA_VIDEO
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            // Android 12 and below: Use READ_EXTERNAL_STORAGE
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    /**
     * Checks if all required permissions are granted.
     *
     * @param context Application context
     * @return true if all permissions are granted
     */
    fun hasAllPermissions(context: Context): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns the list of permissions that are not yet granted.
     *
     * @param context Application context
     * @return List of permission strings that need to be requested
     */
    fun getMissingPermissions(context: Context): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks if storage permission is granted.
     *
     * @param context Application context
     * @return true if storage/media permission is granted
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns a user-friendly description of why permissions are needed.
     *
     * @param permission The permission constant
     * @return User-friendly description
     */
    fun getPermissionRationale(permission: String): String {
        return when (permission) {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_VIDEO ->
                "Storage access is required to select video files for streaming."
            else -> "This permission is required for the app to function properly."
        }
    }
}
