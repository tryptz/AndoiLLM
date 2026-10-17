# Neuron architecture (v1.0, Rust core)

Neuron is a private, on-device LLM app for Android, tuned for the OnePlus 15's
Snapdragon 8 Elite Gen 5. This document explains how the pieces fit and why
they are shaped the way they are. It is written for someone who knows Android
but is new to the native side.

## One-paragraph version

Kotlin draws the UI and stores conversations. Everything that touches the
model is Rust: a workspace in `core/` that drives llama.cpp through the
`llama-cpp-2` crate, decides which silicon to use (Hexagon NPU, Adreno GPU,
CPU), formats prompts, samples tokens, throttles on heat, and sandboxes
model-written JavaScript in QuickJS. UniFFI generates the Kotlin bindings so
the two sides talk through typed records and one `NeuronEngine` object, not
hand-written JNI.

## Why Rust replaced the C++ JNI layer

The first version had two C++ files with `extern "C"` JNI functions, manual
handle integers passed to Kotlin, and `#if LLAMA_AVAILABLE` stubs. Six months
on, three things were wrong with that:

1. Every new capability (a sampler knob, a telemetry field) meant editing a
   JNI signature, a Kotlin `external fun`, and keeping the two in sync by hand.
2. Lifetime bugs (use-after-free on model switch, a completion context outliving
   its model) could only be found on a phone.
3. The Snapdragon backends in llama.cpp moved: the NPU path is now the
   upstream `ggml-hexagon` backend and the GPU path is `ggml-opencl` with
   Adreno 840 verified, replacing the Vulkan/QNN plan.

Rust fixes 1 and 2 structurally (UniFFI generates the bridge; ownership makes
the model/context lifetime a type), and the rebuild was the moment to adopt 3.

## The crates

```
core/
├── Cargo.toml                 workspace, shared dependency versions, release profile
├── cmake/snapdragon.cmake     injects HEXAGON_SDK_ROOT / OPENCL_SDK_ROOT into llama.cpp's CMake cache
├── scripts/
│   ├── build-android.sh       cargo ndk + collect libs + generate Kotlin (what Gradle does, for CI/containers)
│   └── setup-opencl-android.sh builds the Khronos OpenCL headers/ICD loader the GPU backend links against
└── crates/
    ├── neuron-core            the engine (feature `llama`) + pure modules tested on the host
    ├── neuron-sandbox         QuickJS sandbox (rquickjs)
    ├── neuron-ffi             UniFFI surface → libneuron.so, Kotlin package com.tryptz.neuron.core
    └── uniffi-bindgen         tiny binary Gradle runs to emit the Kotlin bindings
```

### neuron-core

| module | job | tested on host |
|---|---|---|
| `backend` | policy: given the ggml devices present, pick NPU → GPU → CPU; NPU only for models ≤ 4000 MB | yes |
| `soc` | `ro.soc.model` → HTP arch (SM8850 = v81) and the skel file name it needs | yes |
| `gguf` | header reader: architecture, context length, file type, Jinja template | yes |
| `template` | ChatML / Llama 3 / Gemma / Phi / Mistral prompt formats, `/no_think` handling | yes |
| `thermal` | thresholds (65/75/85 °C), per-token pause policy, tok/s cap | yes |
| `sampling` | validated sampler parameters | yes |
| `sysinfo` | sysfs temperature, `/proc/meminfo`, thread-count heuristic | yes |
| `engine` | load model, build context, stream tokens, cancel, telemetry | needs llama.cpp |

The engine keeps one `LlamaModel` boxed next to its `LlamaContext`. The
context borrows the model for `'static` via one documented `transmute`; the
struct's field order drops the context first, which is the whole invariant.

Generation is a plain blocking loop: tokenize, clear the KV cache, feed the
prompt in `n_batch` chunks, then sample → check end-of-generation → decode one
token → emit. A `CancelToken` (an atomic flag) is checked every token so
cancelling from another thread is immediate and lock-free. Pieces are passed
through a UTF-8 accumulator so a multibyte character split across two tokens
never reaches the UI as garbage.

### Backends as dlopen'ed modules

llama.cpp is built with `GGML_BACKEND_DL=ON` (the `dynamic-backends` feature of
`llama-cpp-2`). Each backend is its own shared library:

