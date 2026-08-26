plugins {
  id("aiope.android.library")
  id("aiope.spotless")
}

android {
  namespace = "ngo.xnet.aiope.core.terminal"
}

dependencies {
  // Termux terminal-emulator for JNI (forkpty native)
  implementation("com.github.termux.termux-app:terminal-emulator:v0.118.3")
  implementation(libs.kotlinx.coroutines.android)
}
