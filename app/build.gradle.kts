import java.util.Properties

plugins {
  id("aiope.android.application")
  id("aiope.android.application.compose")
  id("aiope.android.hilt")
  id("aiope.spotless")
  id("kotlin-parcelize")
  id("dagger.hilt.android.plugin")
  id("com.google.devtools.ksp")
}

android {
  namespace = "ngo.xnet.aiope"
  compileSdk = Configurations.compileSdk

  defaultConfig {
    applicationId = "ngo.xnet.aiope"
    minSdk = Configurations.minSdk
    targetSdk = Configurations.targetSdk
    versionCode = Configurations.versionCode
    versionName = Configurations.versionName
    buildConfigField("String", "GATEWAY_KEY", "\"${rootProject.file("secrets.properties").let { f -> if (f.exists()) Properties().apply { f.inputStream().use { load(it) } }.getProperty("GATEWAY_KEY", "") else "" }}\"")
  }

  buildFeatures { buildConfig = true }

  val keystoreProps = Properties()
  rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { keystoreProps.load(it) }

  signingConfigs {
    if (keystoreProps.getProperty("storeFile") != null) {
      create("release") {
        storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }

  packaging {
    resources {
      excludes.add("/META-INF/{AL2.0,LGPL2.1}")
      excludes.add("META-INF/LICENSE.md")
      excludes.add("META-INF/LICENSE")
      excludes.add("META-INF/NOTICE.md")
    }
    jniLibs.useLegacyPackaging = true
  }

  buildTypes {
    release {
      isShrinkResources = true
      isMinifyEnabled = true
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // core modules
  implementation(project(":core-designsystem"))
  implementation(project(":core-navigation"))
  implementation(project(":core-data"))
  implementation(project(":core-terminal"))

  // feature modules
  implementation(project(":feature-chat"))
  implementation("io.coil-kt:coil:2.6.0")
  implementation("io.coil-kt:coil-svg:2.6.0")
  implementation(project(":feature-remote"))

  // compose
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.runtime)
  implementation(libs.androidx.compose.ui.tooling)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.constraintlayout)

  // jetpack
  implementation(libs.androidx.startup)
  implementation(libs.hilt.android)
  implementation(libs.androidx.hilt.navigation.compose)
  ksp(libs.hilt.compiler)
  implementation("com.google.errorprone:error_prone_annotations:2.50.0")
}
