plugins {
  id("aiope.android.library")
  id("aiope.android.library.compose")
  id("aiope.android.feature")
  id("aiope.android.hilt")
  id("aiope.spotless")
  id("com.google.devtools.ksp")
}

android {
  namespace = "ngo.xnet.aiope.feature.remote"
}

dependencies {
  implementation(project(":core-designsystem"))
  implementation(project(":core-model"))
  implementation(project(":core-navigation"))
  implementation(project(":core-preferences"))

  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  implementation("com.hierynomus:sshj:0.39.0")
  implementation("org.bouncycastle:bcprov-jdk18on:1.85")
  implementation("org.bouncycastle:bcpkix-jdk18on:1.85")

  implementation(libs.kotlinx.coroutines.android)
}
