plugins {
    id("com.android.library")
}

android {
    namespace = "org.xnet.aiope.inference"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    implementation("com.google.ai.edge.litert:litert:2.1.5")
}
