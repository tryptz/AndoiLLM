# Contributing to Neuron

Thanks for your interest in contributing. This guide covers
everything you need to get started.

## Development Setup

1. Clone the repository
2. Install Android Studio Ladybug (2024.2.2+)
3. Install NDK r27+ and CMake 3.22.1+ via SDK Manager
4. Clone native dependencies into `app/src/main/jni/`:
   ```bash
   cd app/src/main/jni
   git clone --depth 1 https://github.com/ggml-org/llama.cpp.git llama_cpp
   git clone --depth 1 https://github.com/nicbarker/quickjs.git quickjs
   ```
5. Build: `./gradlew assembleDebug`

## Branching

- `main` — stable, always buildable
- Feature branches: `feature/description` (e.g. `feature/npu-backend`)
- Bug fixes: `fix/description`
- Never name a branch `Claude/something`

## Code Style

- One concern per file. If a file exceeds ~150 lines, decompose it.
- All domain types annotated `@Immutable` or `@Stable` for Compose.
- Use `MotionTokens` for all animations — no ad-hoc spring configs.
- Settings UI components live in `ui/settings/components/` as
  individual composables.
- Validate inputs before passing to native code (see `Validation.kt`).

## Commit Convention

```
type(scope): description

feat(inference): add QNN NPU backend
fix(chat): prevent crash on empty model list
refactor(settings): decompose SettingsPanel into atomic components
```

## Architecture

```
domain/model/    → @Immutable data classes, one per file
data/            → Room, DataStore, repositories
inference/       → JNI bridge, engine, prompt formatting, thermal
code/            → QuickJS bridge, sandboxed executor
ui/              → Compose screens, organized by feature
ui/animation/    → MotionTokens + reusable animated composables
util/            → DeviceMonitor, Validation, Extensions
```

## Testing

Run `./gradlew test` for unit tests. Native code tests are run
via `./gradlew connectedAndroidTest` on a physical device.
