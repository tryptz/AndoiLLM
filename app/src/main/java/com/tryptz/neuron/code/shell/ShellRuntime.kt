package com.tryptz.neuron.code.shell

import android.content.Context
import com.tryptz.neuron.terminal.TermuxEnvironment
import com.tryptz.neuron.terminal.TermuxPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** How a confirmed shell run from the Code Editor is launched. */
interface ShellRuntime {
    /** argv for running [code] with `-c`. */
    fun command(code: String): List<String>
    /** Environment overrides, or null to inherit the app's. */
    fun environment(): Map<String, String>?
    fun workingDirectory(): java.io.File?
}

/** Plain Android `/system/bin/sh`: what runs before the Termux userland is installed. */
object SystemShellRuntime : ShellRuntime {
    override fun command(code: String) = listOf("/system/bin/sh", "-c", code)
    override fun environment(): Map<String, String>? = null
    override fun workingDirectory(): java.io.File? = null
}

/**
 * Uses the embedded Termux userland once installed (bash, PATH with every
 * `pkg install`ed tool, termux-exec for shebangs), falling back to the system
 * shell before first-run setup.
 */
@Singleton
class TermuxShellRuntime @Inject constructor(@ApplicationContext context: Context) : ShellRuntime {
    private val paths = TermuxPaths(context.filesDir)

    private val active: Boolean get() = paths.isInstalled && paths.bash.canExecute()

    override fun command(code: String): List<String> =
        if (active) listOf(paths.bash.absolutePath, "-c", code) else SystemShellRuntime.command(code)

    override fun environment(): Map<String, String>? {
        if (!active) return null
        val env = TermuxEnvironment.build(paths)
        // Not a login shell, so set termux-exec's preload here (login does it for terminals).
        val preload = java.io.File(paths.prefix, "lib/libtermux-exec-ld-preload.so")
        return if (preload.isFile) env + ("LD_PRELOAD" to preload.absolutePath) else env
    }

    override fun workingDirectory(): java.io.File? = if (active) paths.home else null
}
