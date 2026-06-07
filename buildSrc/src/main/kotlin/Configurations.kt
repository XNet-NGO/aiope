object Configurations {
  const val compileSdk = 36
  const val targetSdk = 34
  const val minSdk = 26
  const val majorVersion = 3
  const val minorVersion = 1
  const val patchVersion = 0
  const val versionName = "$majorVersion.$minorVersion.$patchVersion"
  const val versionCode = 14

  // Multi-ABI support: 32-bit ARM (legacy), 64-bit ARM (modern), and Intel variants
  val supportedAbis = listOf(
    "armeabi-v7a",  // 32-bit ARM (older/budget devices, broader compatibility)
    "arm64-v8a",    // 64-bit ARM (modern devices, Google Play minimum)
    "x86",          // Intel 32-bit (emulators, tablets)
    "x86_64"        // Intel 64-bit (modern emulators)
  )
}
