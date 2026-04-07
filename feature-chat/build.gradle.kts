plugins {
  id("aiope2.android.library")
  id("aiope2.android.library.compose")
  id("aiope2.android.feature")
  id("aiope2.android.hilt")
  id("aiope2.spotless")
  id("com.google.devtools.ksp")
}

android {
  namespace = "com.aiope2.feature.chat"
}

dependencies {
  implementation(project(":core-data"))
  implementation(project(":core-terminal"))

  implementation(libs.androidx.lifecycle.runtimeCompose)
  implementation(libs.androidx.lifecycle.viewModelCompose)

  // markdown
  implementation(libs.markwon.core)
  implementation(libs.markwon.ext.strikethrough)
  implementation("io.noties.markwon:ext-tables:4.6.2")

  // location
  implementation("com.google.android.gms:play-services-location:21.3.0")

  // maps
  implementation("org.ramani-maps:ramani-maplibre:0.10.0")

  // maps
  implementation("org.ramani-maps:ramani-maplibre:0.6.0")

  // room
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
}
