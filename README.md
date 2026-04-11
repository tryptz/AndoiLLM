# Neuron — On-Device LLM for Android

Private, local AI inference running entirely on-device. Optimized for
Snapdragon 8 Elite Gen 5 (OnePlus 15) with Hexagon NPU, Adreno GPU,
and Oryon CPU backends.

## Architecture

Neuron is a native Android application built with Kotlin and Jetpack
Compose, architected around three pillars: a multi-backend inference
engine, a sandboxed code execution environment, and a flagship-tier
animation system.

**Inference** flows through a JNI bridge (`LlamaBridge`) to a compiled
llama.cpp library, supporting three backends ranked by priority: Hexagon
NPU via Qualcomm QNN for maximum tok/sec and battery efficiency, Adreno
GPU via Vulkan compute for larger models, and CPU with NEON SIMD as a
universal fallback. The `InferenceEngine` class orchestrates model loading,
prompt formatting (supporting chatml, llama3, gemma, phi, and mistral
templates), streaming token generation with thermal throttling, and
battery-aware tok/sec capping. Model selection is driven by a curated
`ModelRegistry` with per-model, per-backend performance estimates
calibrated for the Snapdragon 8 Elite Gen 5.

**Code execution** embeds QuickJS (JavaScript) via a second native library
(`neuron_quickjs`), with Python support planned through Chaquopy and shell
via Android's `/system/bin/sh`. All code runs in isolated contexts with
enforced memory limits (default 64MB), execution timeouts (default 10s),
and a security sandbox that blocks filesystem, network, and system access.
The LLM's code output is automatically parsed, rendered with syntax
highlighting, and paired with "Run" buttons that display stdout, stderr,
return values, and timing in an animated output panel.

**Progressive disclosure** ensures the app feels like a messaging app on
first launch—just a chat input, a model selector, and a send button—while
hiding a full DSP-grade parameter surface (backend override, NPU precision,
KV-cache quantization, thread pinning, thermal policy) behind three
expanding settings tiers: Basic → Advanced → Expert. The animation system
is centralized through `MotionTokens`, defining spring configs (GENTLE,
RESPONSIVE, SNAPPY, BOUNCY) and tween curves (EMPHASIZED, DECELERATE,
ACCELERATE) that target the 165Hz LTPO display.

## Project Structure

