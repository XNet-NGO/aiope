plugins {
  id("aiope.android.library")
  id("aiope.android.hilt")
  id("aiope.spotless")
}

android {
  namespace = "ngo.xnet.aiope.core.network"
}

dependencies {
  api(libs.kotlinx.coroutines.android)
}
