// JNI bridge between LlamaEngine.kt and the story code (story.cpp, issue #51).
#include <android/log.h>
#include <jni.h>

#include <exception>
#include <string>

#include "ggml-backend.h"
#include "story.h"

#define TAG "LlamaEngine"

namespace {

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::string to_string(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

void throw_io(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message);
}

// Java strings are modified UTF-8: hand over bytes and let Kotlin decode them.
jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray array = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(array, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return array;
}

story::Engine *engine_of(jlong handle) { return reinterpret_cast<story::Engine *>(handle); }

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeInit(JNIEnv *env, jclass, jstring native_lib_dir) {
    llama_log_set(log_callback, nullptr);
    // The CPU backend comes in variants for different ARM cores; this loads the best one.
    ggml_backend_load_all_from_path(to_string(env, native_lib_dir).c_str());
    llama_backend_init();
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", llama_print_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeLoad(JNIEnv *env, jclass, jstring path, jint n_ctx, jint n_threads) {
    try {
        return reinterpret_cast<jlong>(story::load(to_string(env, path), n_ctx, n_threads));
    } catch (const std::exception &e) {
        throw_io(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeGenerate(
        JNIEnv *env, jclass, jlong handle, jstring system, jstring user, jint max_tokens, jfloat temperature, jint seed) {
    try {
        const std::string out = story::generate(engine_of(handle), to_string(env, system), to_string(env, user),
                                                max_tokens, temperature, (uint32_t) seed);
        const story::Stats &s = engine_of(handle)->stats;
        __android_log_print(ANDROID_LOG_INFO, TAG, "prompt %.0f tokens in %.1f s, story %.0f tokens in %.1f s",
                            s.prompt_tokens, s.prompt_seconds, s.story_tokens, s.story_seconds);
        return to_bytes(env, out);
    } catch (const std::exception &e) {
        throw_io(env, e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeStats(JNIEnv *env, jclass, jlong handle) {
    const story::Stats &s = engine_of(handle)->stats;
    const double values[] = {s.prompt_tokens, s.prompt_seconds, s.story_tokens, s.story_seconds};
    jdoubleArray array = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(array, 0, 4, values);
    return array;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeCancel(JNIEnv *, jclass, jlong handle) {
    engine_of(handle)->cancel = true;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_atrejoe_travelguide_story_LlamaEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    story::free(engine_of(handle));
}
