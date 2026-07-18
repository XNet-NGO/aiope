cmake_minimum_required(VERSION 3.22.1)
project(aiope-inference C CXX)

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# Disable tools/tests — library only
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# CPU backend — max ARM64 optimization
set(GGML_OPENMP       ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
set(GGML_CPU_KLEIDIAI ON  CACHE BOOL "" FORCE)

# No GPU backends
set(GGML_OPENCL  OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN  OFF CACHE BOOL "" FORCE)
set(GGML_METAL   OFF CACHE BOOL "" FORCE)
set(GGML_CUDA    OFF CACHE BOOL "" FORCE)
set(GGML_SYCL    OFF CACHE BOOL "" FORCE)
set(GGML_RPC     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE  OFF CACHE BOOL "" FORCE)

# Compiler flags — aggressive optimization
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math -fno-finite-math-only -DNDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -fno-finite-math-only -DNDEBUG")

add_subdirectory(${LLAMA_CPP_DIR} llama.cpp)

# JNI library
add_library(aiope-inference SHARED jni_bridge.cpp)

target_include_directories(aiope-inference PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

target_link_libraries(aiope-inference PRIVATE
    llama
    android
    log
)
