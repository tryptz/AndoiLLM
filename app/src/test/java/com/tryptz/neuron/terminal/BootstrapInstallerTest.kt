package com.tryptz.neuron.terminal

import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootstrapInstallerTest {

    @Test
    fun `parses SYMLINKS lines`() {
        val (target, link) = BootstrapInstaller.parseSymlinkLine(
            "/data/data/com.termux/files/usr/share/termux-keyring/grimler.gpg←./etc/apt/trusted.gpg.d/grimler.gpg"
        )
        assertEquals("/data/data/com.termux/files/usr/share/termux-keyring/grimler.gpg", target)
        assertEquals("./etc/apt/trusted.gpg.d/grimler.gpg", link)
    }

    @Test
    fun `rejects malformed SYMLINKS lines`() {
        assertFailsWith<IllegalArgumentException> { BootstrapInstaller.parseSymlinkLine("no-arrow-here") }
        assertFailsWith<IllegalArgumentException> { BootstrapInstaller.parseSymlinkLine("a←b←c") }
        assertFailsWith<IllegalArgumentException> { BootstrapInstaller.parseSymlinkLine("←b") }
    }

    @Test
    fun `exec bit matches TermuxInstaller rules`() {
        listOf("bin/bash", "bin/apt", "libexec/coreutils/libstdbuf.so", "lib/apt/apt-helper", "lib/apt/methods/http")
            .forEach { assertTrue(BootstrapInstaller.needsExecBit(it), it) }
        listOf("etc/motd", "lib/libc++_shared.so", "share/doc/README", "lib/apt/other")
            .forEach { assertFalse(BootstrapInstaller.needsExecBit(it), it) }
    }

    @Test
    fun `zip entries cannot escape the prefix`() {
        val root = createTempDir()
        try {
            assertEquals(File(root, "bin/bash"), BootstrapInstaller.safeChild(root, "bin/bash"))
            assertFailsWith<IOException> { BootstrapInstaller.safeChild(root, "../evil") }
            assertFailsWith<IOException> { BootstrapInstaller.safeChild(root, "bin/../../evil") }
        } finally {
            root.deleteRecursively()
        }
    }
}
