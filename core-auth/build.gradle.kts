plugins {
  id("aiope.android.library")
  id("aiope.android.hilt")
  id("aiope.spotless")
}

android {
  namespace = "ngo.xnet.aiope.core.auth"
}

dependencies {
  api(libs.kotlinx.coroutines.android)
  implementation(project(":core-preferences"))

  // Optional, opt-in auth factors. No Google Play Services.
  implementation(libs.androidx.biometric)

  // Hardware security keys (YubiKey / Thetis and other CTAP2 keys) over USB + NFC.
  implementation(libs.yubikit.android)
  implementation(libs.yubikit.fido)
  implementation(libs.yubikit.oath)
}
