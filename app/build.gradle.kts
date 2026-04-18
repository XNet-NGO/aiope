import java.util.Properties

plugins {
  id("aiope2.android.application")
  id("aiope2.android.application.compose")
  id("aiope2.android.hilt")
  id("aiope2.spotless")
  id("kotlin-parcelize")
  id("dagger.hilt.android.plugin")
  id("com.google.devtools.ksp")
}

android {
  namespace = "com.aiope2"
  compileSdk = Configurations.compileSdk

  defaultConfig {
    applicationId = "com.aiope2"
    minSdk = Configurations.minSdk
    targetSdk = Configurations.targetSdk
    versionCode = Configurations.versionCode
    versionName = Configurations.versionName
    buildConfigField("String", "GATEWAY_KEY", "\"${project.findProperty("GATEWAY_KEY") ?: ""}\"")
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
}
