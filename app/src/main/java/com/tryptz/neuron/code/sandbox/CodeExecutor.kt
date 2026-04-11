package com.tryptz.neuron.code.sandbox

import com.tryptz.neuron.code.engine.QuickJSBridge
import com.tryptz.neuron.domain.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class JsResult(
    val stdout: String = "",
    val stderr: String = "",
    val returnValue: String? = null,
    val executionTimeMs: Long = 0,
    val memoryUsedBytes: Long = 0,
    val error: JsError? = null
)

@Serializable
private data class JsError(
    val type: String = "RUNTIME",
    val message: String = "",
    val line: Int? = null
)

@Singleton
class CodeExecutor @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(
        code: String,
        language: CodeLanguage,
        settings: InferenceSettings
    ): CodeOutput = withContext(Dispatchers.IO) {
        when (language) {
            CodeLanguage.JAVASCRIPT -> executeJavaScript(code, settings)
            CodeLanguage.PYTHON -> executePython(code, settings)
            CodeLanguage.BASH -> executeBash(code, settings)
            CodeLanguage.HTML -> CodeOutput(htmlPreview = code)
            CodeLanguage.UNKNOWN -> CodeOutput(stderr = "Unsupported language")
        }
    }

    private suspend fun executeJavaScript(code: String, settings: InferenceSettings): CodeOutput {
        QuickJSBridge.ensureLoaded()

        val memLimit = settings.codeMemoryMb.toLong() * 1024 * 1024
        val timeoutMs = settings.codeTimeoutSec.toLong() * 1000
        var runtimeHandle = 0L

        return try {
            runtimeHandle = QuickJSBridge.createRuntime(memLimit, 1024 * 1024) // 1MB stack
            if (runtimeHandle == 0L) {
                return CodeOutput(
                    error = CodeError(ErrorType.RUNTIME, "Failed to create JS runtime")
                )
            }

            // Wrap code with console capture
            val wrappedCode = buildString {
                appendLine("var __stdout = [];")
                appendLine("var __stderr = [];")
                appendLine("var console = {")
                appendLine("  log: function() { __stdout.push(Array.from(arguments).join(' ')); },")
                appendLine("  error: function() { __stderr.push(Array.from(arguments).join(' ')); },")
                appendLine("  warn: function() { __stderr.push('WARN: ' + Array.from(arguments).join(' ')); },")
                appendLine("  info: function() { __stdout.push(Array.from(arguments).join(' ')); }")
                appendLine("};")
                appendLine("var __result;")
                appendLine("try {")
                appendLine("  __result = (function() {")
                appendLine("    $code")
                appendLine("  })();")
                appendLine("} catch(e) {")
                appendLine("  __stderr.push(e.toString());")
                appendLine("}")
                appendLine("JSON.stringify({")
                appendLine("  stdout: __stdout.join('\\n'),")
                appendLine("  stderr: __stderr.join('\\n'),")
                appendLine("  returnValue: __result !== undefined ? String(__result) : null")
                appendLine("});")
            }

            val resultJson = QuickJSBridge.evaluate(runtimeHandle, wrappedCode, timeoutMs)
            val result = json.decodeFromString<JsResult>(resultJson)

            CodeOutput(
                stdout = result.stdout,
                stderr = result.stderr,
                returnValue = result.returnValue,
                executionTimeMs = result.executionTimeMs,
                memoryUsedBytes = result.memoryUsedBytes,
                error = result.error?.let {
                    CodeError(
                        type = runCatching { ErrorType.valueOf(it.type) }.getOrDefault(ErrorType.RUNTIME),
                        message = it.message,
                        line = it.line
                    )
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "JS execution failed")
            CodeOutput(error = CodeError(ErrorType.RUNTIME, e.message ?: "Unknown error"))
        } finally {
            if (runtimeHandle != 0L) {
                QuickJSBridge.destroyRuntime(runtimeHandle)
            }
        }
    }

    private fun executePython(code: String, settings: InferenceSettings): CodeOutput {
        // Chaquopy integration — requires Chaquopy Gradle plugin.
        // For now, return a placeholder that explains the setup needed.
        return CodeOutput(
            stderr = "Python execution requires Chaquopy SDK integration. " +
                     "Add the Chaquopy Gradle plugin and configure the Python distribution.",
            error = CodeError(ErrorType.RUNTIME, "Python backend not yet configured")
        )
    }

    private fun executeBash(code: String, settings: InferenceSettings): CodeOutput {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", code)
                .redirectErrorStream(false)
                .start()

            val completed = process.waitFor(settings.codeTimeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                return CodeOutput(
                    error = CodeError(ErrorType.TIMEOUT, "Execution timed out after ${settings.codeTimeoutSec}s")
                )
            }

            val stdout = process.inputStream.bufferedReader().readText()
                .take(1024 * 1024) // 1MB output cap
            val stderr = process.errorStream.bufferedReader().readText()
                .take(1024 * 1024)

            CodeOutput(
                stdout = stdout,
                stderr = stderr,
                returnValue = if (process.exitValue() == 0) null else "exit code: ${process.exitValue()}"
            )
        } catch (e: Exception) {
            CodeOutput(error = CodeError(ErrorType.RUNTIME, e.message ?: "Shell execution failed"))
        }
    }

    fun detectLanguage(code: String): CodeLanguage {
        val trimmed = code.trim()
        return when {
            trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html") -> CodeLanguage.HTML
            trimmed.startsWith("#!/bin/bash") || trimmed.startsWith("#!/bin/sh") -> CodeLanguage.BASH
            trimmed.contains("def ") || trimmed.contains("import ") && !trimmed.contains("require(") -> CodeLanguage.PYTHON
            trimmed.contains("function ") || trimmed.contains("const ") || trimmed.contains("let ") ||
                trimmed.contains("var ") || trimmed.contains("=>") || trimmed.contains("console.") -> CodeLanguage.JAVASCRIPT
            trimmed.startsWith("grep ") || trimmed.startsWith("echo ") || trimmed.startsWith("cat ") ||
                trimmed.startsWith("ls ") || trimmed.startsWith("awk ") -> CodeLanguage.BASH
            else -> CodeLanguage.UNKNOWN
        }
    }
}
