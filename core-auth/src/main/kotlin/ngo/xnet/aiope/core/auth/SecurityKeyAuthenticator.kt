package ngo.xnet.aiope.core.auth

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Detects and connects to an external hardware security key (YubiKey / Thetis / any CTAP2 key)
 * over USB or NFC using Yubico's yubikit (Apache-2.0, no Google Play Services).
 *
 * v1 scope: confirm a hardware key is present and connectable — this is the meaningful
 * "possession" proof for a device-local verifier. The full FIDO2/CTAP2 make/get-credential
 * ceremony belongs with a server RP and is exposed via the [Verifier] seam.
 *
 * NOTE on testing while tethered: a phone's USB port is occupied by the adb cable, so use an
 * NFC tap to test key presence. USB works when the key is the only device on the port.
 */
class SecurityKeyAuthenticator(private val context: Context) {

  private val yubiKit = YubiKitManager(context)

  /** USB host or NFC hardware present on this device (not whether a key is plugged in). */
  fun isAvailable(): Boolean {
    val pm = context.packageManager
    return pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST) ||
      pm.hasSystemFeature(PackageManager.FEATURE_NFC)
  }

  /**
   * Wait for the user to present a key over USB or tap over NFC, and confirm we can open a
   * SmartCardConnection to it. Resumes with [AuthResult.Success] on the first connectable key.
   *
   * Must be called while [activity] is in the foreground (required for NFC dispatch).
   */
  suspend fun awaitKeyPresence(activity: Activity, enableNfc: Boolean = true): AuthResult =
    suspendCancellableCoroutine { cont ->
      if (!isAvailable()) {
        cont.resume(AuthResult.Unavailable("No USB host or NFC on this device"))
        return@suspendCancellableCoroutine
      }

      val finished = AtomicBoolean(false)
      var usbStarted = false
      var nfcStarted = false

      fun stopAll() {
        try {
          if (usbStarted) yubiKit.stopUsbDiscovery()
        } catch (_: Throwable) {}
        try {
          if (nfcStarted) yubiKit.stopNfcDiscovery(activity)
        } catch (_: Throwable) {}
      }

      fun finish(result: AuthResult) {
        if (!finished.compareAndSet(false, true)) return
        stopAll()
        if (cont.isActive) cont.resume(result)
      }

      // USB discovery: callback fires once a compatible key is attached AND permission granted.
      try {
        yubiKit.startUsbDiscovery(UsbConfiguration()) { device ->
          device.requestConnection(SmartCardConnection::class.java) { result ->
            try {
              result.value.use { /* connection opened => key present & usable */ }
              finish(AuthResult.Success)
            } catch (e: Throwable) {
              finish(AuthResult.Failure("USB connection failed: ${e.message}", e))
            }
          }
        }
        usbStarted = true
      } catch (e: Throwable) {
        // Continue; NFC may still work.
      }

      // NFC discovery: requires foreground activity.
      if (enableNfc) {
        try {
          yubiKit.startNfcDiscovery(NfcConfiguration().timeout(20000), activity) { device ->
            device.requestConnection(SmartCardConnection::class.java) { result ->
              try {
                result.value.use { /* connection opened => key present & usable */ }
                finish(AuthResult.Success)
              } catch (e: Throwable) {
                finish(AuthResult.Failure("NFC connection failed: ${e.message}", e))
              }
            }
          }
          nfcStarted = true
        } catch (e: NfcNotAvailable) {
          // NFC missing/disabled; if USB also didn't start, report unavailable.
          if (!usbStarted) {
            finish(AuthResult.Unavailable("NFC unavailable: ${e.message}"))
          }
        }
      }

      if (!usbStarted && !nfcStarted && !finished.get()) {
        finish(AuthResult.Unavailable("Could not start USB or NFC discovery"))
      }

      cont.invokeOnCancellation { finish(AuthResult.Cancelled) }
    }
}
