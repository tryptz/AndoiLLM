# Neuron — On-Device LLM for Android

Private, local AI inference running entirely on-device, with a Rust core.
Optimized for the Snapdragon 8 Elite Gen 5 (OnePlus 15): Hexagon HTP v81
NPU, Adreno 840 GPU, and Oryon CPU backends.

## What changed in 1.0

Six months after the first cut, Neuron was rebuilt from the ground up around
a Rust inference core and the current Snapdragon driver stack in llama.cpp:

| | 0.1 (C++ JNI) | 1.0 (Rust core) |
|---|---|---|
| Native layer | two hand-written JNI files, integer handles | `core/` Rust workspace, UniFFI-generated Kotlin bindings |
| NPU | planned via QNN, never wired | upstream `ggml-hexagon` backend (Hexagon SDK 6.6, HTP v73–v81) |
| GPU | Vulkan compute | `ggml-opencl` with Adreno 840 verified upstream |
| Backend loading | compiled in | each backend is a dlopen'ed module; a missing driver degrades, never crashes |
| JS sandbox | QuickJS via JNI | QuickJS via `rquickjs`, memory + deadline limits, host-tested |
| Prompt templates, thermal policy, backend policy | Kotlin | Rust, unit-tested on the host |
| llama.cpp | git clone into `jni/` | pinned through `llama-cpp-2` 0.1.160 |

The full design is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Architecture in brief

Kotlin + Jetpack Compose draws the UI and persists conversations (Room,
DataStore, WorkManager downloads). Everything that touches the model lives in
`core/`:

- **neuron-core** — the engine. Loads a GGUF, builds a llama.cpp context on the
  chosen backend, streams tokens with cancellation, applies thermal throttling
  and tok/s caps per token, exposes telemetry. Pure modules (backend policy,
  SoC table, GGUF reader, chat templates, thermal thresholds, sampling) are
  tested with `cargo test` on any machine.
- **neuron-sandbox** — QuickJS sandbox for model-written JavaScript.
- **neuron-ffi** — the UniFFI surface compiled to `libneuron.so`; Kotlin sees
  `com.tryptz.neuron.core.NeuronEngine` plus plain records and enums.

Backend selection is automatic (`Auto` prefers NPU → GPU → CPU, keeping models
over 4 GB off the NPU) or explicit from the Expert settings tier; the engine
reports the backend it actually used and why.

## Project structure

```
AndoiLLM/
├── core/                              # Rust workspace (see docs/ARCHITECTURE.md)
│   ├── crates/neuron-core             # engine + pure modules
│   ├── crates/neuron-sandbox          # QuickJS sandbox
│   ├── crates/neuron-ffi              # UniFFI → libneuron.so
│   ├── crates/uniffi-bindgen          # Kotlin binding generator
│   ├── cmake/snapdragon.cmake         # Hexagon SDK + Oryon flags into llama.cpp's CMake
│   └── scripts/                       # build-android.sh, setup-opencl-android.sh
├── app/
│   ├── build.gradle.kts               # drives cargo ndk + UniFFI before compiling Kotlin
│   └── src/main/
│       ├── assets/htp/                # libggml-htp-vNN.so (Hexagon DSP skels)
│       └── java/com/tryptz/neuron/
│           ├── inference/core/NeuronCore.kt        # owns the NeuronEngine, stages HTP skel
│           ├── inference/backend/InferenceEngine.kt # Kotlin façade over the core
│           ├── code/sandbox/CodeExecutor.kt        # JS via core sandbox; bash gated
│           ├── data/, domain/, ui/                  # unchanged app layers
│           └── ...
└── docs/ARCHITECTURE.md
```

## Build

### Prerequisites

- JDK 17+, Android SDK 36, NDK r29 (`29.0.14206865`), CMake 3.22+, Ninja
- Rust stable (1.85+) with `rustup target add aarch64-linux-android`
- `cargo install cargo-ndk`
- libclang (for bindgen; `apt install libclang-dev` / Xcode CLT)

### One-time: OpenCL link stubs for the Adreno backend

```bash
ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/29.0.14206865 core/scripts/setup-opencl-android.sh
```

This clones the Khronos OpenCL headers and ICD loader and builds them for
arm64 into `core/third_party/opencl-android/`. They are link-time stand-ins
only: on the phone, `libggml-opencl.so` resolves to Qualcomm's driver in
`/vendor/lib64`.

### Build the app

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle runs `cargo ndk` for arm64-v8a, collects `libllama`, `libggml*` and the
backend modules into `jniLibs`, and generates the Kotlin bindings. The first
build compiles llama.cpp (several minutes); later builds are incremental.

Useful flags:

