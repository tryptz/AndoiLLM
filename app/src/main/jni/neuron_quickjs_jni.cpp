#include <jni.h>
#include <android/log.h>
#include <string>
#include <chrono>
#include <atomic>
#include <thread>

#define TAG "NeuronQuickJS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if QUICKJS_AVAILABLE
#include "quickjs.h"
#include "quickjs-libc.h"
#endif

struct RuntimeContext {
#if QUICKJS_AVAILABLE
    JSRuntime* rt = nullptr;
    JSContext* ctx = nullptr;
#endif
    std::atomic<bool> interrupted{false};
};

#if QUICKJS_AVAILABLE
static int interrupt_handler(JSRuntime* rt, void* opaque) {
    auto* rc = static_cast<RuntimeContext*>(opaque);
    return rc->interrupted.load() ? 1 : 0;
}
#endif

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tryptz_neuron_code_engine_QuickJSBridge_createRuntime(
    JNIEnv*, jobject, jlong memoryLimitBytes, jlong maxStackSize)
{
#if QUICKJS_AVAILABLE
    auto* rc = new RuntimeContext();
    rc->rt = JS_NewRuntime();
    if (!rc->rt) {
        delete rc;
        return 0;
    }

    JS_SetMemoryLimit(rc->rt, memoryLimitBytes);
    JS_SetMaxStackSize(rc->rt, maxStackSize);
    JS_SetInterruptHandler(rc->rt, interrupt_handler, rc);

    rc->ctx = JS_NewContext(rc->rt);
    if (!rc->ctx) {
        JS_FreeRuntime(rc->rt);
        delete rc;
        return 0;
    }

    // Disable dangerous builtins for sandboxing
    // (In production, use a custom class list that excludes os, std, etc.)

    LOGI("QuickJS runtime created, memory limit: %lld bytes", (long long)memoryLimitBytes);
    return reinterpret_cast<jlong>(rc);
#else
    LOGE("QuickJS not available");
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_tryptz_neuron_code_engine_QuickJSBridge_destroyRuntime(
    JNIEnv*, jobject, jlong handle)
{
#if QUICKJS_AVAILABLE
    auto* rc = reinterpret_cast<RuntimeContext*>(handle);
    if (!rc) return;
    if (rc->ctx) JS_FreeContext(rc->ctx);
    if (rc->rt) JS_FreeRuntime(rc->rt);
    delete rc;
    LOGI("QuickJS runtime destroyed");
#endif
}

JNIEXPORT jstring JNICALL
Java_com_tryptz_neuron_code_engine_QuickJSBridge_evaluate(
    JNIEnv* env, jobject, jlong handle, jstring code, jlong timeoutMs)
{
#if QUICKJS_AVAILABLE
    auto* rc = reinterpret_cast<RuntimeContext*>(handle);
    if (!rc || !rc->ctx) {
        return env->NewStringUTF("{\"stderr\":\"Runtime not initialized\",\"error\":{\"type\":\"RUNTIME\",\"message\":\"No runtime\"}}");
    }

    const char* codeStr = env->GetStringUTFChars(code, nullptr);
    rc->interrupted.store(false);

    auto t_start = std::chrono::high_resolution_clock::now();

    // Set up a timeout thread
    std::thread timeout_thread([rc, timeoutMs]() {
        std::this_thread::sleep_for(std::chrono::milliseconds(timeoutMs));
        rc->interrupted.store(true);
    });
    timeout_thread.detach();

    JSValue result = JS_Eval(rc->ctx, codeStr, strlen(codeStr), "<input>", JS_EVAL_TYPE_GLOBAL);
    env->ReleaseStringUTFChars(code, codeStr);

    auto t_end = std::chrono::high_resolution_clock::now();
    long elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_start).count();

    // Get memory usage
    JSMemoryUsage mem_usage;
    JS_ComputeMemoryUsage(rc->rt, &mem_usage);

    std::string json_result;

    if (JS_IsException(result)) {
        JSValue exc = JS_GetException(rc->ctx);
        const char* err_str = JS_ToCString(rc->ctx, exc);
        std::string err_msg = err_str ? err_str : "Unknown error";
        JS_FreeCString(rc->ctx, err_str);
        JS_FreeValue(rc->ctx, exc);

        std::string error_type = rc->interrupted.load() ? "TIMEOUT" : "RUNTIME";

        json_result = "{\"stdout\":\"\",\"stderr\":\"" + err_msg + "\","
                      "\"executionTimeMs\":" + std::to_string(elapsed_ms) + ","
                      "\"memoryUsedBytes\":" + std::to_string(mem_usage.malloc_size) + ","
                      "\"error\":{\"type\":\"" + error_type + "\",\"message\":\"" + err_msg + "\"}}";
    } else {
        const char* result_str = JS_ToCString(rc->ctx, result);
        std::string result_text = result_str ? result_str : "";
        JS_FreeCString(rc->ctx, result_str);

        // The code wraps itself in JSON.stringify, so result_text should be JSON
        // We need to parse it and inject timing info
        // For simplicity, if it's valid JSON already, inject the extra fields
        if (!result_text.empty() && result_text[0] == '{') {
            // Inject executionTimeMs and memoryUsedBytes before the closing brace
            size_t last_brace = result_text.rfind('}');
            if (last_brace != std::string::npos) {
                json_result = result_text.substr(0, last_brace) +
                              ",\"executionTimeMs\":" + std::to_string(elapsed_ms) +
                              ",\"memoryUsedBytes\":" + std::to_string(mem_usage.malloc_size) +
                              "}";
            } else {
                json_result = result_text;
            }
        } else {
            json_result = "{\"stdout\":\"\",\"returnValue\":\"" + result_text + "\","
                          "\"executionTimeMs\":" + std::to_string(elapsed_ms) + ","
                          "\"memoryUsedBytes\":" + std::to_string(mem_usage.malloc_size) + "}";
        }
    }

    JS_FreeValue(rc->ctx, result);

    return env->NewStringUTF(json_result.c_str());
#else
    return env->NewStringUTF("{\"stderr\":\"QuickJS not available\",\"error\":{\"type\":\"RUNTIME\",\"message\":\"QuickJS not compiled\"}}");
#endif
}

JNIEXPORT void JNICALL
Java_com_tryptz_neuron_code_engine_QuickJSBridge_interrupt(
    JNIEnv*, jobject, jlong handle)
{
#if QUICKJS_AVAILABLE
    auto* rc = reinterpret_cast<RuntimeContext*>(handle);
    if (rc) rc->interrupted.store(true);
#endif
}

} // extern "C"