```
NeuronApp/
├── build.gradle.kts                    # Root build config
├── settings.gradle.kts
├── gradle.properties
├── compose_stability.conf              # Compose compiler stability hints
├── gradle/libs.versions.toml           # Version catalog
├── app/
│   ├── build.gradle.kts                # App module with NDK + Compose
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/values/{strings,themes}.xml
│       ├── jni/
│       │   ├── CMakeLists.txt          # Native build: llama.cpp + QuickJS
│       │   ├── neuron_inference_jni.cpp # LLM inference JNI bridge
│       │   ├── neuron_quickjs_jni.cpp   # JS execution JNI bridge
│       │   ├── llama_cpp/              # ← clone llama.cpp here
│       │   └── quickjs/                # ← clone QuickJS here
│       └── java/com/tryptz/neuron/
│           ├── NeuronApp.kt            # Application + WorkManager
│           ├── MainActivity.kt         # Entry point
│           ├── di/
│           │   └── AppModule.kt        # Hilt: Room, Ktor, DAOs
│           ├── domain/model/
│           │   └── Models.kt           # All domain types (@Immutable)
│           ├── data/
│           │   ├── local/
│           │   │   ├── NeuronDatabase.kt
│           │   │   ├── dao/            # ConversationDao, MessageDao,
│           │   │   │                   # InstalledModelDao, CodeSnippetDao
│           │   │   ├── entity/Entities.kt
│           │   │   └── datastore/SettingsDataStore.kt
│           │   ├── repository/
│           │   │   ├── ConversationRepository.kt
│           │   │   └── ModelRepository.kt
│           │   └── model/
│           │       └── ModelRegistry.kt # Curated model catalog
│           ├── inference/
│           │   ├── bridge/LlamaBridge.kt # JNI declarations
│           │   └── backend/InferenceEngine.kt # Multi-backend orchestrator
│           ├── code/
│           │   ├── engine/QuickJSBridge.kt # JNI declarations
│           │   └── sandbox/CodeExecutor.kt # Sandboxed JS/Py/Bash runner
│           ├── download/
│           │   └── ModelDownloadWorker.kt  # WorkManager + HF downloads
│           ├── service/
│           │   └── InferenceService.kt     # Foreground service + wake lock
│           ├── util/
│           │   ├── DeviceMonitor.kt    # RAM, thermal, battery telemetry
│           │   └── Extensions.kt       # formatBytes, extractCodeBlocks
│           └── ui/
│               ├── NeuronNavHost.kt    # Navigation with animated transitions
│               ├── animation/
│               │   ├── MotionTokens.kt        # Central animation design system
│               │   └── AnimatedComponents.kt   # TypingIndicator, ThermalDot, etc.
│               ├── theme/
│               │   ├── Theme.kt        # OLED-optimized dark + Material You
│               │   └── Type.kt         # Typography scale
│               ├── chat/
│               │   ├── ChatScreen.kt   # Main chat UI
│               │   ├── components/
│               │   │   ├── ChatBubble.kt       # Message bubbles + code blocks
│               │   │   ├── StreamingBubble.kt   # Streaming response with thinking
│               │   │   └── ModelSelectorSheet.kt # Bottom sheet model picker
│               │   └── viewmodel/
│               │       └── ChatViewModel.kt     # Chat state + inference orchestration
│               ├── settings/
│               │   └── SettingsPanel.kt  # Progressive disclosure settings
│               ├── modelmanager/
│               │   ├── ModelManagerScreen.kt
│               │   └── viewmodel/ModelManagerViewModel.kt
│               └── editor/
│                   └── CodeEditorScreen.kt # Full editor with undo/redo/zoom
```

## Build Instructions

### Prerequisites

- Android Studio Ladybug (2024.2.2) or later
- JDK 17+
- Android SDK 36
- NDK r27+ (install via SDK Manager → SDK Tools → NDK)
- CMake 3.22.1+ (install via SDK Manager → SDK Tools → CMake)

### Setup Native Dependencies

```bash
cd app/src/main/jni

# Clone llama.cpp
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git llama_cpp

# Clone QuickJS
git clone --depth 1 https://github.com/nicbarker/quickjs.git quickjs
# Or use bellard's: https://bellard.org/quickjs/
```

### Build

```bash
# From project root
./gradlew assembleDebug

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build will compile llama.cpp and QuickJS for arm64-v8a,
which takes 5-10 minutes. Subsequent builds are incremental.

### Sideloading Models

Models are downloaded within the app from Hugging Face. For manual
sideloading:

```bash
# Download a GGUF model
wget https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf

# Push to the app's models directory
adb push Llama-3.2-3B-Instruct-Q4_K_M.gguf /data/data/com.tryptz.neuron/files/models/

# The app will detect sideloaded models on next launch if you
# register them in the installed_models database table.
```

## Performance Targets (OnePlus 15)

| Model | Backend | Quant | tok/sec | RAM |
|---|---|---|---|---|
| Gemma 4 E2B | NPU | INT4 | 150-220 | ~3 GB |
| Llama 3.2 3B | NPU | INT4 | 120-180 | ~2.2 GB |
| Phi-4 Mini | NPU | INT4 | 100-150 | ~2.5 GB |
| Qwen 2.5 7B | GPU | Q4_K_M | 20-35 | ~5.2 GB |
| Gemma 4 E4B | NPU | INT4 | 100-150 | ~4.5 GB |

## License

Copyright (c) 2026 tryptz. All rights reserved.
