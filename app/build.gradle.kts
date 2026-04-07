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
    }
  }
}

dependencies {
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
