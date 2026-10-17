package com.tryptz.neuron.terminal

/**
 * Builds the environment for shells in the embedded userland, matching what
 * termux-app v0.119 exports (TermuxShellEnvironment + AndroidShellEnvironment +
 * TermuxAppShellEnvironment). `LD_PRELOAD` for termux-exec is intentionally
 * absent: `$PREFIX/bin/login` sets it after probing that the device allows it.
 */
object TermuxEnvironment {

    /** Android variables the runtime needs (dalvikvm, am, pm, app_process). */
    private val PASSTHROUGH = listOf(
        "ANDROID_ASSETS", "ANDROID_DATA", "ANDROID_ROOT", "ANDROID_STORAGE",
        "EXTERNAL_STORAGE", "ASEC_MOUNTPOINT", "LOOP_MOUNTPOINT",
        "ANDROID_RUNTIME_ROOT", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
        "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH"
    )

    /** termux-app version the userland's scripts compare against (login, pkg). */
    const val TERMUX_APP_VERSION = "0.119.0"

    fun build(
        paths: TermuxPaths,
        systemEnv: Map<String, String> = System.getenv(),
        cwd: String = paths.home.absolutePath,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> = buildMap {
        PASSTHROUGH.forEach { key -> systemEnv[key]?.let { put(key, it) } }
        put("HOME", paths.home.absolutePath)
        put("PREFIX", paths.prefix.absolutePath)
        put("TMPDIR", paths.tmp.absolutePath)
        put("PATH", "${paths.bin.absolutePath}:${paths.bin.absolutePath}/applets")
        put("LANG", "en_US.UTF-8")
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("PWD", cwd)
        put("SHELL", paths.bash.absolutePath)
        put("TERMUX_VERSION", TERMUX_APP_VERSION)
        put("TERMUX_APP__VERSION_NAME", TERMUX_APP_VERSION)
        put("TERMUX_APP__PACKAGE_NAME", "com.termux")
        put("TERMUX_APP__PACKAGE_MANAGER", "apt")
        put("TERMUX_APP__PACKAGE_VARIANT", "apt-android-7")
        put("TERMUX_APP__FILES_DIR", paths.filesDir.absolutePath)
        // Lets scripts (and the user) tell they are inside Neuron's terminal.
        put("NEURON_TERMINAL", "1")
        putAll(extra)
    }

    /** Environment for Android's `/system/bin/sh` when the userland is unavailable. */
    fun systemShell(
        home: java.io.File,
        tmp: java.io.File,
        systemEnv: Map<String, String> = System.getenv()
    ): Map<String, String> = buildMap {
        PASSTHROUGH.forEach { key -> systemEnv[key]?.let { put(key, it) } }
        put("HOME", home.absolutePath)
        put("TMPDIR", tmp.absolutePath)
        put("PATH", systemEnv["PATH"] ?: "/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin")
        put("LANG", "en_US.UTF-8")
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("PWD", home.absolutePath)
        put("NEURON_TERMINAL", "1")
    }

    /** `KEY=value` array in the form the terminal JNI and ProcessBuilder expect. */
    fun toEnvArray(env: Map<String, String>): Array<String> =
        env.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" }.toTypedArray()
}
