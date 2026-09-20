package com.itsluminous.cleartravel.core.notifications

import android.content.Context
import android.content.Intent

/**
 * The notification → app deep-link INTENT CONTRACT (integration seam).
 *
 * `core:notifications` cannot reference the app's `MainActivity` class (module
 * boundaries), so every notification's content intent is the package LAUNCH intent
 * decorated with these extras. The app shell resolves them in
 * `MainActivity.onCreate`/`onNewIntent` (nav wiring is deferred to integration):
 *
 * - [EXTRA_TARGET]: which surface to open — [TARGET_FLIGHT] / [TARGET_TRAIN].
 * - [EXTRA_ENTITY_ID]: the UUID of the flight journey / train ticket whose detail
 *   sheet should open.
 *
 * The activity is launched `singleTask` (see the app manifest), so warm launches
 * arrive via `onNewIntent` instead of stacking duplicates.
 */
object DeepLinkContract {
    const val EXTRA_TARGET = "com.itsluminous.cleartravel.deeplink.TARGET"
    const val EXTRA_ENTITY_ID = "com.itsluminous.cleartravel.deeplink.ENTITY_ID"

    const val TARGET_FLIGHT = "flight"
    const val TARGET_TRAIN = "train"

    /**
     * Launch intent for [target]/[entityId], or null when the package has no launch
     * intent (never the case in the installed app; keeps tests honest).
     */
    fun launchIntent(
        context: Context,
        target: String,
        entityId: String,
    ): Intent? =
        context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_TARGET, target)
                putExtra(EXTRA_ENTITY_ID, entityId)
            }
}
