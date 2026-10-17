import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// ───────────────────────── Rust core (libneuron) ─────────────────────────
// The inference engine, backend selection, Hexagon/OpenCL plumbing and the JS
// sandbox live in ../core (Rust). Gradle drives `cargo ndk`, collects the ggml
// backend modules it produces, and generates the Kotlin bindings with UniFFI.
val rustDir: File = rootProject.file("core")
val rustTriple = "aarch64-linux-android"
val rustProfile = if (project.hasProperty("rustDebug")) "debug" else "release"
val rustTargetDir: File = rustDir.resolve("target/$rustTriple/$rustProfile")
val rustJniLibs = layout.buildDirectory.dir("rustJniLibs")
val rustAssets = layout.buildDirectory.dir("rustAssets")
val uniffiKotlinOut = layout.buildDirectory.dir("generated/source/uniffi/main")
val androidApiLevel = 28

// `-PneuronPrebuiltLibs=<dir>` packages a core built elsewhere instead of running
// cargo — typically the output of core/scripts/build-android.sh inside the
// Snapdragon toolchain image, the only place the Hexagon NPU backend can be
// built. The directory holds arm64-v8a/*.so and, optionally, htp/*.so.
val prebuiltLibs: File? = (findProperty("neuronPrebuiltLibs") as String?)?.let { rootProject.file(it) }
prebuiltLibs?.let {
    require(it.resolve("arm64-v8a/libneuron.so").isFile) { "neuronPrebuiltLibs: $it/arm64-v8a/libneuron.so not found" }
    logger.lifecycle("Neuron: packaging prebuilt core from $it")
}

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compiles libneuron.so and the ggml backend modules for arm64-v8a"
    onlyIf { prebuiltLibs == null }
    workingDir = rustDir
    inputs.files(rustDir.resolve("Cargo.toml"), rustDir.resolve("Cargo.lock"))
    inputs.dir(rustDir.resolve("crates"))
    inputs.dir(rustDir.resolve("cmake"))
    outputs.dir(rustJniLibs)

    val args = mutableListOf(
        "ndk", "-t", "arm64-v8a", "-P", androidApiLevel.toString(), "--link-libcxx-shared",
        "-o", rustJniLibs.get().asFile.absolutePath,
        "build", "-p", "neuron-ffi"
    )
    if (rustProfile == "release") args += "--release"
    commandLine(listOf("cargo") + args)

    val ndk = android.ndkDirectory.absolutePath
    environment("ANDROID_NDK_ROOT", ndk)
    environment("ANDROID_NDK_HOME", ndk)
    environment("ANDROID_PLATFORM", "android-$androidApiLevel")
    // Bridges HEXAGON_SDK_ROOT and OPENCL_SDK_ROOT into llama.cpp's CMake cache.
    environment("CMAKE_PROJECT_INCLUDE", rustDir.resolve("cmake/snapdragon.cmake").absolutePath)

    val openclSdk = System.getenv("OPENCL_SDK_ROOT")?.let(::File)
        ?: rustDir.resolve("third_party/opencl-android")
    if (openclSdk.resolve("lib/libOpenCL.so").exists()) {
        environment("GGML_OPENCL", "ON")
        environment("OPENCL_SDK_ROOT", openclSdk.absolutePath)
    } else if (prebuiltLibs == null) {
        logger.warn("Neuron: Adreno OpenCL backend OFF — run core/scripts/setup-opencl-android.sh")
    }
    val hexagonSdk = System.getenv("HEXAGON_SDK_ROOT")
    if (hexagonSdk != null && File(hexagonSdk).isDirectory) {
        environment("GGML_HEXAGON", "ON")
        environment("HEXAGON_SDK_ROOT", hexagonSdk)
    } else if (prebuiltLibs == null) {
        logger.warn("Neuron: Hexagon NPU backend OFF — HEXAGON_SDK_ROOT not set; see README \"Hexagon NPU backend\"")
    }
}

/** libllama / libggml* / libggml-<backend>.so collected by neuron-core's build script. */
val collectRustLibs by tasks.registering(Copy::class) {
    group = "rust"
    dependsOn(cargoNdkBuild)
    if (prebuiltLibs != null) {
        from(prebuiltLibs.resolve("arm64-v8a")) { include("*.so"); exclude("libggml-htp-*") }
    } else {
        from(rustTargetDir.resolve("neuron-libs")) { include("*.so") }
    }
    into(rustJniLibs.map { it.dir("arm64-v8a") })
}

