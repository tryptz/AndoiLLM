package com.tryptz.neuron.code.engine

import timber.log.Timber

/**
 * JNI bridge to embedded QuickJS engine for sandboxed JavaScript execution.
 */
object QuickJSBridge {
    private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            try {
                System.loadLibrary("neuron_quickjs")
                loaded = true
                Timber.d("QuickJS engine loaded")
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "Failed to load QuickJS")
                throw e
            }
        }
    }

    /**
     * Create an isolated JS runtime with resource limits.
     * @return runtime handle, 0 on failure
     */
    external fun createRuntime(memoryLimitBytes: Long, maxStackSize: Long): Long

    /** Destroy a runtime and free all resources. */
    external fun destroyRuntime(handle: Long)

    /**
     * Execute JavaScript code in the given runtime.
     * @return JSON-encoded result: { stdout, stderr, returnValue, executionTimeMs, memoryUsedBytes, error? }
     */
    external fun evaluate(
        runtimeHandle: Long,
        code: String,
        timeoutMs: Long
    ): String

    /** Interrupt a running evaluation. */
    external fun interrupt(runtimeHandle: Long)
}
