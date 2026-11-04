package com.tryptz.neuron.terminal

import android.system.Os
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Progress of a bootstrap install, for the first-run screen. */
sealed interface BootstrapProgress {
    data class Extracting(val entries: Int, val currentPath: String) : BootstrapProgress
    data object Linking : BootstrapProgress
    data object Done : BootstrapProgress
}

/**
 * Installs the Termux bootstrap (a zip of `$PREFIX` plus `SYMLINKS.txt`) the
 * same way termux-app's TermuxInstaller does: extract into a staging dir,
 * create the symlinks, mark executables 0700, then atomically rename the
 * staging dir to `usr`. A failure at any point leaves no half-installed
 * prefix behind, so "retry" is always safe — the classic Termux first-run
 * dead end (a broken `usr/` that is never repaired) cannot happen.
 */
class BootstrapInstaller(private val paths: TermuxPaths) {

    fun install(zip: InputStream, onProgress: (BootstrapProgress) -> Unit = {}) {
        paths.staging.deleteRecursively()
        paths.staging.mkdirs()
        val symlinks = mutableListOf<Pair<String, File>>()
        var entries = 0
        val buffer = ByteArray(64 * 1024)
        try {
            ZipInputStream(zip.buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name
                    if (name == SYMLINKS_FILE) {
                        input.bufferedReader().lineSequence().filter { it.isNotBlank() }.forEach { line ->
                            val (target, link) = parseSymlinkLine(line)
                            val linkFile = safeChild(paths.staging, link)
                            linkFile.parentFile?.mkdirs()
                            symlinks += target to linkFile
                        }
                        continue
                    }
                    val out = safeChild(paths.staging, name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                o.write(buffer, 0, n)
                            }
                        }
                        if (needsExecBit(name)) Os.chmod(out.absolutePath, "700".toInt(8))
                    }
                    entries++
                    if (entries % 50 == 0) onProgress(BootstrapProgress.Extracting(entries, name))
                }
            }
            if (symlinks.isEmpty()) throw IOException("Bootstrap has no $SYMLINKS_FILE")
            onProgress(BootstrapProgress.Linking)
            symlinks.forEach { (target, link) -> Os.symlink(target, link.absolutePath) }

            paths.prefix.deleteRecursively()
            if (!paths.staging.renameTo(paths.prefix)) {
                throw IOException("Could not move ${paths.staging} to ${paths.prefix}")
            }
            paths.home.mkdirs()
            paths.tmp.mkdirs()
            onProgress(BootstrapProgress.Done)
        } catch (e: Exception) {
            paths.staging.deleteRecursively()
            throw e
        }
    }

    companion object {
        const val SYMLINKS_FILE = "SYMLINKS.txt"

        /** `SYMLINKS.txt` lines are `<target>←<link path relative to $PREFIX>`. */
        fun parseSymlinkLine(line: String): Pair<String, String> {
            val parts = line.split('←')
            require(parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                "Malformed symlink line: $line"
            }
            return parts[0] to parts[1]
        }

        /** Same rule as TermuxInstaller: binaries, libexec and apt's helpers. */
        fun needsExecBit(entry: String): Boolean =
            entry.startsWith("bin/") || entry.startsWith("libexec") ||
                entry.startsWith("lib/apt/apt-helper") || entry.startsWith("lib/apt/methods")

        /** Rejects `../` entries so a malformed zip cannot write outside the prefix. */
        fun safeChild(root: File, relative: String): File {
            val f = File(root, relative).canonicalFile
            val r = root.canonicalFile
            if (f != r && !f.path.startsWith(r.path + File.separator)) {
                throw IOException("Bootstrap entry escapes prefix: $relative")
            }
            return File(root, relative)
        }
    }
}
