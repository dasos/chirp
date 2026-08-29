package com.chirp.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.chirp.ui.permissions.hasPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Formats the current date, time, and timezone.
     */
    fun getCurrentDateTimePrompt(): String {
        val now = ZonedDateTime.now()
        val formatted = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy HH:mm:ss (z)"))
        return "Current date and time: $formatted."
    }

    /**
     * Obtains the approximate location (locality, administrative area, country or coarse coords)
     * if permission is granted. Runs off the main thread.
     */
    @SuppressLint("MissingPermission")
    suspend fun getApproximateLocationPrompt(): String? = withContext(Dispatchers.IO) {
        if (!context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return@withContext null
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null

        val location: Location? = runCatching {
            val providers = locationManager.getProviders(true)
            var bestLocation: Location? = null
            for (provider in providers) {
                val l = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.time > bestLocation.time) {
                    bestLocation = l
                }
            }
            bestLocation
        }.getOrNull()

        if (location == null) return@withContext null

        // Round coordinates to ~2 decimal places for approximate locality (~1km resolution)
        val lat = String.format(Locale.US, "%.2f", location.latitude)
        val lon = String.format(Locale.US, "%.2f", location.longitude)

        val geocoded = runCatching {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.getDefault())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    var result: String? = null
                    geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                        val address = addresses.firstOrNull()
                        if (address != null) {
                            val parts = listOfNotNull(
                                address.locality ?: address.subAdminArea,
                                address.adminArea,
                                address.countryName,
                            ).filter { it.isNotBlank() }
                            if (parts.isNotEmpty()) {
                                result = parts.joinToString(", ")
                            }
                        }
                    }
                    result
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    val address = addresses?.firstOrNull()
                    if (address != null) {
                        val parts = listOfNotNull(
                            address.locality ?: address.subAdminArea,
                            address.adminArea,
                            address.countryName,
                        ).filter { it.isNotBlank() }
                        if (parts.isNotEmpty()) parts.joinToString(", ") else null
                    } else null
                }
            } else null
        }.getOrNull()

        if (!geocoded.isNullOrBlank()) {
            "User approximate location: $geocoded."
        } else {
            "User approximate coordinates: latitude $lat, longitude $lon."
        }
    }
}
