plugins {
  id("aiope2.android.library")
  id("aiope2.android.hilt")
  id("aiope2.spotless")
}

android {
  namespace = "com.aiope2.core.network"
}

dependencies {
  api(libs.kotlinx.coroutines.android)
}
