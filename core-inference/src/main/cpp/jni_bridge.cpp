#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml.h"

#define TAG "inf-serv"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct EngineState {
    llama_model   *model   = nullptr;
    llama_context *ctx     = nullptr;
    llama_sampler *sampler = nullptr;
    std::atomic<bool> abort_flag{false};
    std::mutex    mtx;
};

static EngineState *getState(jlong handle) {
    return reinterpret_cast<EngineState *>(handle);
}

static std::string jstringToStd(JNIEnv *env, jstring js) {
    if (!js) return {};
    const char *utf = env->GetStringUTFChars(js, nullptr);
    std::string s(utf);
    env->ReleaseStringUTFChars(js, utf);
    return s;
}

static std::string tokenToString(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::vector<char> big(-n + 1);
        n = llama_token_to_piece(vocab, token, big.data(), big.size(), 0, false);
        if (n > 0) return std::string(big.data(), n);
        return {};
    }
    return std::string(buf, n);
}

// Validate Modified UTF-8 for JNI
static bool isValidModifiedUtf8(const std::string &s) {
    const unsigned char *p = (const unsigned char *)s.data();
    const unsigned char *end = p + s.size();
    while (p < end) {
        unsigned char c = *p;
        if (c == 0) return false;
        if (c < 0x80) { p++; continue; }
        int len;
        if      ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else return false; // 4-byte not valid in Modified UTF-8
        if (p + len > end) return false;
        for (int j = 1; j < len; j++) {
            if ((p[j] & 0xC0) != 0x80) return false;
        }
        if (len == 3) {
            uint32_t cp = ((p[0] & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
            if (cp >= 0xD800 && cp <= 0xDFFF) return false;
        }
        p += len;
    }
    return true;
}

// --- JNI: lifecycle ---

extern "C" JNIEXPORT jlong JNICALL
Java_org_xnet_aiope_inference_LlamaEngine_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new EngineState());
}

extern "C" JNIEXPORT void JNICALL
Java_org_xnet_aiope_inference_LlamaEngine_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto *state = getState(handle);
    if (!state) return;
    std::lock_guard<std::mutex> lock(state->mtx);
    if (state->sampler) { llama_sampler_free(state->sampler); state->sampler = nullptr; }
    if (state->ctx)     { llama_free(state->ctx);             state->ctx     = nullptr; }
    if (state->model)   { llama_model_free(state->model);     state->model   = nullptr; }
    delete state;
}

// --- JNI: loadModel ---

extern "C" JNIEXPORT jboolean JNICALL
Java_org_xnet_aiope_inference_LlamaEngine_nativeLoadModel(
        JNIEnv *env, jobject, jlong handle,
        jstring jPath, jint contextSize, jint nThreads) {

    auto *state = getState(handle);
    if (!state) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mtx);

    if (state->sampler) { llama_sampler_free(state->sampler); state->sampler = nullptr; }
    if (state->ctx)     { llama_free(state->ctx);             state->ctx     = nullptr; }
    if (state->model)   { llama_model_free(state->model);     state->model   = nullptr; }

    state->abort_flag.store(false);
    std::string path = jstringToStd(env, jPath);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    LOGI("Loading model: %s (ctx=%d, threads=%d)", path.c_str(), contextSize, nThreads);

    state->model = llama_model_load_from_file(path.c_str(), model_params);
    if (!state->model) {
        LOGE("Failed to load model: %s", path.c_str());
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx     = (contextSize > 0) ? static_cast<uint32_t>(contextSize) : 4096;
    ctx_params.n_threads = (nThreads > 0) ? nThreads : 6;
    ctx_params.n_threads_batch = ctx_params.n_threads;
    ctx_params.n_batch   = 512;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    ctx_params.type_k    = GGML_TYPE_Q8_0;
    ctx_params.type_v    = GGML_TYPE_Q8_0;

    state->ctx = llama_init_from_model(state->model, ctx_params);
    if (!state->ctx) {
        LOGE("Failed to create context");
        llama_model_free(state->model);
        state->model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

// --- JNI: generate (streaming via callback) ---

extern "C" JNIEXPORT jboolean JNICALL
Java_org_xnet_aiope_inference_LlamaEngine_nativeGenerate(
        JNIEnv *env, jobject, jlong handle,
        jstring jPrompt, jint maxTokens, jfloat temperature, jfloat topP,
        jfloat repeatPenalty, jobject callback) {

    auto *state = getState(handle);
    if (!state || !state->model || !state->ctx) return JNI_FALSE;

    state->abort_flag.store(false);
    llama_memory_clear(llama_get_memory(state->ctx), true);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken    = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "(FI)V");

    if (!onToken || !onComplete) return JNI_FALSE;

    std::string prompt = jstringToStd(env, jPrompt);
    const llama_vocab *vocab = llama_model_get_vocab(state->model);

    // Tokenize
    int n_prompt_max = prompt.size() + 128;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                                  tokens.data(), n_prompt_max, true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                                  tokens.data(), tokens.size(), true, true);
    }
    if (n_tokens < 0) return JNI_FALSE;
    tokens.resize(n_tokens);

    // Sampler
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);

    float temp = (temperature > 0.0f) ? temperature : 0.8f;
    float top_p_val = (topP > 0.0f && topP <= 1.0f) ? topP : 0.95f;
    float rep_pen = (repeatPenalty > 0.0f) ? repeatPenalty : 1.1f;

    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, rep_pen, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p_val, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    if (state->sampler) llama_sampler_free(state->sampler);
    state->sampler = smpl;

    // Evaluate prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(state->ctx, batch) != 0) return JNI_FALSE;

    // Generate
    auto gen_start = std::chrono::steady_clock::now();
    int tokens_generated = 0;
    int max = (maxTokens > 0) ? maxTokens : 512;

    for (int i = 0; i < max; i++) {
        if (state->abort_flag.load()) break;

        llama_token new_token = llama_sampler_sample(smpl, state->ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token)) break;

        std::string piece = tokenToString(vocab, new_token);
        if (piece.empty()) continue;
        if (!isValidModifiedUtf8(piece)) continue;

        jstring jPiece = env->NewStringUTF(piece.c_str());
        if (!jPiece) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            continue;
        }
        jboolean shouldContinue = env->CallBooleanMethod(callback, onToken, jPiece);
        env->DeleteLocalRef(jPiece);

        if (!shouldContinue) break;

        tokens_generated++;

        llama_batch next = llama_batch_get_one(&new_token, 1);
        if (llama_decode(state->ctx, next) != 0) break;
    }

    auto gen_end = std::chrono::steady_clock::now();
    auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(gen_end - gen_start).count();
    float tps = (elapsed_ms > 0) ? (tokens_generated * 1000.0f / elapsed_ms) : 0.0f;

    env->CallVoidMethod(callback, onComplete, tps, (jint)tokens_generated);
    return JNI_TRUE;
}

