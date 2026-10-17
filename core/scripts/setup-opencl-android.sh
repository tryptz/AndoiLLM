#!/usr/bin/env bash
# Builds the pieces ggml-opencl needs to *link* for Android: the Khronos OpenCL
# headers and the Khronos ICD loader (libOpenCL.so), into
#   core/third_party/opencl-android/{include,lib}
#
# The loader is only a link-time stand-in. It is NOT packaged in the APK: at
# runtime Android resolves libggml-opencl.so's dependency on libOpenCL.so to
# the Qualcomm driver in /vendor/lib64 (exported through the vendor's
# public.libraries.txt on every Snapdragon phone). Because the backend is a
# dlopen'ed module, a device without that driver simply has no GPU device.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/../third_party/opencl-android}"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" ]]; then
  NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
[[ -d "$NDK" ]] || { echo "set ANDROID_NDK_ROOT (or ANDROID_HOME with an ndk/ dir)" >&2; exit 1; }
API="${ANDROID_API_LEVEL:-28}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo ">> cloning Khronos OpenCL-Headers and OpenCL-ICD-Loader"
git clone --depth 1 https://github.com/KhronosGroup/OpenCL-Headers.git "$WORK/headers"
git clone --depth 1 https://github.com/KhronosGroup/OpenCL-ICD-Loader.git "$WORK/loader"

mkdir -p "$OUT/include" "$OUT/lib"
cp -r "$WORK/headers/CL" "$OUT/include/"

echo ">> building ICD loader for arm64-v8a (API $API)"
cmake -S "$WORK/loader" -B "$WORK/loader-build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" \
  -DOPENCL_ICD_LOADER_HEADERS_DIR="$WORK/headers" \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF -DBUILD_SHARED_LIBS=ON
cmake --build "$WORK/loader-build" --parallel
cp "$WORK/loader-build/libOpenCL.so" "$OUT/lib/"
echo ">> OpenCL SDK ready at $OUT"
