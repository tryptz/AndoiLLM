# Hexagon HTP skeleton libraries

The skels (`libggml-htp-v81.so` for the Snapdragon 8 Elite Gen 5 / OnePlus 15,
plus v79, v75, v73) are added to the APK's `assets/htp/` at build time from the
Rust core's output; nothing needs to be copied here by hand.

They are Hexagon DSP ELF objects produced by the `ggml-hexagon` build inside the
Snapdragon toolchain container (`core/scripts/build-android.sh` with
`HEXAGON_SDK_ROOT` set puts them in `target/android-jniLibs/htp/`, and
`./gradlew assembleRelease -PneuronPrebuiltLibs=core/target/android-jniLibs`
packages them). They are
shipped as assets rather than `jniLibs` because they are not ARM binaries; the
app copies them to `filesDir/htp` on first launch and points FastRPC at that
directory (`ADSP_LIBRARY_PATH` / `DSP_LIBRARY_PATH`) before loading the
`libggml-hexagon.so` backend module.

Without a skel the NPU backend reports no device and Neuron falls back to the
Adreno GPU (OpenCL) or the CPU automatically.
