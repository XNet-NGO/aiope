package com.aiope2.feature.chat.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * High-accuracy location provider using FusedLocationProviderClient.
 * - getLastLocation(): immediate, battery-efficient
 * - locationUpdates(): real-time tracking flow
 */
class LocationProvider(private val context: Context) {

  private val client: FusedLocationProviderClient =
    LocationServices.getFusedLocationProviderClient(context)

  private val highAccuracyRequest = LocationRequest.Builder(
    Priority.PRIORITY_HIGH_ACCURACY,
    5_000L,
  ).setMinUpdateIntervalMillis(2_000L)
    .setMaxUpdateDelayMillis(10_000L)
    .build()

  private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

  /** Get last known location — immediate, battery-efficient */
  @SuppressWarnings("MissingPermission")
  suspend fun getLastLocation(): Location? {
    if (!hasPermission()) return null
    return suspendCancellableCoroutine { cont ->
      client.lastLocation
        .addOnSuccessListener { loc -> cont.resume(loc) }
        .addOnFailureListener { cont.resume(null) }
    }
  }

  /** Request a single fresh high-accuracy fix */
  @SuppressWarnings("MissingPermission")
  suspend fun getFreshLocation(): Location? {
    if (!hasPermission()) return null
    return suspendCancellableCoroutine { cont ->
      val req = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .setMaxUpdateAgeMillis(5_000L)
        .build()
      client.getCurrentLocation(req, null)
        .addOnSuccessListener { loc -> cont.resume(loc) }
        .addOnFailureListener { cont.resume(null) }
    }
  }

  /** Live location updates as a Flow */
  @SuppressWarnings("MissingPermission")
  fun locationUpdates(): Flow<Location> = callbackFlow {
    if (!hasPermission()) {
      close()
      return@callbackFlow
    }
    val callback = object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        result.lastLocation?.let { trySend(it) }
      }
    }
    client.requestLocationUpdates(highAccuracyRequest, callback, Looper.getMainLooper())
    awaitClose { client.removeLocationUpdates(callback) }
  }

  /** Format location as a readable string for the agent */
  fun formatLocation(loc: Location): String = buildString {
    append("Latitude: ${loc.latitude}\n")
    append("Longitude: ${loc.longitude}\n")
    if (loc.hasAltitude()) append("Altitude: ${"%.1f".format(loc.altitude)}m\n")
    if (loc.hasSpeed()) append("Speed: ${"%.1f".format(loc.speed * 3.6)} km/h\n")
    if (loc.hasBearing()) append("Bearing: ${"%.0f".format(loc.bearing)} degrees\n")
    append("Accuracy: ${"%.1f".format(loc.accuracy)}m\n")
    append("Provider: ${loc.provider}\n")
    append("Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(loc.time)}")
  }

  /** Reverse geocode to get address/city */
  fun reverseGeocode(loc: Location): String? = try {
    val geocoder = android.location.Geocoder(context, java.util.Locale.US)
    val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
    if (!addresses.isNullOrEmpty()) {
      val addr = addresses[0]
      buildString {
        addr.getAddressLine(0)?.let { append("Address: $it\n") }
          ?: run {
            addr.locality?.let { append("City: $it\n") }
            addr.adminArea?.let { append("State: $it\n") }
            addr.countryName?.let { append("Country: $it\n") }
          }
      }.trimEnd()
    } else {
      null
    }
  } catch (_: Exception) {
    null
  }
}
