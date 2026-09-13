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

    // On-device embeddings: ONNX Runtime + HuggingFace tokenizer (reads tokenizer.json)
    implementation(libs.onnxruntime.android)
    // DJL HuggingFace tokenizers Java API. Exclude its bundled desktop native jar;
    // the Android native libs come from tokenizer-native (AAR with arm64/x86 jniLibs).
    implementation(libs.djl.tokenizers) {
        exclude(group = "ai.djl.huggingface", module = "tokenizers-native")
    }
    runtimeOnly(libs.djl.tokenizer.native.android)
}
