#include "story.h"

#include <stdexcept>
#include <vector>

namespace story {

namespace {

// Formats the conversation with the model's own chat template; empty when it has none we know.
std::string apply_template(const llama_model *model, const std::string &system, const std::string &user) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr) return "";
    llama_chat_message messages[] = {{"system", system.c_str()}, {"user", user.c_str()}};
    std::vector<char> buf(system.size() + user.size() + 1024);
    int n = llama_chat_apply_template(tmpl, messages, 2, true, buf.data(), (int32_t) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, messages, 2, true, buf.data(), (int32_t) buf.size());
    }
    return n < 0 ? "" : std::string(buf.data(), n);
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    // A negative count means the buffer was too small and tells how many tokens there are.
    int n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    if (n < 0) n = -n;
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, true, true) < 0) {
        throw std::runtime_error("Could not read the prompt");
    }
    return tokens;
}

}  // namespace

Engine *load(const std::string &path, int n_ctx, int n_threads) {
    llama_model_params model_params = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) throw std::runtime_error("Could not load the model");

    auto *engine = new Engine();
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = n_ctx;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.abort_callback = [](void *data) { return static_cast<Engine *>(data)->cancel.load(); };
    ctx_params.abort_callback_data = engine;
    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        delete engine;
        llama_model_free(model);
        throw std::runtime_error("Not enough memory for the model");
    }
    engine->model = model;
    engine->ctx = ctx;
    return engine;
}

std::string generate(Engine *engine, const std::string &system, const std::string &user, int max_tokens,
                     float temperature, uint32_t seed) {
    engine->cancel = false;
    engine->stats = Stats();
    const llama_vocab *vocab = llama_model_get_vocab(engine->model);
    llama_memory_clear(llama_get_memory(engine->ctx), true);

    std::string prompt = apply_template(engine->model, system, user);
    if (prompt.empty()) prompt = system + "\n\n" + user + "\n\n";
    std::vector<llama_token> tokens = tokenize(vocab, prompt);
    const int n_prompt = (int) tokens.size();
    if (n_prompt == 0) throw std::runtime_error("Empty prompt");
    if (n_prompt + max_tokens > (int) llama_n_ctx(engine->ctx)) {
        throw std::runtime_error("The story is too long for the model (" + std::to_string(n_prompt) + " tokens)");
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(seed));

    std::string out;
    const int64_t t_start = ggml_time_us();
    int64_t t_prompt_done = 0;
    int generated = 0;
    llama_token token = 0;
    llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);
    while (generated < max_tokens) {
        const int ret = llama_decode(engine->ctx, batch);
        if (ret != 0) {
            llama_sampler_free(sampler);
            throw std::runtime_error(engine->cancel ? "Cancelled" : "The model failed (" + std::to_string(ret) + ")");
        }
        if (t_prompt_done == 0) t_prompt_done = ggml_time_us();
        token = llama_sampler_sample(sampler, engine->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char buf[256];
        const int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, n);
        generated++;
        batch = llama_batch_get_one(&token, 1);
    }
    const int64_t t_end = ggml_time_us();
    llama_sampler_free(sampler);

    engine->stats.prompt_tokens = n_prompt;
    engine->stats.prompt_seconds = (t_prompt_done - t_start) / 1e6;
    engine->stats.story_tokens = generated;
    engine->stats.story_seconds = (t_end - t_prompt_done) / 1e6;
    return out;
}

void free(Engine *engine) {
    llama_free(engine->ctx);
    llama_model_free(engine->model);
    delete engine;
}

}  // namespace story
