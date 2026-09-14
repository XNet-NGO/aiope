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
    // ORT is our OWN source build (1.28.2) matching sherpa's bundled core, so face/RAG/detection
    // (ai.onnxruntime Java API) and sherpa STT share ONE libonnxruntime.so. The Java classes come
    // from the local jar; the native libs (libonnxruntime.so + libonnxruntime4j_jni.so) are in
    // src/main/jniLibs. (Replaces the Maven com.microsoft.onnxruntime:onnxruntime-android.)
    implementation(files("libs/onnxruntime-1.28.2.jar"))
    // DJL HuggingFace tokenizers Java API. Exclude its bundled desktop native jar;
    // the Android native libs come from tokenizer-native (AAR with arm64/x86 jniLibs).
    implementation(libs.djl.tokenizers) {
        exclude(group = "ai.djl.huggingface", module = "tokenizers-native")
    }
    runtimeOnly(libs.djl.tokenizer.native.android)

    // Offline streaming STT (Apache-2.0). Bundles native libs + Kotlin API (OnlineRecognizer).
    implementation(libs.sherpa.onnx)
}
