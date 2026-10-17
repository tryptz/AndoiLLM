package com.tryptz.neuron.terminal

import java.io.File

/**
 * Filesystem layout of the embedded Termux userland.
 *
 * Every Termux package is compiled with `/data/data/com.termux/files/usr` baked
 * in (ELF RUNPATH, shebangs, config paths). That is why the app's
 * applicationId is `com.termux`: it is the only way the stock packages run
 * unmodified. [filesDir] is normally exactly that path; [isCanonical] tells the
 * UI when it is not (secondary user / work profile), where packages break.
 */
data class TermuxPaths(val filesDir: File) {
    val prefix: File get() = File(filesDir, "usr")
    val staging: File get() = File(filesDir, "usr-staging")
    val home: File get() = File(filesDir, "home")
    val tmp: File get() = File(prefix, "tmp")
    val bin: File get() = File(prefix, "bin")
    val login: File get() = File(bin, "login")
    val bash: File get() = File(bin, "bash")

    /** Packages only work when the files dir is the path they were built for. */
    val isCanonical: Boolean
        get() = filesDir.absolutePath == CANONICAL_FILES_DIR ||
            filesDir.absolutePath == "/data/user/0/com.termux/files"

    /** The bootstrap counts as installed once `usr/bin/login` exists and is executable. */
    val isInstalled: Boolean get() = login.isFile && login.canExecute()

    companion object {
        const val CANONICAL_FILES_DIR = "/data/data/com.termux/files"
        const val CANONICAL_PREFIX = "$CANONICAL_FILES_DIR/usr"
    }
}
