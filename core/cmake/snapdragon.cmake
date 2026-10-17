# Included by CMake after every project() call when the build exports
# CMAKE_PROJECT_INCLUDE=<this file> (llama-cpp-sys-2 forwards any CMAKE_* env var
# as a cache define). It bridges environment variables into the CMake cache the
# way llama.cpp's own docs/backend/snapdragon/CMakeUserPresets.json does.

# Hexagon SDK (NPU backend). Only a CMake variable upstream, never read from env.
if(DEFINED ENV{HEXAGON_SDK_ROOT} AND NOT HEXAGON_SDK_ROOT)
  set(HEXAGON_SDK_ROOT "$ENV{HEXAGON_SDK_ROOT}" CACHE PATH "Hexagon SDK root" FORCE)
endif()
if(DEFINED ENV{HEXAGON_TOOLS_ROOT} AND NOT HEXAGON_TOOLS_ROOT)
  set(HEXAGON_TOOLS_ROOT "$ENV{HEXAGON_TOOLS_ROOT}" CACHE PATH "Hexagon tools root" FORCE)
endif()

# OpenCL (Adreno backend). The NDK toolchain restricts find_* to the sysroot
# (CMAKE_FIND_ROOT_PATH_MODE_* = ONLY), so CMAKE_PREFIX_PATH alone cannot reach
# our link stubs; point FindOpenCL at them directly.
if(DEFINED ENV{OPENCL_SDK_ROOT} AND NOT OpenCL_LIBRARY)
  set(_neuron_ocl "$ENV{OPENCL_SDK_ROOT}")
  if(EXISTS "${_neuron_ocl}/include/CL/cl.h")
    set(OpenCL_INCLUDE_DIR "${_neuron_ocl}/include" CACHE PATH "OpenCL headers" FORCE)
  endif()
  file(GLOB _neuron_ocl_lib "${_neuron_ocl}/lib/libOpenCL.so" "${_neuron_ocl}/lib/libOpenCL.a")
  if(_neuron_ocl_lib)
    list(GET _neuron_ocl_lib 0 _neuron_ocl_lib)
    set(OpenCL_LIBRARY "${_neuron_ocl_lib}" CACHE FILEPATH "OpenCL ICD loader" FORCE)
  endif()
endif()
