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

    // We ship a self-built ONNX Runtime 1.28.2 (matching sherpa) for arm64-v8a only. Restrict the
    // build to arm64-v8a so no ABI is packaged without a matching libonnxruntime.so.
    ndk { abiFilters.add("arm64-v8a") }
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
      excludes.add("/META-INF/LICENSE.md")
      excludes.add("/META-INF/NOTICE.md")
      // DJL 'tokenizers' jar bundles desktop native libs as resources (osx/win/linux).
      // We only run on Android (native libs come from tokenizer-native AAR jniLibs).
      excludes.add("native/lib/osx-**")
      excludes.add("native/lib/win-**")
      excludes.add("native/lib/linux-**")
      excludes.add("**/*.dylib")
      excludes.add("native/lib/tokenizers.properties")
    }
    jniLibs.useLegacyPackaging = true
    // Both onnxruntime-android (1.22.0) and sherpa-onnx (bundles 1.27.1) ship libonnxruntime.so.
    // ONNX Runtime is backward-compatible across these versions and the C ABI is stable, so we
    // pick one. This keeps both the Java ORT API (face/RAG/detection) and sherpa's JNI working.
    jniLibs.pickFirsts.add("**/libonnxruntime.so")
  }

  buildTypes {
    release {
      isShrinkResources = true
      isMinifyEnabled = true
      // Alt-store distribution (Obtainium/IzzyOnDroid/F-Droid) requires a STABLE release
      // signature so updates install over prior versions. Never silently fall back to the
      // debug key for a real release build — fail loudly so we don't ship an unstable signature.
      val releaseSigning = signingConfigs.findByName("release")
      if (releaseSigning != null) {
        signingConfig = releaseSigning
      } else {
        val allowUnsigned = (project.findProperty("allowUnsignedRelease") as String?) == "true"
        if (allowUnsigned) {
          logger.warn("WARNING: release keystore.properties missing — falling back to DEBUG signing (allowUnsignedRelease=true). DO NOT distribute this build.")
          signingConfig = signingConfigs.getByName("debug")
        } else {
          throw GradleException(
            "Release keystore.properties not found. A stable release signature is required for " +
              "distribution. Provide keystore.properties, or pass -PallowUnsignedRelease=true to " +
              "build a debug-signed (non-distributable) release for local testing only.",
          )
        }
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
}

// Force transitive org.json:json (via com.vdurmont:emoji-java) to a patched version.
// 20170516 has DoS + stack-overflow advisories; 20231013 fixes both.
configurations.all {
  resolutionStrategy {
    force("org.json:json:20231013")
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // core modules
  implementation(project(":core-designsystem"))
  implementation(project(":core-navigation"))
  implementation(project(":core-data"))
  implementation(project(":core-terminal"))
  implementation(project(":core-inference"))

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