| | |
|---|---|
| `./gradlew assembleDebug -PrustDebug` | Rust debug profile (faster compile, slower inference) |
| `cd core && cargo test` | host tests, including the engine against a host llama.cpp build |
| `cd core && cargo test -p neuron-core --no-default-features -p neuron-sandbox` | pure-Rust tests only, no llama.cpp compile |
| `core/scripts/build-android.sh` | the same steps Gradle runs, for CI and containers |

### Hexagon NPU backend

`libggml-hexagon.so` and the DSP-side skels (`libggml-htp-v81.so` for the
8 Elite Gen 5) need the Hexagon SDK, which Qualcomm ships only inside the
Snapdragon toolchain image used by llama.cpp. The image has Hexagon SDK
6.6.0.0 and NDK r29 but no Rust, so mount yours in:

```bash
docker run --rm --network host -v "$PWD":"$PWD" -w "$PWD/core" \
  -v ~/.cargo:/root/.cargo -v ~/.rustup:/root/.rustup \
  -e PATH=/root/.cargo/bin:/usr/local/bin:/usr/bin:/bin \
  -e CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=clang \
  -e CC_x86_64_unknown_linux_gnu=clang -e CXX_x86_64_unknown_linux_gnu=clang++ \
  -e CMAKE_GENERATOR=Ninja -e CARGO_TARGET_DIR="$PWD/core/target/snapdragon" \
  -e ANDROID_NDK_ROOT=/opt/android-ndk-r29 -e HEXAGON_SDK_ROOT=/opt/hexagon/6.6.0.0 \
  -e OPENCL_SDK_ROOT="$PWD/core/third_party/opencl-android" \
  ghcr.io/snapdragon-toolchain/arm64-android:v0.7 \
  scripts/build-android.sh --out "$PWD/core/target/android-jniLibs"

./gradlew assembleRelease -PneuronPrebuiltLibs=core/target/android-jniLibs
```

The image has `clang` and `ninja` but no `cc` or `make`, hence the extra
variables. `-PneuronPrebuiltLibs` makes Gradle package that output instead of
running cargo: ARM libraries go to `jniLibs`, the `htp/` skels go to assets
uncompressed. The `hexagon` CI job runs the same build and uploads the
libraries as an artifact. Without them the engine lists no HTP device and uses
the GPU or CPU.

Hexagon knobs are environment variables read by the backend; the app sets the
important ones (`ADSP_LIBRARY_PATH`/`DSP_LIBRARY_PATH` to the skel directory,
optionally `GGML_HEXAGON_POWER_MODE`). `GGML_HEXAGON_VERBOSE=1` and
`GGML_HEXAGON_PROFILE=1` are available for debugging on a rooted shell.

### Signing

Release builds read `keystore.properties` at the project root:

```
storeFile=neuron-release.jks
storePassword=...
keyAlias=neuron
keyPassword=...
```

Both the properties file and `*.jks` are git-ignored. The keystore is PKCS12,
so `keyPassword` must equal `storePassword` (Java ignores a separate key
password for PKCS12). Keep the keystore and its password in a password
manager: an app update must be signed with the same key or Android refuses to
install it over the previous version.

To create a fresh key:

```bash
keytool -genkeypair -keystore neuron-release.jks -storetype PKCS12 -alias neuron \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Neuron, O=tryptz"
```

### Sideloading models

Models download inside the app from Hugging Face. To sideload:

```bash
adb push Llama-3.2-3B-Instruct-Q4_K_M.gguf /data/data/com.tryptz.neuron/files/models/
```

The Model Manager also imports any GGUF from device storage and reads its
header (architecture, context length, quantization, chat template) to fill in
the settings.

## Logging

```bash
adb logcat -s NeuronCore Neuron.*
```

`NeuronCore` carries the Rust core and llama.cpp's own diagnostics (model load
reasons, backend devices found, Hexagon session messages); `Neuron.*` is the
Kotlin side.

## Performance targets (OnePlus 15)

Estimates for the shipped model registry; measure with `[op=inference_complete]`
log lines, which report average tok/s per generation.

| Model | Backend | Quant | tok/sec | RAM |
|---|---|---|---|---|
| Llama 3.2 1B | NPU (HTP v81) | Q4_0 | 150–220 | ~1.1 GB |
| Llama 3.2 3B | NPU (HTP v81) | Q4_0 | 60–90 | ~2.5 GB |
| Gemma 3 4B IT | GPU (OpenCL) | Q4_K_M | 25–45 | ~3.5 GB |
| Qwen 3 8B | GPU (OpenCL) | Q4_K_M | 18–30 | ~5.6 GB |
| Qwen 2.5 7B | GPU (OpenCL) | Q4_K_M | 20–32 | ~5.4 GB |

The Hexagon backend is optimised for Q4_0 weights; the OpenCL backend covers
Q4_0, Q4_K, Q5_K, Q6_K, Q8_0, MXFP4 and IQ4_NL.

## License

Copyright (c) 2026 tryptz. All rights reserved.
