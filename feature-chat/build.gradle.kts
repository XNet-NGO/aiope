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
  defaultConfig {
    buildConfigField("String", "GATEWAY_KEY", "\"${project.findProperty("GATEWAY_KEY") ?: ""}\"")
  }
  buildFeatures { buildConfig = true }
}

dependencies {
  implementation(project(":core-data"))
  implementation(project(":core-model"))
  implementation(project(":core-terminal"))

  implementation(libs.androidx.lifecycle.runtimeCompose)
  implementation(libs.androidx.lifecycle.viewModelCompose)

  // UniversalMarkdown (streaming markdown renderer)
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
  implementation("com.atlassian.commonmark:commonmark:0.15.2")
  implementation("com.atlassian.commonmark:commonmark-ext-gfm-tables:0.15.2")
  implementation("com.vdurmont:emoji-java:5.1.1")
  implementation("androidx.recyclerview:recyclerview:1.4.0")
  implementation(libs.androidx.appcompat)
  implementation("io.coil-kt:coil-compose:2.6.0")
  implementation("ru.noties:jlatexmath-android:0.2.0")
  implementation("ru.noties:jlatexmath-android-font-cyrillic:0.2.0")
  implementation("ru.noties:jlatexmath-android-font-greek:0.2.0")

  // location
  implementation("com.google.android.gms:play-services-location:21.3.0")

  // maps
  implementation("org.ramani-maps:ramani-maplibre:0.10.0")

  // room
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  // networking
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)

  // tokenizer
  implementation(libs.jtokkit)
  implementation(libs.pdfbox.android) {
    exclude(group = "org.bouncycastle")
  }

  // datastore
  implementation("androidx.datastore:datastore-preferences:1.1.7")

  // exoplayer for video backgrounds
  implementation("androidx.media3:media3-exoplayer:1.10.1")
  implementation("androidx.media3:media3-ui:1.10.1")
}
