package com.itsluminous.cleartravel.feature.itinerary.place

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Seam over "is there a network right now" so the backfill is unit-testable and skips cleanly offline. */
fun interface NetworkAvailability {
    fun isOnline(): Boolean
}

/** [ConnectivityManager]-backed: an active network with INTERNET capability. */
class AndroidNetworkAvailability
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : NetworkAvailability {
        override fun isOnline(): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
