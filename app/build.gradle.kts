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
val uniffiKotlinOut = layout.buildDirectory.dir("generated/source/uniffi/main")
val androidApiLevel = 28

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compiles libneuron.so and the ggml backend modules for arm64-v8a"
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
    } else {
        logger.warn("Neuron: Adreno OpenCL backend OFF — run core/scripts/setup-opencl-android.sh")
    }
    val hexagonSdk = System.getenv("HEXAGON_SDK_ROOT")
    if (hexagonSdk != null && File(hexagonSdk).isDirectory) {
        environment("GGML_HEXAGON", "ON")
        environment("HEXAGON_SDK_ROOT", hexagonSdk)
    } else {
        logger.warn("Neuron: Hexagon NPU backend OFF — HEXAGON_SDK_ROOT not set (build inside the Snapdragon toolchain image)")
    }
}

/** libllama / libggml* / libggml-<backend>.so collected by neuron-core's build script. */
val collectRustLibs by tasks.registering(Copy::class) {
    group = "rust"
    dependsOn(cargoNdkBuild)
    from(rustTargetDir.resolve("neuron-libs")) { include("*.so") }
    into(rustJniLibs.map { it.dir("arm64-v8a") })
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generates Kotlin bindings (com.tryptz.neuron.core) from libneuron.so"
    dependsOn(cargoNdkBuild)
    workingDir = rustDir
    val library = rustTargetDir.resolve("libneuron.so")
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

    sourceSets["main"].jniLibs.srcDir(rustJniLibs)
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

tasks.named("preBuild") { dependsOn(collectRustLibs, generateUniffiBindings) }

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
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
