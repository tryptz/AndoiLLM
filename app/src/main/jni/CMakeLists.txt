cmake_minimum_required(VERSION 3.22.1)
project(neuron_native CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Options ──
option(LLAMA_VULKAN "Enable Vulkan GPU acceleration" ON)
option(LLAMA_HEXAGON "Enable Hexagon NPU acceleration" ON)

# ── llama.cpp ──
# llama.cpp source is expected at app/src/main/jni/llama_cpp/
# Clone it there: git clone https://github.com/ggml-org/llama.cpp.git llama_cpp
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama_cpp/CMakeLists.txt")
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

    if(LLAMA_VULKAN)
        set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    endif()

    # Hexagon NPU backend — requires Hexagon SDK 6.x
    # Install via Qualcomm Package Manager and set HEXAGON_SDK_ROOT
    if(LLAMA_HEXAGON AND DEFINED ENV{HEXAGON_SDK_ROOT})
        set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
        set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
        set(HEXAGON_NPU_AVAILABLE TRUE)
        message(STATUS "Hexagon NPU backend ENABLED (SDK: $ENV{HEXAGON_SDK_ROOT})")
    elseif(LLAMA_HEXAGON)
        set(HEXAGON_NPU_AVAILABLE FALSE)
        message(STATUS "Hexagon NPU backend SKIPPED (HEXAGON_SDK_ROOT not set)")
    else()
        set(HEXAGON_NPU_AVAILABLE FALSE)
    endif()

    add_subdirectory(llama_cpp)
    set(LLAMA_AVAILABLE TRUE)
else()
    message(WARNING "llama.cpp not found at ${CMAKE_CURRENT_SOURCE_DIR}/llama_cpp/ — building stub")
    set(LLAMA_AVAILABLE FALSE)
    set(HEXAGON_NPU_AVAILABLE FALSE)
endif()

# ── QuickJS ──
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/quickjs/quickjs.c")
    add_library(quickjs STATIC
        quickjs/quickjs.c
        quickjs/quickjs-libc.c
        quickjs/cutils.c
        quickjs/libbf.c
        quickjs/libregexp.c
        quickjs/libunicode.c
    )
    target_include_directories(quickjs PUBLIC quickjs/)
    target_compile_definitions(quickjs PRIVATE
        CONFIG_VERSION="2024-02-14"
        _GNU_SOURCE
    )
    set(QUICKJS_AVAILABLE TRUE)
else()
    message(WARNING "QuickJS not found — building stub")
    set(QUICKJS_AVAILABLE FALSE)
endif()

# ── Inference JNI bridge ──
add_library(neuron_inference SHARED
    neuron_inference_jni.cpp
)

target_include_directories(neuron_inference PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

if(LLAMA_AVAILABLE)
    target_link_libraries(neuron_inference PRIVATE llama ggml common)
    target_compile_definitions(neuron_inference PRIVATE LLAMA_AVAILABLE=1)
    if(HEXAGON_NPU_AVAILABLE)
        target_compile_definitions(neuron_inference PRIVATE HEXAGON_NPU_AVAILABLE=1)
    else()
        target_compile_definitions(neuron_inference PRIVATE HEXAGON_NPU_AVAILABLE=0)
    endif()
else()
    target_compile_definitions(neuron_inference PRIVATE LLAMA_AVAILABLE=0 HEXAGON_NPU_AVAILABLE=0)
endif()

target_link_libraries(neuron_inference PRIVATE
    android
    log
)

# ── QuickJS JNI bridge ──
add_library(neuron_quickjs SHARED
    neuron_quickjs_jni.cpp
)

target_include_directories(neuron_quickjs PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

if(QUICKJS_AVAILABLE)
    target_link_libraries(neuron_quickjs PRIVATE quickjs)
    target_compile_definitions(neuron_quickjs PRIVATE QUICKJS_AVAILABLE=1)
else()
    target_compile_definitions(neuron_quickjs PRIVATE QUICKJS_AVAILABLE=0)
endif()

target_link_libraries(neuron_quickjs PRIVATE
    android
    log
)
