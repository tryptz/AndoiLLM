# Contributing to Neuron

## Development setup

1. Clone the repository.
2. Install Android Studio (Ladybug or newer), Android SDK 36, NDK
   `29.0.14206865`, CMake 3.22+, Ninja.
3. Install Rust stable, the Android target and cargo-ndk:
   ```bash
   rustup target add aarch64-linux-android
   cargo install cargo-ndk
   ```
4. Build the OpenCL link stubs once:
   ```bash
   ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/29.0.14206865 core/scripts/setup-opencl-android.sh
   ```
5. `./gradlew assembleDebug`

## Where things live

```
core/crates/neuron-core      engine + backend policy, SoC table, GGUF, templates, thermal, sampling
core/crates/neuron-sandbox   QuickJS sandbox
core/crates/neuron-ffi       UniFFI surface (what Kotlin sees)
app/.../inference/core       NeuronCore: owns the engine, stages the HTP skel
app/.../inference/backend    InferenceEngine façade, ThermalManager.classify
app/.../code/sandbox         CodeExecutor (bash gate, language detection)
app/.../domain, data, ui     app layers (unchanged by the core)
```

Rule of thumb: if it needs the model, a tokenizer, or a hardware backend, it
belongs in Rust. If it needs a `Context`, Room, or Compose, it belongs in
Kotlin. Mapping between the two lives in `InferenceEngine.kt`.

## Testing

```bash
cd core
cargo test -p neuron-core --no-default-features -p neuron-sandbox   # fast, pure Rust
cargo test                                                          # also builds llama.cpp for the host
cargo clippy --workspace --all-targets --no-default-features -- -D warnings
cargo fmt --all --check
cd ..
./gradlew testDebugUnitTest
```

The Rust test suites are the source of truth for prompt templates, backend
selection, thermal thresholds and the JS sandbox; the Kotlin tests cover
domain mapping, the DataStore mapper, repositories and the bash gate.

## Changing the FFI surface

1. Edit `core/crates/neuron-ffi/src/lib.rs` (records, enums, `NeuronEngine`
   methods, free functions).
2. Build once (`./gradlew assembleDebug` or `core/scripts/build-android.sh`);
   the Kotlin bindings regenerate into
   `app/build/generated/source/uniffi/main`.
3. Update the mapping in `InferenceEngine.kt`.

Never hand-edit generated Kotlin; it is rebuilt on every change to `libneuron.so`.

## Branching

- `main` — stable, always buildable
- Feature branches: `feature/description`, fixes: `fix/description`
- Never name a branch `Claude/something`

## Commit convention

```
type(scope): description

feat(core): add Hexagon power-mode setting
fix(sandbox): classify QuickJS out-of-memory as Memory
refactor(app): drop the JNI bridges in favour of UniFFI
```

Commits are authored as `tryptz`, without co-author trailers.

## Code style

- Kotlin: one concern per file; domain types `@Immutable`; animations through
  `MotionTokens`; validate inputs before they reach the core.
- Rust: `cargo fmt` + clippy clean; every `unsafe` block carries a `SAFETY:`
  comment that states the invariant; pure logic gets host unit tests.
