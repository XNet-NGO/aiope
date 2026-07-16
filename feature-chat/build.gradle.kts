plugins {
  id("aiope.android.library")
  id("aiope.android.library.compose")
  id("aiope.android.feature")
  id("aiope.android.hilt")
  id("aiope.spotless")
  id("com.google.devtools.ksp")
}

android {
  namespace = "ngo.xnet.aiope.feature.chat"
  defaultConfig {
    buildConfigField("String", "GATEWAY_KEY", "\"${project.findProperty("GATEWAY_KEY") ?: ""}\"")
  }
  buildFeatures { buildConfig = true }
}

dependencies {
  implementation(project(":core-data"))
  implementation(project(":core-model"))
  implementation(project(":core-terminal"))
  implementation(project(":core-inference"))

  implementation(libs.androidx.lifecycle.runtimeCompose)
  implementation(libs.androidx.lifecycle.viewModelCompose)

  // UniversalMarkdown (streaming markdown renderer)
  implementation(":markwon-core@aar")
  implementation(":markwon-ext-latex@aar")
  implementation(":markwon-ext-strikethrough@aar")
  implementation(":markwon-ext-tables@aar")
  implementation(":markwon-ext-tasklist@aar")
  implementation(":markwon-html@aar")
  implementation(":markwon-image@aar")
  implementation(":markwon-inline-parser@aar")
  implementation(":markwon-syntax-highlight@aar")
  implementation(":fluid-markdown@aar")
  implementation(":universal-markdown-compose@aar")
  implementation("com.atlassian.commonmark:commonmark:0.15.2")
  implementation("com.atlassian.commonmark:commonmark-ext-gfm-tables:0.15.2")
  implementation("com.vdurmont:emoji-java:5.1.1")
  implementation("androidx.recyclerview:recyclerview:1.4.0")
  implementation(libs.androidx.appcompat)
  implementation("io.coil-kt:coil-compose:2.6.0")
  implementation("io.coil-kt:coil-svg:2.6.0")
  implementation("ru.noties:jlatexmath-android:0.2.0")
  implementation("ru.noties:jlatexmath-android-font-cyrillic:0.2.0")
  implementation("ru.noties:jlatexmath-android-font-greek:0.2.0")

  // location
  implementation("com.google.android.gms:play-services-location:21.4.0")

  // maps
  implementation("org.ramani-maps:ramani-maplibre:0.10.0")
  implementation("com.caverock:androidsvg-aar:1.4")

  // room
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  // networking
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)

  // work manager
  implementation(libs.androidx.worker)

  // tokenizer
  implementation(libs.jtokkit)
  implementation(libs.pdfbox.android) {
    exclude(group = "org.bouncycastle")
  }

  // datastore
  implementation("androidx.datastore:datastore-preferences:1.1.7")

  // exoplayer for video backgrounds
  implementation("androidx.media3:media3-exoplayer:1.6.1")
  implementation("androidx.media3:media3-ui:1.6.1")
}
