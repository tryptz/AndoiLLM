# Hexagon HTP skeleton libraries

Drop `libggml-htp-v81.so` (Snapdragon 8 Elite Gen 5 / OnePlus 15) and, if you
want older phones, `libggml-htp-v79.so`, `v75`, `v73` in this folder.

They are Hexagon DSP ELF objects produced by the `ggml-hexagon` build inside the
Snapdragon toolchain container (`core/scripts/build-android.sh` with
`HEXAGON_SDK_ROOT` set copies them to `target/android-jniLibs/htp/`). They are
shipped as assets rather than `jniLibs` because they are not ARM binaries; the
app copies them to `filesDir/htp` on first launch and points FastRPC at that
directory (`ADSP_LIBRARY_PATH` / `DSP_LIBRARY_PATH`) before loading the
`libggml-hexagon.so` backend module.

Without a skel the NPU backend reports no device and Neuron falls back to the
Adreno GPU (OpenCL) or the CPU automatically.
