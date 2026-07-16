cmake_minimum_required(VERSION 3.22.1)
project(aiope-inference)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(aiope-inference SHARED jni_bridge.cpp)

target_include_directories(aiope-inference PRIVATE
    llama.cpp/include
    llama.cpp/common
    llama.cpp/ggml/include
)

target_link_libraries(aiope-inference
    llama
    common
    android
    log
)