| file | silicon | note |
|---|---|---|
| `libggml-cpu-*.so` | Oryon CPU | one variant per ARM feature level, picked at runtime |
| `libggml-opencl.so` | Adreno 840 | depends on `libOpenCL.so`, resolved to the Qualcomm driver in `/vendor/lib64` |
| `libggml-hexagon.so` | Hexagon HTP v81 | talks to the DSP through FastRPC; needs `libggml-htp-v81.so` on the DSP side |

At startup the engine calls `ggml_backend_load_all_from_path(nativeLibraryDir)`.
A module whose driver is missing fails to `dlopen` and is skipped. That is the
single most important design choice for a phone app: on a device without the
OpenCL driver you lose the GPU device, not the process.

The HTP skel is a Hexagon ELF, not an ARM library, so it cannot live in
`jniLibs`. It ships in `app/src/main/assets/htp/`, is copied to `filesDir/htp`
on first run, and that directory is exported as `ADSP_LIBRARY_PATH` /
`DSP_LIBRARY_PATH` before the backend module loads; FastRPC resolves the
backend's `file:///libggml-htp-v81.so` URI against it.

### neuron-sandbox

A fresh QuickJS runtime per run. Only ECMAScript intrinsics exist (QuickJS'
`std`/`os` modules are not linked), so there is no file, socket or process
access to disable. Limits: memory ceiling (`JS_SetMemoryLimit`), 1 MB stack,
and a deadline enforced from the interpreter's interrupt hook. `console.*` is
captured. Out-of-memory in QuickJS surfaces as a bare `null` exception; the
sandbox recognises that and reports `Memory`.

### neuron-ffi

Declares the Kotlin-visible types with `#[derive(uniffi::Record)]` /
`uniffi::Enum` and one `#[derive(uniffi::Object)] NeuronEngine`. Streaming uses
a callback interface (`TokenSink`) implemented in Kotlin; returning `false`
stops the generation. Errors are a flat enum, so Kotlin sees
`NeuronException.ModelNotFound` etc. with a message.

## Build pipeline

```
Gradle preBuild
 ├─ cargoNdkBuild          cargo ndk -t arm64-v8a -P 28 --link-libcxx-shared -o build/rustJniLibs build -p neuron-ffi --release
 │                         env: GGML_OPENCL=ON + OPENCL_SDK_ROOT (if the OpenCL stubs exist)
 │                              GGML_HEXAGON=ON + HEXAGON_SDK_ROOT (if set) + CMAKE_PROJECT_INCLUDE=cmake/snapdragon.cmake
 ├─ collectRustLibs        copies libllama/libggml*/libggml-<backend>.so from target/.../neuron-libs
 └─ generateUniffiBindings cargo run -p uniffi-bindgen -- generate --library libneuron.so --language kotlin
```

`neuron-core/build.rs` is what makes `collectRustLibs` possible: it reads the
`DEP_LLAMA_BACKENDS_DIR` that `llama-cpp-sys-2` exports and copies every shared
library into one predictable folder.

The Hexagon backend needs the Hexagon SDK, which exists only inside Qualcomm's
toolchain container (`ghcr.io/snapdragon-toolchain/arm64-android:v0.7`,
Hexagon SDK 6.6.0.0). The `hexagon` CI job builds there and publishes
`libggml-hexagon.so` and the skels as an artifact. A build on an ordinary
machine produces CPU + OpenCL and the engine simply reports no NPU device.

## Kotlin side after the rebuild

- `inference/core/NeuronCore.kt` owns the single `NeuronEngine`, stages the HTP
  skel from assets, logs the ggml devices found.
- `inference/backend/InferenceEngine.kt` maps domain settings to core records,
  runs `generate` on the IO dispatcher, and keeps `<think>` splitting (a
  presentation concern) in Kotlin.
- `code/sandbox/CodeExecutor.kt` calls `runJavascript`; the bash gate and
  language detection are unchanged.
- Deleted: `LlamaBridge`, `QuickJSBridge`, `BackendResolver`,
  `PromptFormatter`, `ModelHandle`, the `jni/` tree and its CMake build.
- `ThermalManager` keeps only `classify` for the UI; throttling moved to Rust
  with the same thresholds.

## Logging

Rust (and llama.cpp through it) logs under the logcat tag `NeuronCore`; Kotlin
logs under `Neuron.*`. `adb logcat -s NeuronCore Neuron.*` shows the stack.