/** Hexagon DSP skels (libggml-htp-vNN.so) travel as assets; see NeuronCore.kt. */
val collectHtpSkels by tasks.registering(Sync::class) {
    group = "rust"
    dependsOn(cargoNdkBuild)
    from(prebuiltLibs?.resolve("htp") ?: rustTargetDir.resolve("neuron-libs/htp")) { include("libggml-htp-v*.so") }
    into(rustAssets.map { it.dir("htp") })
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generates Kotlin bindings (com.tryptz.neuron.core) from libneuron.so"
    dependsOn(cargoNdkBuild)
    workingDir = rustDir
    val library = prebuiltLibs?.resolve("arm64-v8a/libneuron.so") ?: rustTargetDir.resolve("libneuron.so")
    inputs.file(library)
    // Library mode picks up crates/neuron-ffi/uniffi.toml through cargo metadata.
    inputs.file(rustDir.resolve("crates/neuron-ffi/uniffi.toml"))
    outputs.dir(uniffiKotlinOut)
    commandLine(
        "cargo", "run", "-q", "-p", "uniffi-bindgen", "--release", "--",
        "generate", "--library", library.absolutePath,
        "--language", "kotlin",
        "--no-format",
        "--out-dir", uniffiKotlinOut.get().asFile.absolutePath
    )
}

// ───────────────────────── Termux userland (bootstrap) ─────────────────────────
// The Termux apt bootstrap (bash, coreutils, apt/pkg, termux-exec) is built for
// /data/data/com.termux/files/usr, so it is only bundled when the app is
// installed under that ID. Pinned to the release termux-app master uses and
// verified by SHA-256; `-PtermuxBootstrap=<zip>` uses a local file instead (for
// example one made by termux-packages' scripts/generate-bootstraps.sh).
val termuxBootstrapVersion = "2026.02.12-r1+apt.android-7"
val termuxBootstrapSha256 = "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"
val termuxAssets = layout.buildDirectory.dir("termuxAssets")

fun sha256Of(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val prepareTermuxBootstrap by tasks.registering {
    group = "termux"
    description = "Bundles bootstrap-aarch64.zip into the APK assets (com.termux installs only)"
    val local = (findProperty("termuxBootstrap") as String?)?.let { rootProject.file(it) }
    val out = termuxAssets.map { it.file("termux/bootstrap-aarch64.zip") }
    inputs.property("version", termuxBootstrapVersion)
    local?.let { inputs.file(it) }
    outputs.dir(termuxAssets)
    onlyIf {
        val id = android.defaultConfig.applicationId
        (id == "com.termux").also { bundled ->
            if (!bundled) logger.lifecycle("Neuron: Termux userland not bundled (applicationId is $id, packages need com.termux)")
        }
    }
    doLast {
        val dest = out.get().asFile
        dest.parentFile.mkdirs()
        if (local != null) {
            require(local.isFile) { "termuxBootstrap: $local not found" }
            local.copyTo(dest, overwrite = true)
            logger.lifecycle("Neuron: bundled local Termux bootstrap ${local.name} sha256=${sha256Of(dest)}")
            return@doLast
        }
        if (dest.isFile && sha256Of(dest) == termuxBootstrapSha256) return@doLast
        val tag = URLEncoder.encode("bootstrap-$termuxBootstrapVersion", "UTF-8")
        val url = URI("https://github.com/termux/termux-packages/releases/download/$tag/bootstrap-aarch64.zip").toURL()
        logger.lifecycle("Neuron: downloading $url")
        url.openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        val actual = sha256Of(dest)
        if (actual != termuxBootstrapSha256) {
            dest.delete()
            throw GradleException("Termux bootstrap checksum mismatch: expected $termuxBootstrapSha256, got $actual")
        }
    }
}

android {
    namespace = "com.tryptz.neuron"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.tryptz.neuron"
        minSdk = androidApiLevel
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release signing: reads keystore.properties at the project root. The file and
    // the keystore it points to are git-ignored; without them release builds are
    // unsigned (CI) and only debug builds are installable.
    val keystoreProps = rootProject.file("keystore.properties")
    if (keystoreProps.exists()) {
        val props = Properties().also { p -> keystoreProps.inputStream().use { p.load(it) } }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    // HTP skels must be stored uncompressed: NeuronCore compares the asset's
    // openFd() length with the staged copy, and openFd() fails on compressed assets.
    androidResources { noCompress += listOf("so", "zip") }

    sourceSets["main"].jniLibs.srcDir(rustJniLibs)
    sourceSets["main"].assets.srcDir(rustAssets)
    sourceSets["main"].assets.srcDir(termuxAssets)
    sourceSets["main"].kotlin.srcDir(uniffiKotlinOut)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Extract native libs to nativeLibraryDir: ggml dlopen()s its backend
        // modules by path, and FastRPC reads the HTP skel from a real directory.
        jniLibs { useLegacyPackaging = true }
    }
}

tasks.named("preBuild") { dependsOn(collectRustLibs, collectHtpSkels, generateUniffiBindings, prepareTermuxBootstrap) }

composeCompiler {
    stabilityConfigurationFile = rootProject.layout.projectDirectory.file("compose_stability.conf")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.syntax) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation(libs.timber)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    // JNA is the FFI runtime of the UniFFI-generated bindings.
    implementation("${libs.jna.get()}@aar")
    implementation(libs.termux.terminal.view)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
