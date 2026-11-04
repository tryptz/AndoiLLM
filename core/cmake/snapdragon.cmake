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

# The Hexagon SDK's hexagon_fun.cmake reads PREBUILT_LIB_DIR unconditionally.
# llama.cpp's Snapdragon preset sets it to android_aarch64 for the ARM side; the
# nested HTP skel builds pass their own toolv19_vNN value.
if(HEXAGON_SDK_ROOT AND NOT DEFINED PREBUILT_LIB_DIR)
  set(PREBUILT_LIB_DIR "android_aarch64" CACHE STRING "Hexagon SDK prebuilt lib dir" FORCE)
endif()

# ggml-hexagon installs the DSP skels with `install(FILES ... TYPE LIB)`, i.e.
# next to libllama.so. llama-cpp-sys-2 links every lib*.so it finds there, and
# a Hexagon ELF cannot be linked into an ARM library. Route only those files to
# <prefix>/htp instead. Guarded so repeated project() calls wrap install once.
get_property(_neuron_install_wrapped GLOBAL PROPERTY NEURON_INSTALL_WRAPPED)
if(HEXAGON_SDK_ROOT AND NOT _neuron_install_wrapped)
  set_property(GLOBAL PROPERTY NEURON_INSTALL_WRAPPED TRUE)
  function(install)
    set(_args "${ARGV}")
    list(GET _args 0 _kind)
    string(FIND "${ARGV}" "libggml-htp-" _is_skel)
    if(_kind STREQUAL "FILES" AND NOT _is_skel EQUAL -1)
      list(FIND _args "TYPE" _t)
      if(NOT _t EQUAL -1)
        math(EXPR _v "${_t} + 1")
        list(REMOVE_AT _args ${_v} ${_t})
        list(APPEND _args DESTINATION htp)
        message(STATUS "neuron: installing HTP skels to <prefix>/htp")
      endif()
    endif()
    _install(${_args})
  endfunction()
endif()
