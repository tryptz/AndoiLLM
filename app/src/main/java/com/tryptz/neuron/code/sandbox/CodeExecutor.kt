package com.tryptz.neuron.code.sandbox

import com.tryptz.neuron.core.JsErrorKind
import com.tryptz.neuron.core.JsLimits
import com.tryptz.neuron.core.runJavascript
import com.tryptz.neuron.domain.model.*
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public contract for the sandboxed code-execution engine.
 * Consumers depend on this interface; the concrete implementation is
 * [CodeExecutorImpl], bound in InferenceModule.
 *
 * SECURITY: Bash execution pipes code straight to /system/bin/sh. It is therefore
 * gated: [execute] runs a BASH block only when [confirmed] is true, which the
 * Code Editor screen sets exclusively after an explicit per-run user confirmation.
 * The chat path (ExecuteCodeUseCase) never passes confirmed=true, and
 * [detectLanguage] never classifies code as BASH — so bash can never run
 * implicitly from an LLM-generated chat code block.
 */
interface CodeExecutor {
    /**
     * @param confirmed must be true to run [CodeLanguage.BASH]; ignored for all
     *   other languages. Defaults to false so existing callers refuse bash safely.
     */
    suspend fun execute(
        code: String,
        language: CodeLanguage,
        settings: InferenceSettings,
        confirmed: Boolean = false
    ): CodeOutput

    /** Best-effort language detection. NEVER returns [CodeLanguage.BASH]. */
    fun detectLanguage(code: String): CodeLanguage
}

@Singleton
class CodeExecutorImpl @Inject constructor() : CodeExecutor {


    override suspend fun execute(
        code: String,
        language: CodeLanguage,
        settings: InferenceSettings,
        confirmed: Boolean
    ): CodeOutput = withContext(Dispatchers.IO) {
        when (language) {
            CodeLanguage.JAVASCRIPT -> executeJavaScript(code, settings)
            CodeLanguage.PYTHON -> executePython(code, settings)
            CodeLanguage.BASH -> {
                if (!confirmed) {
                    Timber.w("Refused unconfirmed bash execution")
                    CodeOutput(
                        error = CodeError(
                            ErrorType.SECURITY,
                            "Shell execution must be explicitly confirmed by the user " +
                                "from the Code Editor. It cannot run from chat."
                        )
                    )
                } else {
                    executeBash(code, settings)
                }
            }
            CodeLanguage.HTML -> CodeOutput(htmlPreview = code)
            CodeLanguage.UNKNOWN -> CodeOutput(stderr = "Unsupported language")
        }
    }

