package ngo.xnet.aiope.feature.chat.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * High-accuracy location provider using the AOSP [LocationManager].
 *
 * This implementation intentionally avoids Google Play Services
 * (com.google.android.gms.location) so the module is fully FOSS and
 * buildable for stores such as F-Droid. Behaviour and the public API are
 * preserved from the previous FusedLocationProviderClient-based version.
 *
 * - getLastLocation(): immediate, battery-efficient (best cached fix)
 * - getFreshLocation(): a single fresh high-accuracy fix
 * - locationUpdates(): real-time tracking flow
 */
class LocationProvider(private val context: Context) {

  private val locationManager: LocationManager =
    context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

  private fun hasPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  /** Providers we care about, most-accurate first, filtered to those enabled. */
  private fun activeProviders(): List<String> =
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
      .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

  /** Get last known location — immediate, battery-efficient.
   *  Chooses the most recent, most accurate cached fix across providers. */
  @SuppressWarnings("MissingPermission")
  suspend fun getLastLocation(): Location? {
    if (!hasPermission()) return null
    return activeProviders()
      .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
      .maxWithOrNull(bestFixComparator)
  }

  /** Request a single fresh high-accuracy fix. */
  @SuppressWarnings("MissingPermission")
  suspend fun getFreshLocation(): Location? {
    if (!hasPermission()) return null

    // Prefer GPS, fall back to network, then last known.
    val provider = when {
      runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
        LocationManager.GPS_PROVIDER
      runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
        LocationManager.NETWORK_PROVIDER
      else -> return getLastLocation()
    }

    return suspendCancellableCoroutine { cont ->
      val resumed = AtomicBoolean(false)
      fun finish(loc: Location?) {
        if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(loc)
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // API 30+: getCurrentLocation with a cancellation signal.
        val cancellation = android.os.CancellationSignal()
        cont.invokeOnCancellation { runCatching { cancellation.cancel() } }
        runCatching {
          locationManager.getCurrentLocation(
            provider,
            cancellation,
            ContextCompat.getMainExecutor(context),
          ) { loc -> finish(loc) }
        }.onFailure { finish(runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()) }
      } else {
        // Legacy: single update via a one-shot listener.
        val listener = object : LocationListener {
          override fun onLocationChanged(location: Location) {
            runCatching { locationManager.removeUpdates(this) }
            finish(location)
          }

          override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
          override fun onProviderEnabled(p: String) {}
          override fun onProviderDisabled(p: String) {}
        }
        cont.invokeOnCancellation { runCatching { locationManager.removeUpdates(listener) } }
        runCatching {
          @Suppress("DEPRECATION")
          locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }.onFailure { finish(runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()) }
      }
    }
  }

  /** Live location updates as a Flow. */
  @SuppressWarnings("MissingPermission")
  fun locationUpdates(): Flow<Location> = callbackFlow {
    if (!hasPermission()) {
      close()
      return@callbackFlow
    }
    val provider = when {
      runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
        LocationManager.GPS_PROVIDER
      runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
        LocationManager.NETWORK_PROVIDER
      else -> {
        close()
        return@callbackFlow
      }
    }

    val listener = object : LocationListener {
      override fun onLocationChanged(location: Location) {
        trySend(location)
      }

      override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
      override fun onProviderEnabled(p: String) {}
      override fun onProviderDisabled(p: String) {}
    }

    runCatching {
      locationManager.requestLocationUpdates(
        provider,
        2_000L, // minTime ms — matches prior min update interval
        0f, // minDistance m
        listener,
        Looper.getMainLooper(),
      )
    }.onFailure { close(it) }

    awaitClose { runCatching { locationManager.removeUpdates(listener) } }
  }

  /** Format location as a readable string for the agent. */
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

  /** Reverse geocode to get address/city. */
  fun reverseGeocode(loc: Location): String? = try {
    val geocoder = android.location.Geocoder(context, java.util.Locale.US)
    @Suppress("DEPRECATION")
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

  private companion object {
    /** Ranks fixes: prefers a real accuracy value, then the most recent timestamp. */
    val bestFixComparator = Comparator<Location> { a, b ->
      when {
        a.hasAccuracy() && b.hasAccuracy() && a.accuracy != b.accuracy ->
          // smaller accuracy radius is better -> higher rank
          b.accuracy.compareTo(a.accuracy)
        else -> a.time.compareTo(b.time)
      }
    }
  }
}
