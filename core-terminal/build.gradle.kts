plugins {
  id("aiope2.android.library")
  id("aiope2.spotless")
}

android {
  namespace = "com.aiope2.core.terminal"
}

dependencies {
  // Termux terminal-emulator for JNI (forkpty native)
  implementation("com.github.termux.termux-app:terminal-emulator:v0.118.3")
  implementation(libs.kotlinx.coroutines.android)
}
