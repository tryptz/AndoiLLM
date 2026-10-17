package com.tryptz.neuron.terminal

import com.tryptz.neuron.domain.model.CodeBlock
import com.tryptz.neuron.domain.model.CodeLanguage
import com.tryptz.neuron.ui.chat.terminalInputFor
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermuxEnvironmentTest {

    private val paths = TermuxPaths(File(TermuxPaths.CANONICAL_FILES_DIR))

    @Test
    fun `canonical paths match the Termux layout`() {
        assertTrue(paths.isCanonical)
        assertEquals(TermuxPaths.CANONICAL_PREFIX, paths.prefix.path)
        assertEquals("/data/data/com.termux/files/usr/bin/login", paths.login.path)
        assertFalse(TermuxPaths(File("/data/data/com.tryptz.neuron/files")).isCanonical)
    }

    @Test
    fun `userland environment mirrors termux-app`() {
        val env = TermuxEnvironment.build(paths, systemEnv = mapOf("ANDROID_ROOT" to "/system", "SECRET" to "x"))
        assertEquals("/data/data/com.termux/files/home", env["HOME"])
        assertEquals("/data/data/com.termux/files/usr", env["PREFIX"])
        assertEquals("/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets", env["PATH"])
        assertEquals("/data/data/com.termux/files/usr/tmp", env["TMPDIR"])
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("apt", env["TERMUX_APP__PACKAGE_MANAGER"])
        assertEquals(TermuxEnvironment.TERMUX_APP_VERSION, env["TERMUX_VERSION"])
        assertEquals("/system", env["ANDROID_ROOT"])
        // Only the Android allowlist passes through, and login (not us) sets LD_PRELOAD.
        assertFalse("SECRET" in env)
        assertFalse("LD_PRELOAD" in env)
    }

    @Test
    fun `system shell environment keeps Android PATH`() {
        val env = TermuxEnvironment.systemShell(File("/h"), File("/t"), systemEnv = mapOf("PATH" to "/system/bin"))
        assertEquals("/h", env["HOME"])
        assertEquals("/t", env["TMPDIR"])
        assertEquals("/system/bin", env["PATH"])
        assertFalse("PREFIX" in env)
    }

    @Test
    fun `env array is KEY=value and sorted`() {
        val arr = TermuxEnvironment.toEnvArray(mapOf("B" to "2", "A" to "1"))
        assertEquals(listOf("A=1", "B=2"), arr.toList())
    }

    @Test
    fun `transcript tail keeps the last lines within the char budget`() {
        val text = (1..200).joinToString("\n") { "line $it" } + "\n\n"
        val tail = TerminalSessionManager.tail(text, maxLines = 3, maxChars = 1000)
        assertEquals("line 198\nline 199\nline 200", tail)
        assertEquals(5, TerminalSessionManager.tail(text, 80, 5).length)
    }

    @Test
    fun `chat hands python and shell blocks over as typed input`() {
        assertEquals(TerminalInput("print('hi')\n", python = true), terminalInputFor(CodeBlock("1", CodeLanguage.PYTHON, "print('hi')\n")))
        assertEquals(TerminalInput("ls -la", python = false), terminalInputFor(CodeBlock("2", CodeLanguage.BASH, "ls -la")))
    }

    @Test
    fun `single-line shell is typed verbatim`() {
        val dir = createTempDir()
        try {
            assertEquals("ls -la", TerminalInputStager.commandFor(TerminalInput("ls -la\n", false), dir, shellIsBash = true))
            assertEquals(0, dir.listFiles()?.size ?: 0)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `multi-line shell and python become one typed command with no newline`() {
        val dir = createTempDir()
        try {
            val sh = TerminalInputStager.commandFor(TerminalInput("cd /tmp\nls", false), dir, shellIsBash = false)
            assertEquals("sh ${File(dir, "1.sh").absolutePath}", sh)
            assertEquals("cd /tmp\nls\n", File(dir, "1.sh").readText())
            val bash = TerminalInputStager.commandFor(TerminalInput("a\nb", false), dir, shellIsBash = true)
            assertTrue(bash.startsWith("bash ") && bash.endsWith("2.sh"))
            val py = TerminalInputStager.commandFor(TerminalInput("print(1)", true), dir, shellIsBash = true)
            assertEquals("python3 ${File(dir, "3.py").absolutePath}", py)
            listOf(sh, bash, py).forEach { assertFalse('\n' in it, "typed line must not contain Enter: $it") }
        } finally { dir.deleteRecursively() }
    }
}
