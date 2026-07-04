plugins {
  id("aiope.android.library")
  id("aiope.android.hilt")
  id("aiope.spotless")
}

android {
  namespace = "ngo.xnet.aiope.core.data"
}

dependencies {
  api(project(":core-model"))
  api(project(":core-network"))
  api(project(":core-preferences"))
}
