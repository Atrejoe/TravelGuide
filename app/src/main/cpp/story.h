// Retelling one story with llama.cpp (issue #51), free of Android and JNI so that CI can run the
// very same code on Linux (tools/llama-check). Errors are thrown as std::runtime_error, with a
// message for the listener.
#pragma once

#include <atomic>
#include <cstdint>
#include <string>

#include "llama.h"

namespace story {

struct Stats {
    double prompt_tokens = 0;
    double prompt_seconds = 0;
    double story_tokens = 0;
    double story_seconds = 0;
};

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    // Set from another thread to stop a story mid-way.
    std::atomic<bool> cancel{false};
    // Timings of the last generate().
    Stats stats;
};

Engine *load(const std::string &path, int n_ctx, int n_threads);

// One system and one user message, no history: the story, without the template's markup.
std::string generate(Engine *engine, const std::string &system, const std::string &user, int max_tokens,
                     float temperature, uint32_t seed);

void free(Engine *engine);

}  // namespace story
