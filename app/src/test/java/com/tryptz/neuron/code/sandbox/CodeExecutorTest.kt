package com.tryptz.neuron.code.sandbox

import com.tryptz.neuron.domain.model.CodeLanguage
import com.tryptz.neuron.domain.model.ErrorType
import com.tryptz.neuron.domain.model.InferenceSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Security regression tests for the bash-RCE fix:
 *  - detectLanguage must NEVER classify code as BASH.
 *  - execute(BASH, confirmed=false) must be refused (the chat path).
 */
class CodeExecutorTest {

    private val executor: CodeExecutor = CodeExecutorImpl()
    private val settings = InferenceSettings()

    // ── detectLanguage never returns BASH ──

    @Test
    fun `detectLanguage never returns BASH for shebang`() {
        assertNotEquals(CodeLanguage.BASH, executor.detectLanguage("#!/bin/bash\necho hi"))
        assertNotEquals(CodeLanguage.BASH, executor.detectLanguage("#!/bin/sh\nls /"))
    }

    @Test
    fun `detectLanguage never returns BASH for shell commands`() {
        listOf(
            "echo hello world",
            "ls -la /sdcard",
            "cat /etc/passwd",
            "grep -r secret /",
            "awk '{print \$1}' file"
        ).forEach { cmd ->
            assertNotEquals(
                CodeLanguage.BASH,
                executor.detectLanguage(cmd),
                "detectLanguage must not classify '$cmd' as BASH"
            )
        }
    }

    @Test
    fun `detectLanguage still detects other languages`() {
        assertEquals(CodeLanguage.HTML, executor.detectLanguage("<!DOCTYPE html><html></html>"))
        assertEquals(CodeLanguage.JAVASCRIPT, executor.detectLanguage("const x = () => 1;"))
        assertEquals(CodeLanguage.PYTHON, executor.detectLanguage("def main():\n  pass"))
    }

    @Test
    fun `detectLanguage falls back to UNKNOWN for plain shell output`() {
        assertEquals(CodeLanguage.UNKNOWN, executor.detectLanguage("echo hello"))
    }

    // ── execute(BASH) gating ──

    @Test
    fun `execute BASH without confirmation is refused`() = runTest {
        val output = executor.execute("echo pwned", CodeLanguage.BASH, settings)
        assertNotNull(output.error, "Unconfirmed bash must produce an error")
        assertEquals(ErrorType.SECURITY, output.error?.type)
        assertTrue(output.stdout.isEmpty(), "Unconfirmed bash must not produce stdout")
    }

    @Test
    fun `execute BASH with confirmed false explicitly is refused`() = runTest {
        val output = executor.execute("echo pwned", CodeLanguage.BASH, settings, confirmed = false)
        assertEquals(ErrorType.SECURITY, output.error?.type)
    }

    @Test
    fun `execute HTML ignores confirmed flag`() = runTest {
        val output = executor.execute("<html></html>", CodeLanguage.HTML, settings, confirmed = false)
        assertEquals("<html></html>", output.htmlPreview)
        assertEquals(null, output.error)
    }
}
