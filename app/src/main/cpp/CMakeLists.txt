# The on-device storyteller (issue #51): llama.cpp (see llama.cmake) behind a small JNI bridge.
# The story code itself (story.cpp) has no Android dependency, so CI can run it on Linux too.
cmake_minimum_required(VERSION 3.22.1)
project(travelguide_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(${CMAKE_CURRENT_LIST_DIR}/llama.cmake)

add_library(travelguide_llama SHARED llama_jni.cpp story.cpp)
target_link_libraries(travelguide_llama llama ggml android log)