// --- JNI: abort / unload ---

extern "C" JNIEXPORT void JNICALL
Java_org_xnet_aiope_inference_LlamaEngine_nativeAbort(JNIEnv *, jobject, jlong handle) {
    auto *state = getState(handle);
    if (state) state->abort_flag.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_org_xnet_aiope_inference_LlamaEngine_nativeUnload(JNIEnv *, jobject, jlong handle) {
    auto *state = getState(handle);
    if (!state) return;
    std::lock_guard<std::mutex> lock(state->mtx);
    if (state->sampler) { llama_sampler_free(state->sampler); state->sampler = nullptr; }
    if (state->ctx)     { llama_free(state->ctx);             state->ctx     = nullptr; }
    if (state->model)   { llama_model_free(state->model);     state->model   = nullptr; }
}

// --- JNI: embedding ---

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_xnet_aiope_inference_LlamaEngine_nativeEmbed(JNIEnv *env, jobject, jlong handle, jstring jtext) {
    auto *state = getState(handle);
    if (!state || !state->model || !state->ctx) return nullptr;

    std::lock_guard<std::mutex> lock(state->mtx);

    std::string text = jstringToStd(env, jtext);
    if (text.empty()) return nullptr;

    const llama_vocab *vocab = llama_model_get_vocab(state->model);
    int32_t n_embd = llama_model_n_embd(state->model);

    // Tokenize
    std::vector<llama_token> tokens(512);
    int32_t n_tokens = llama_tokenize(vocab, text.c_str(), text.size(),
                                       tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, text.c_str(), text.size(),
                                   tokens.data(), tokens.size(), true, true);
        if (n_tokens < 0) return nullptr;
    }
    tokens.resize(n_tokens);

    // Truncate to context size
    uint32_t n_ctx = llama_n_ctx(state->ctx);
    if ((uint32_t)n_tokens > n_ctx) {
        tokens.resize(n_ctx);
        n_tokens = n_ctx;
    }

    // Clear KV cache
    llama_memory_clear(llama_get_memory(state->ctx), true);

    // Decode
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(state->ctx, batch) != 0) {
        LOGE("Embed: decode failed");
        return nullptr;
    }

    // Get embeddings (try sequence first, then fallback)
    const float *emb = llama_get_embeddings_seq(state->ctx, 0);
    if (!emb) {
        emb = llama_get_embeddings(state->ctx);
    }
    if (!emb) {
        LOGE("Embed: no embeddings returned");
        return nullptr;
    }

    // Normalize
    std::vector<float> normalized(n_embd);
    float norm = 0.0f;
    for (int i = 0; i < n_embd; i++) norm += emb[i] * emb[i];
    norm = sqrtf(norm);
    if (norm > 0.0f) {
        for (int i = 0; i < n_embd; i++) normalized[i] = emb[i] / norm;
    } else {
        for (int i = 0; i < n_embd; i++) normalized[i] = 0.0f;
    }

    // Return as Java float array
    jfloatArray result = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(result, 0, n_embd, normalized.data());
    return result;
}
