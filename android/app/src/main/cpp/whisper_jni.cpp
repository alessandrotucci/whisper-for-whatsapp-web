// Ponte JNI tra io.github.alessandrotucci.whisperwa.WhisperLib e whisper.cpp.
//
// Un contesto whisper è un puntatore opaco passato a Java come long. Le chiamate su uno
// stesso contesto arrivano sempre dallo stesso thread (la coda di trascrizione), quindi
// qui non c'è sincronizzazione oltre al flag di annullamento.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>

#include "ggml-backend.h"
#include "whisper.h"

#define TAG "whisperwa"

namespace {

std::once_flag backends_once;
std::atomic<bool> cancel_requested{false};

void log_cb(enum ggml_log_level level, const char * text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, TAG, text);
}

struct progress_ctx {
    JNIEnv * env;
    jobject listener;
    jmethodID method;
};

void progress_cb(struct whisper_context *, struct whisper_state *, int progress, void * user_data) {
    auto * p = static_cast<progress_ctx *>(user_data);
    if (p->listener != nullptr) {
        p->env->CallVoidMethod(p->listener, p->method, (jint) progress);
        if (p->env->ExceptionCheck()) p->env->ExceptionClear();
    }
}

bool abort_cb(void *) {
    return cancel_requested.load();
}

std::string to_std(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_io_github_alessandrotucci_whisperwa_WhisperLib_nativeLoadBackends(JNIEnv * env, jclass, jstring lib_dir) {
    std::string dir = to_std(env, lib_dir);
    std::call_once(backends_once, [&dir]() {
        whisper_log_set(log_cb, nullptr);
        ggml_log_set(log_cb, nullptr);
        ggml_backend_load_all_from_path(dir.c_str());
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_alessandrotucci_whisperwa_WhisperLib_nativeInit(JNIEnv * env, jclass, jstring model_path) {
    std::string path = to_std(env, model_path);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    return (jlong) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_alessandrotucci_whisperwa_WhisperLib_nativeFree(JNIEnv *, jclass, jlong ctx) {
    if (ctx != 0) whisper_free((struct whisper_context *) ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_alessandrotucci_whisperwa_WhisperLib_nativeCancel(JNIEnv *, jclass) {
    cancel_requested.store(true);
}

// Restituisce i byte UTF-8 della lingua riconosciuta, di un a capo e del testo, oppure null se
// whisper_full fallisce o viene annullato. I byte, e non una jstring, perché NewStringUTF accetta solo UTF-8 modificato e il
// testo può contenere caratteri fuori dal piano base o sequenze troncate.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_alessandrotucci_whisperwa_WhisperLib_nativeTranscribe(
        JNIEnv * env, jclass, jlong ctx_ptr, jfloatArray samples, jstring language,
        jint threads, jobject listener) {
    auto * ctx = (struct whisper_context *) ctx_ptr;
    std::string lang = to_std(env, language);
    if (lang.empty()) lang = "auto";

    progress_ctx progress{env, listener, nullptr};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        progress.method = env->GetMethodID(cls, "onProgress", "(I)V");
        if (progress.method == nullptr) {
            env->ExceptionClear();
            progress.listener = nullptr;
        }
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.language = lang.c_str();
    params.translate = false;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.progress_callback = progress_cb;
    params.progress_callback_user_data = &progress;
    params.abort_callback = abort_cb;
    params.abort_callback_user_data = nullptr;

    cancel_requested.store(false);
    jsize n = env->GetArrayLength(samples);
    jfloat * data = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(ctx, params, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc != 0 || cancel_requested.load()) return nullptr;
    whisper_print_timings(ctx);

    std::string text;
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        text += whisper_full_get_segment_text(ctx, i);
    }
    const char * detected = whisper_lang_str(whisper_full_lang_id(ctx));
    std::string payload = std::string(detected != nullptr ? detected : "") + '\n' + text;

    jbyteArray out = env->NewByteArray((jsize) payload.size());
    env->SetByteArrayRegion(out, 0, (jsize) payload.size(), reinterpret_cast<const jbyte *>(payload.data()));
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_alessandrotucci_whisperwa_WhisperLib_nativeSystemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}
