#!/usr/bin/env bash
# Builds libneuron.so (+ the ggml backend modules) for arm64-v8a and generates
# the Kotlin bindings. Gradle runs the same steps; this script exists for CI and
# for building inside the Snapdragon toolchain container, where the Hexagon SDK
# is present and HEXAGON_SDK_ROOT is set.
#
#   scripts/build-android.sh [--debug] [--out <jniLibs dir>] [--kotlin-out <dir>]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CORE="$HERE/.."
PROFILE=release
OUT="$CORE/target/android-jniLibs"
KOTLIN_OUT="$CORE/target/uniffi-kotlin"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug) PROFILE=debug ;;
    --out) OUT="$2"; shift ;;
    --kotlin-out) KOTLIN_OUT="$2"; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
  shift
done

NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" ]]; then
  NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
[[ -d "$NDK" ]] || { echo "set ANDROID_NDK_ROOT" >&2; exit 1; }
export ANDROID_NDK_ROOT="$NDK" ANDROID_NDK_HOME="$NDK" ANDROID_PLATFORM="android-${ANDROID_API_LEVEL:-28}"
export CMAKE_PROJECT_INCLUDE="$CORE/cmake/snapdragon.cmake"

OPENCL_SDK="${OPENCL_SDK_ROOT:-$CORE/third_party/opencl-android}"
if [[ -f "$OPENCL_SDK/lib/libOpenCL.so" ]]; then
  export GGML_OPENCL=ON OPENCL_SDK_ROOT="$OPENCL_SDK"
  echo ">> Adreno OpenCL backend: ON ($OPENCL_SDK)"
else
  echo ">> Adreno OpenCL backend: OFF (run scripts/setup-opencl-android.sh)"
fi
if [[ -n "${HEXAGON_SDK_ROOT:-}" && -d "$HEXAGON_SDK_ROOT" ]]; then
  export GGML_HEXAGON=ON
  echo ">> Hexagon NPU backend: ON ($HEXAGON_SDK_ROOT)"
else
  echo ">> Hexagon NPU backend: OFF (HEXAGON_SDK_ROOT not set; use the Snapdragon toolchain image)"
fi

ARGS=(ndk -t arm64-v8a -P "${ANDROID_API_LEVEL:-28}" --link-libcxx-shared -o "$OUT" build -p neuron-ffi)
[[ "$PROFILE" == release ]] && ARGS+=(--release)
(cd "$CORE" && cargo "${ARGS[@]}")

TARGET_DIR="${CARGO_TARGET_DIR:-$CORE/target}/aarch64-linux-android/$PROFILE"
mkdir -p "$OUT/arm64-v8a"
rm -f "$OUT"/arm64-v8a/libggml-htp-*.so
cp "$TARGET_DIR"/neuron-libs/*.so "$OUT/arm64-v8a/" 2>/dev/null || true
echo ">> jniLibs:"; ls -1 "$OUT/arm64-v8a"

# HTP skels are Hexagon ELF objects, not ARM libraries: they ship as assets.
if compgen -G "$TARGET_DIR/neuron-libs/htp/libggml-htp-v*.so" >/dev/null; then
  mkdir -p "$OUT/htp"
  cp "$TARGET_DIR"/neuron-libs/htp/*.so "$OUT/htp/"
  echo ">> HTP skels:"; ls -1 "$OUT/htp"
fi

(cd "$CORE" && cargo run -q -p uniffi-bindgen --release -- generate \
  --library "$TARGET_DIR/libneuron.so" --language kotlin --no-format --out-dir "$KOTLIN_OUT")
echo ">> Kotlin bindings: $KOTLIN_OUT"
