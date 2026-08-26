import java.util.Properties

plugins {
  id("aiope.android.application")
  id("aiope.android.application.compose")
  id("aiope.spotless")
  id("kotlin-parcelize")
  alias(libs.plugins.secrets)
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
  }

  buildFeatures { buildConfig = true }

  secrets {
    defaultPropertiesFileName = "secrets.defaults.properties"
    propertiesFileName = "secrets.properties"
    ignoreList.add("keyToIgnore")
    ignoreList.add("sdk.*")
  }

  val keystoreProps = Properties()
  rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { keystoreProps.load(it) }

  signingConfigs {
    create("debugConfig") {
      storeFile = rootProject.file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
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
      excludes.add("/META-INF/LICENSE.md")
      excludes.add("/META-INF/NOTICE.md")
    }
    jniLibs.useLegacyPackaging = true
  }

  buildTypes {
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
    release {
      isShrinkResources = true
      isMinifyEnabled = true
      signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debugConfig")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // core modules
  implementation(project(":core-designsystem"))

  // feature modules
  implementation("io.coil-kt:coil:2.6.0")
  implementation("io.coil-kt:coil-svg:2.6.0")

  // compose
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.runtime)
  implementation(libs.androidx.compose.ui.tooling)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.constraintlayout)

  // jetpack
  implementation(libs.androidx.startup)
  implementation("com.google.errorprone:error_prone_annotations:2.50.0")
}
