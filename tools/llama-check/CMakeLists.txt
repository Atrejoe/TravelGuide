# Runs the app's story code (app/src/main/cpp/story.cpp) on Linux with the real models, so CI
# catches crashes and bad output without a phone. See .github/workflows/llama-check.yml.
cmake_minimum_required(VERSION 3.22.1)
project(llama_check LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(APP_CPP ${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp)
include(${APP_CPP}/llama.cmake)

add_executable(llama-check main.cpp ${APP_CPP}/story.cpp)
target_include_directories(llama-check PRIVATE ${APP_CPP})
target_link_libraries(llama-check llama ggml)
