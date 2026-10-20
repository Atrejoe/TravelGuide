// JNI bridge between LlamaEngine.kt and llama.cpp's C API (issue #51): load a GGUF model, retell
// one story (system + user message, no chat history), and report how fast that went.
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define TAG "LlamaEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::atomic<bool> cancel{false};
    // Timings of the last generate(): prompt tokens, prompt seconds, generated tokens, generation seconds.
    double stats[4] = {0, 0, 0, 0};
};

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::string to_string(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

void throw_io(JNIEnv *env, const std::string &message) {
    jclass cls = env->FindClass("java/io/IOException");
    env->ThrowNew(cls, message.c_str());
}

// Java strings are modified UTF-8: hand over bytes and let Kotlin decode them.
jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray array = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(array, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return array;
}

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

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeInit(JNIEnv *env, jclass, jstring native_lib_dir) {
    llama_log_set(log_callback, nullptr);
    // The CPU backend comes in variants for different ARM cores; this loads the best one.
    ggml_backend_load_all_from_path(to_string(env, native_lib_dir).c_str());
    llama_backend_init();
    LOGI("%s", llama_print_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeLoad(JNIEnv *env, jclass, jstring path, jint n_ctx, jint n_threads) {
    llama_model_params model_params = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(to_string(env, path).c_str(), model_params);
    if (model == nullptr) {
        throw_io(env, "Could not load the model");
        return 0;
    }
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = n_ctx;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.abort_callback = [](void *data) { return static_cast<Engine *>(data)->cancel.load(); };
    auto *engine = new Engine();
    ctx_params.abort_callback_data = engine;
    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        delete engine;
        llama_model_free(model);
        throw_io(env, "Not enough memory for the model");
        return 0;
    }
    engine->model = model;
    engine->ctx = ctx;
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeGenerate(
        JNIEnv *env, jclass, jlong handle, jstring jsystem, jstring juser, jint max_tokens, jfloat temperature, jint seed) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    engine->cancel = false;
    std::fill(std::begin(engine->stats), std::end(engine->stats), 0.0);
    const llama_vocab *vocab = llama_model_get_vocab(engine->model);
    llama_memory_clear(llama_get_memory(engine->ctx), true);

    const std::string system = to_string(env, jsystem);
    const std::string user = to_string(env, juser);
    std::string prompt = apply_template(engine->model, system, user);
    if (prompt.empty()) prompt = system + "\n\n" + user + "\n\n";

    const int n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n_prompt, true, true) < 0) {
        throw_io(env, "Could not read the prompt");
        return nullptr;
    }
    const int n_ctx = (int) llama_n_ctx(engine->ctx);
    if (n_prompt + max_tokens > n_ctx) {
        throw_io(env, "The story is too long for the model (" + std::to_string(n_prompt) + " tokens)");
        return nullptr;
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist((uint32_t) seed));

    std::string out;
    const int64_t t_start = ggml_time_us();
    int64_t t_prompt_done = 0;
    int generated = 0;
    llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);
    llama_token token;
    while (generated < max_tokens) {
        int ret = llama_decode(engine->ctx, batch);
        if (ret != 0) {
            llama_sampler_free(sampler);
            throw_io(env, engine->cancel ? "Cancelled" : "The model failed (" + std::to_string(ret) + ")");
            return nullptr;
        }
        if (t_prompt_done == 0) t_prompt_done = ggml_time_us();
        token = llama_sampler_sample(sampler, engine->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char buf[256];
        int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, n);
        generated++;
        batch = llama_batch_get_one(&token, 1);
    }
    const int64_t t_end = ggml_time_us();
    llama_sampler_free(sampler);

    engine->stats[0] = n_prompt;
    engine->stats[1] = (t_prompt_done - t_start) / 1e6;
    engine->stats[2] = generated;
    engine->stats[3] = (t_end - t_prompt_done) / 1e6;
    LOGI("prompt %d tokens in %.1f s, story %d tokens in %.1f s",
         n_prompt, engine->stats[1], generated, engine->stats[3]);
    return to_bytes(env, out);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeStats(JNIEnv *env, jclass, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    jdoubleArray array = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(array, 0, 4, engine->stats);
    return array;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeCancel(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<Engine *>(handle)->cancel = true;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    llama_free(engine->ctx);
    llama_model_free(engine->model);
    delete engine;
}