    /**
     * JavaScript runs in the Rust-side QuickJS sandbox (`neuron-sandbox`): a
     * fresh runtime per call with a hard memory ceiling, a wall-clock deadline
     * enforced through the interpreter's interrupt hook, no `std`/`os` modules
     * linked in, and `console.*` captured into stdout/stderr.
     */
    private fun executeJavaScript(code: String, settings: InferenceSettings): CodeOutput {
        val limits = JsLimits(
            memoryLimitBytes = settings.codeMemoryMb.toULong() * 1024uL * 1024uL,
            timeoutMs = settings.codeTimeoutSec.toULong() * 1000uL
        )
        return try {
            val r = runJavascript(code, limits)
            CodeOutput(
                stdout = r.stdout,
                stderr = r.stderr,
                returnValue = r.returnValue,
                executionTimeMs = r.executionTimeMs.toLong(),
                memoryUsedBytes = r.memoryUsedBytes.toLong(),
                error = r.error?.let { e ->
                    CodeError(type = e.kind.toDomain(), message = e.message, line = e.line?.toInt())
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "JS execution failed")
            CodeOutput(error = CodeError(ErrorType.RUNTIME, e.message ?: "Unknown error"))
        }
    }

    private fun JsErrorKind.toDomain(): ErrorType = when (this) {
        JsErrorKind.SYNTAX -> ErrorType.SYNTAX
        JsErrorKind.RUNTIME -> ErrorType.RUNTIME
        JsErrorKind.TIMEOUT -> ErrorType.TIMEOUT
        JsErrorKind.MEMORY -> ErrorType.MEMORY
        JsErrorKind.SECURITY -> ErrorType.SECURITY
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

    /**
     * Runs a shell command. SECURITY-SENSITIVE: only reachable from [execute] when
     * the caller passed confirmed=true (Code Editor, post user confirmation).
     */
    private fun executeBash(code: String, settings: InferenceSettings): CodeOutput {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", code)
                .redirectErrorStream(false)
                .start()

            // Drain stdout/stderr WHILE waiting: a child that writes more than
            // the ~64KB pipe buffer blocks forever if we only read after
            // waitFor, turning every chatty command into a bogus timeout.
            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()
            val outDrainer = drainAsync(process.inputStream, stdoutBuf)
            val errDrainer = drainAsync(process.errorStream, stderrBuf)

            val completed = process.waitFor(settings.codeTimeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                outDrainer.join(1000)
                errDrainer.join(1000)
                return CodeOutput(
                    stdout = stdoutBuf.toString(),
                    stderr = stderrBuf.toString(),
                    error = CodeError(ErrorType.TIMEOUT, "Execution timed out after ${settings.codeTimeoutSec}s")
                )
            }

            outDrainer.join(5000)
            errDrainer.join(5000)

            CodeOutput(
                stdout = stdoutBuf.toString(),
                stderr = stderrBuf.toString(),
                returnValue = if (process.exitValue() == 0) null else "exit code: ${process.exitValue()}"
            )
        } catch (e: Exception) {
            CodeOutput(error = CodeError(ErrorType.RUNTIME, e.message ?: "Shell execution failed"))
        }
    }

    /** Reads [stream] to exhaustion on a daemon thread, keeping at most [MAX_OUTPUT_CHARS] chars. */
    private fun drainAsync(stream: java.io.InputStream, into: StringBuilder): Thread =
        Thread {
            try {
                stream.bufferedReader().use { reader ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = reader.read(buf)
                        if (n == -1) break
                        synchronized(into) {
                            val room = MAX_OUTPUT_CHARS - into.length
                            if (room > 0) into.append(buf, 0, minOf(n, room))
                        }
                        // Keep consuming past the cap so the child never blocks on a full pipe.
                    }
                }
            } catch (_: Exception) {
                // Stream closed by destroyForcibly — nothing to do.
            }
        }.apply { isDaemon = true; start() }

    /**
     * Heuristic language detection. Deliberately NEVER returns [CodeLanguage.BASH]:
     * shell code must be selected explicitly in the Code Editor, never auto-detected,
     * to keep LLM-generated chat output from being silently classified as runnable shell.
     */
    override fun detectLanguage(code: String): CodeLanguage {
        val trimmed = code.trim()
        return when {
            trimmed.startsWith("<!doctype", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true) -> CodeLanguage.HTML
            // JS indicators FIRST: ES-module `import` / code containing "def"
            // substrings would otherwise be misrouted to the Python backend.
            trimmed.contains("function ") || trimmed.contains("const ") || trimmed.contains("let ") ||
                trimmed.contains("var ") || trimmed.contains("=>") || trimmed.contains("console.") ||
                trimmed.contains("require(") -> CodeLanguage.JAVASCRIPT
            PYTHON_DEF.containsMatchIn(trimmed) || PYTHON_IMPORT.containsMatchIn(trimmed) -> CodeLanguage.PYTHON
            else -> CodeLanguage.UNKNOWN
        }
    }

    private companion object {
        /** Python-specific shapes, anchored per line so JS `import {…} from '…'` can't match. */
        val PYTHON_DEF = Regex("(?m)^\\s*def\\s+\\w+\\s*\\(.*\\)\\s*:")
        val PYTHON_IMPORT = Regex("(?m)^\\s*(import\\s+\\w+|from\\s+\\w+(\\.\\w+)*\\s+import\\s)")

        /** 1MB cap on captured shell output. */
        const val MAX_OUTPUT_CHARS = 1024 * 1024
    }
}
