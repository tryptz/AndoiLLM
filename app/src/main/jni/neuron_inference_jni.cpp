#include <jni.h>
#include <android/log.h>
#include <string>
#include <mutex>
#include <atomic>
#include <chrono>
#include <fstream>

#define TAG "NeuronInference"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if LLAMA_AVAILABLE
#include "llama.h"
#include "common.h"
#endif

// ── Global state ──
struct ModelContext {
#if LLAMA_AVAILABLE
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
#endif
    int n_ctx = 0;
    std::mutex mtx;
};

struct CompletionContext {
#if LLAMA_AVAILABLE
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
#endif
    std::atomic<bool> cancelled{false};
    float last_tok_sec = 0.0f;
    int tokens_generated = 0;
};

static std::atomic<float> g_last_tok_sec{0.0f};

// ── Helpers ──
static float read_cpu_temperature() {
    std::ifstream f("/sys/class/thermal/thermal_zone0/temp");
    if (f.is_open()) {
        float val;
        f >> val;
        if (val > 1000.0f) val /= 1000.0f;
        return val;
    }
    return -1.0f;
}

extern "C" {

// ── Model lifecycle ──

JNIEXPORT jlong JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_loadModel(
    JNIEnv* env, jobject,
    jstring modelPath, jint contextLength, jint batchSize,
    jint threadCount, jint gpuLayers, jboolean useVulkan,
    jint kvCacheTypeQuant)
{
#if LLAMA_AVAILABLE
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = gpuLayers;
    // Vulkan would be configured via ggml backend init

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = contextLength;
    cparams.n_batch = batchSize;
    cparams.n_threads = threadCount > 0 ? threadCount : 4;
    cparams.n_threads_batch = cparams.n_threads;

    // KV cache quantization
    switch (kvCacheTypeQuant) {
        case 1: cparams.type_k = GGML_TYPE_Q8_0; cparams.type_v = GGML_TYPE_Q8_0; break;
        case 2: cparams.type_k = GGML_TYPE_Q4_0; cparams.type_v = GGML_TYPE_Q4_0; break;
        default: cparams.type_k = GGML_TYPE_F16; cparams.type_v = GGML_TYPE_F16; break;
    }

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto* mc = new ModelContext();
    mc->model = model;
    mc->ctx = ctx;
    mc->n_ctx = contextLength;

    // Initialize sampler
    mc->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());

    LOGI("Model loaded successfully, ctx=%d", contextLength);
    return reinterpret_cast<jlong>(mc);
#else
    LOGE("llama.cpp not available — stub mode");
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_unloadModel(
    JNIEnv*, jobject, jlong handle)
{
#if LLAMA_AVAILABLE
    auto* mc = reinterpret_cast<ModelContext*>(handle);
    if (!mc) return;
    std::lock_guard<std::mutex> lock(mc->mtx);
    if (mc->sampler) llama_sampler_free(mc->sampler);
    if (mc->ctx) llama_free(mc->ctx);
    if (mc->model) llama_model_free(mc->model);
    delete mc;
    LOGI("Model unloaded");
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_isModelLoaded(
    JNIEnv*, jobject, jlong handle)
{
#if LLAMA_AVAILABLE
    auto* mc = reinterpret_cast<ModelContext*>(handle);
    return mc && mc->model && mc->ctx;
#else
    return JNI_FALSE;
#endif
}

// ── Inference ──

JNIEXPORT jlong JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_startCompletion(
    JNIEnv* env, jobject, jlong handle,
    jstring prompt, jfloat temperature, jfloat topP,
    jint topK, jfloat minP, jfloat repeatPenalty, jint maxTokens)
{
#if LLAMA_AVAILABLE
    auto* mc = reinterpret_cast<ModelContext*>(handle);
    if (!mc || !mc->ctx) return 0;

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptCpp(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    // Tokenize prompt
    const llama_vocab* vocab = llama_model_get_vocab(mc->model);
    std::vector<llama_token> tokens(promptCpp.size() + 16);
    int n_tokens = llama_tokenize(vocab, promptCpp.c_str(), promptCpp.size(),
                                   tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, promptCpp.c_str(), promptCpp.size(),
                                   tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(n_tokens);

    LOGI("Tokenized prompt: %d tokens", n_tokens);

    // Configure sampler
    if (mc->sampler) llama_sampler_free(mc->sampler);
    mc->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(mc->sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(mc->sampler, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(mc->sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(mc->sampler, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(mc->sampler, llama_sampler_init_penalties(repeatPenalty, 0.0f, 0.0f));

    // Eval prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(mc->ctx, batch) != 0) {
        LOGE("Failed to eval prompt");
        return 0;
    }

    auto* cc = new CompletionContext();
    cc->ctx = mc->ctx;
    cc->sampler = mc->sampler;
    cc->cancelled = false;
    cc->tokens_generated = 0;

    return reinterpret_cast<jlong>(cc);
#else
    return 0;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_getNextToken(
    JNIEnv* env, jobject, jlong completionHandle)
{
#if LLAMA_AVAILABLE
    auto* cc = reinterpret_cast<CompletionContext*>(completionHandle);
    if (!cc || !cc->ctx || !cc->sampler || cc->cancelled.load()) return nullptr;

    auto t_start = std::chrono::high_resolution_clock::now();

    // Sample next token
    llama_token token = llama_sampler_sample(cc->sampler, cc->ctx, -1);

    // Check for EOS
    const llama_vocab* vocab = llama_model_get_vocab(llama_get_model(cc->ctx));
    if (llama_vocab_is_eog(vocab, token)) {
        return nullptr; // end of generation
    }

    // Accept and decode
    llama_sampler_accept(cc->sampler, token);

    // Convert token to string
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n < 0) return nullptr;

    // Eval the new token
    llama_batch batch = llama_batch_get_one(&token, 1);
    if (llama_decode(cc->ctx, batch) != 0) {
        LOGE("Failed to decode token");
        return nullptr;
    }

    cc->tokens_generated++;

    auto t_end = std::chrono::high_resolution_clock::now();
    float elapsed_ms = std::chrono::duration<float, std::milli>(t_end - t_start).count();
    cc->last_tok_sec = 1000.0f / elapsed_ms;
    g_last_tok_sec.store(cc->last_tok_sec);

    return env->NewStringUTF(std::string(buf, n).c_str());
#else
    return nullptr;
#endif
}

JNIEXPORT void JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_cancelCompletion(
    JNIEnv*, jobject, jlong completionHandle)
{
#if LLAMA_AVAILABLE
    auto* cc = reinterpret_cast<CompletionContext*>(completionHandle);
    if (cc) {
        cc->cancelled.store(true);
        LOGI("Completion cancelled");
    }
#endif
}

// ── Tokenizer ──

JNIEXPORT jintArray JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_tokenize(
    JNIEnv* env, jobject, jlong handle, jstring text)
{
#if LLAMA_AVAILABLE
    auto* mc = reinterpret_cast<ModelContext*>(handle);
    if (!mc || !mc->model) return env->NewIntArray(0);

    const char* str = env->GetStringUTFChars(text, nullptr);
    const llama_vocab* vocab = llama_model_get_vocab(mc->model);

    std::vector<llama_token> tokens(strlen(str) + 16);
    int n = llama_tokenize(vocab, str, strlen(str), tokens.data(), tokens.size(), true, true);
    env->ReleaseStringUTFChars(text, str);

    if (n < 0) { tokens.resize(-n); n = llama_tokenize(vocab, str, strlen(str), tokens.data(), tokens.size(), true, true); }
    tokens.resize(n);

    jintArray result = env->NewIntArray(n);
    env->SetIntArrayRegion(result, 0, n, reinterpret_cast<jint*>(tokens.data()));
    return result;
#else
    return env->NewIntArray(0);
#endif
}

JNIEXPORT jstring JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_detokenize(
    JNIEnv* env, jobject, jlong handle, jintArray tokens)
{
#if LLAMA_AVAILABLE
    auto* mc = reinterpret_cast<ModelContext*>(handle);
    if (!mc || !mc->model) return env->NewStringUTF("");

    int n = env->GetArrayLength(tokens);
    jint* data = env->GetIntArrayElements(tokens, nullptr);
    const llama_vocab* vocab = llama_model_get_vocab(mc->model);

    std::string result;
    char buf[256];
    for (int i = 0; i < n; i++) {
        int len = llama_token_to_piece(vocab, data[i], buf, sizeof(buf), 0, true);
        if (len > 0) result.append(buf, len);
    }
    env->ReleaseIntArrayElements(tokens, data, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
#else
    return env->NewStringUTF("");
#endif
}

JNIEXPORT jint JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_getContextUsed(
    JNIEnv*, jobject, jlong handle)
{
#if LLAMA_AVAILABLE
    auto* mc = reinterpret_cast<ModelContext*>(handle);
    if (!mc || !mc->ctx) return 0;
    return llama_get_kv_cache_used_cells(mc->ctx);
#else
    return 0;
#endif
}

// ── Telemetry ──

JNIEXPORT jfloat JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_getLastTokensPerSec(JNIEnv*, jobject)
{
    return g_last_tok_sec.load();
}

JNIEXPORT jint JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_getModelRamUsageMb(
    JNIEnv*, jobject, jlong handle)
{
#if LLAMA_AVAILABLE
    // Rough estimate based on context size
    auto* mc = reinterpret_cast<ModelContext*>(handle);
    if (!mc || !mc->ctx) return 0;
    // This is approximate — llama.cpp doesn't expose exact memory usage easily
    return (int)(llama_get_kv_cache_used_cells(mc->ctx) * 4 / (1024 * 1024)); // very rough
#else
    return 0;
#endif
}

JNIEXPORT jfloat JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_getCpuTemperature(JNIEnv*, jobject)
{
    return read_cpu_temperature();
}

JNIEXPORT jint JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_getAvailableRamMb(JNIEnv*, jobject)
{
    std::ifstream f("/proc/meminfo");
    std::string line;
    while (std::getline(f, line)) {
        if (line.find("MemAvailable") != std::string::npos) {
            long kb = 0;
            sscanf(line.c_str(), "MemAvailable: %ld kB", &kb);
            return (int)(kb / 1024);
        }
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_tryptz_neuron_inference_bridge_LlamaBridge_benchmarkDevice(JNIEnv* env, jobject)
{
    // Placeholder — real benchmark would run inference loops per backend
    return env->NewStringUTF("{\"cpu_score\": 0, \"gpu_score\": 0, \"npu_score\": 0}");
}

} // extern "C"
