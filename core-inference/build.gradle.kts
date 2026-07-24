plugins {
    id("com.android.library")
}

android {
    namespace = "org.xnet.aiope.inference"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                cppFlags += "-std=c++17 -O3 -ffast-math -fno-finite-math-only"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
