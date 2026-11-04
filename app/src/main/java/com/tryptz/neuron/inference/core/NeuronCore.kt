package com.tryptz.neuron.inference.core

import android.content.Context
import com.tryptz.neuron.BuildConfig
import com.tryptz.neuron.core.EngineConfig
import com.tryptz.neuron.core.NeuronEngine
import com.tryptz.neuron.core.SocInfo
import com.tryptz.neuron.core.coreVersion
import com.tryptz.neuron.core.detectSoc
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single [NeuronEngine] instance (the Rust core behind libneuron.so).
 *
 * Construction is lazy and happens off the main thread on first use: it
 * dlopen()s every ggml backend module in the app's native library directory
 * (CPU variants, `libggml-opencl.so` for the Adreno GPU, `libggml-hexagon.so`
 * for the NPU) and enumerates the devices they expose. A module whose driver is
 * missing on this phone is skipped by ggml, so the app degrades instead of
 * crashing.
 */
@Singleton
class NeuronCore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile
    private var engine: NeuronEngine? = null

    /** SoC identity from system properties; cheap, no native init needed. */
    val soc: SocInfo by lazy { detectSoc() }

    fun engine(): NeuronEngine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val skelDir = HexagonAssets.stage(context, soc)
            val config = EngineConfig(
                backendsDir = context.applicationInfo.nativeLibraryDir,
                hexagonSkelDir = skelDir?.absolutePath,
                hexagonPowerMode = null,
                verboseLogging = BuildConfig.DEBUG
            )
            Timber.i(
                "[op=core_init] version=${coreVersion()} soc=${soc.model} " +
                    "htp=${soc.htpArch} skelDir=${skelDir?.absolutePath}"
            )
            val created = NeuronEngine(config)
            created.devices().forEach {
                Timber.i("[op=ggml_device] name=${it.name} backend=${it.backend} desc=\"${it.description}\"")
            }
            engine = created
            return created
        }
    }
}

/**
 * The Hexagon HTP skeleton (`libggml-htp-vNN.so`) is a DSP-side ELF object, so
 * it travels as an asset and is copied to `filesDir/htp` once. FastRPC resolves
 * the backend's `file:///libggml-htp-vNN.so` URI against that directory.
 */
internal object HexagonAssets {
    private const val ASSET_DIR = "htp"

    fun stage(context: Context, soc: SocInfo): File? {
        val wanted = soc.htpSkelFilename ?: return null
        val names = runCatching { context.assets.list(ASSET_DIR)?.toList() }.getOrNull().orEmpty()
        if (wanted !in names) {
            Timber.w("[op=htp_skel_missing] wanted=$wanted available=$names")
            return null
        }
        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val target = File(dir, wanted)
        val assetSize = runCatching {
            context.assets.openFd("$ASSET_DIR/$wanted").use { it.length }
        }.getOrDefault(-1L)
        if (!target.exists() || target.length() != assetSize) {
            context.assets.open("$ASSET_DIR/$wanted").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            Timber.i("[op=htp_skel_staged] file=${target.absolutePath} bytes=${target.length()}")
        }
        return dir
    }
}
