// Retells the app's sample story twice with one model, the way the app does (one model, several
// stories), and prints the timings and the stories. Exits non-zero on any error.
//
// Usage: llama-check MODEL.gguf SYSTEM.txt USER.txt [SUFFIX.txt]
// The prompt files come from core's LocalStoryEnhancerTest (core/build/llama-check).
#include <algorithm>
#include <cstdio>
#include <stdexcept>
#include <exception>
#include <fstream>
#include <sstream>
#include <string>
#include <thread>

#include "story.h"

static std::string read(const char *path) {
    std::ifstream in(path);
    if (!in) throw std::runtime_error(std::string("Cannot read ") + path);
    std::stringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

int main(int argc, char **argv) {
    if (argc < 4) {
        std::fprintf(stderr, "Usage: %s MODEL.gguf SYSTEM.txt USER.txt [SUFFIX.txt]\n", argv[0]);
        return 2;
    }
    try {
        llama_backend_init();
        const std::string system = read(argv[2]);
        const std::string user = read(argv[3]) + (argc > 4 ? read(argv[4]) : "");
        // As in the app: LlamaModel's context size, LocalStoryEnhancer's token limit and sampling.
        const int threads = (int) std::max(2u, std::min(4u, std::thread::hardware_concurrency()));
        story::Engine *engine = story::load(argv[1], 2048, threads);
        for (int i = 1; i <= 2; i++) {
            const std::string out = story::generate(engine, system, user, 320, 0.5f, 42 + i);
            const story::Stats &s = engine->stats;
            std::printf("--- story %d: prompt %.0f tokens in %.1f s (%.1f/s), story %.0f tokens in %.1f s (%.1f/s)\n",
                        i, s.prompt_tokens, s.prompt_seconds, s.prompt_tokens / s.prompt_seconds,
                        s.story_tokens, s.story_seconds, s.story_tokens / s.story_seconds);
            std::printf("%s\n", out.c_str());
            if (out.empty()) throw std::runtime_error("Empty story");
        }
        story::free(engine);
        llama_backend_free();
    } catch (const std::exception &e) {
        std::fprintf(stderr, "error: %s\n", e.what());
        return 1;
    }
    return 0;
}
