package com.itsluminous.cleartravel.core.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * PermissionGate-style check for POST_NOTIFICATIONS (runtime permission on 13+;
 * implicitly granted below). Every `notify(...)` call in this module runs through
 * [canPost] — posting without permission is silently dropped, never a crash.
 * The runtime REQUEST (system dialog) is the app shell's job; features only check.
 */
object NotificationPermissions {
    /** The runtime permission features must request on Android 13+. */
    const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    /** Whether posting a notification is currently allowed. */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Whether the app must ask for the runtime permission before posting. */
    fun needsRequest(context: Context): Boolean = !canPost(context)
}
