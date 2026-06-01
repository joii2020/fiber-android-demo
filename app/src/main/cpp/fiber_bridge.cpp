#include <android/log.h>
#include <jni.h>

#include <cstdlib>
#include <mutex>
#include <string>

#include "fiber_ffi.h"

#define LOG_TAG "FiberBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::mutex fiber_mutex;
FiberHandle *fiber_handle = nullptr;

std::string result_message(const char *action, FiberFfiResult result) {
    std::string message;
    if (result.status == FIBER_FFI_STATUS_OK) {
        message = std::string("Fiber ") + action;
    } else {
        message = std::string("Fiber ") + action + " failed (" + std::to_string(result.status) + ")";
        if (result.error_message != nullptr) {
            message += ": ";
            message += result.error_message;
        }
        LOGE("%s", message.c_str());
    }

    if (result.error_message != nullptr) {
        fiber_free_string(result.error_message);
    }
    return message;
}

jstring to_java_string(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fiberdemo_FiberRuntime_nativeStart(
        JNIEnv *env,
        jclass,
        jstring config_path,
        jstring database_prefix,
        jstring log_level) {
    std::lock_guard<std::mutex> lock(fiber_mutex);
    if (fiber_handle != nullptr) {
        return to_java_string(env, "Fiber already running");
    }

    const char *config_path_chars = env->GetStringUTFChars(config_path, nullptr);
    const char *database_prefix_chars = env->GetStringUTFChars(database_prefix, nullptr);
    const char *log_level_chars = env->GetStringUTFChars(log_level, nullptr);

    FiberStartOptions options = {};
    options.config_path = config_path_chars;
    options.database_prefix = database_prefix_chars;
    options.log_level = log_level_chars;
    options.event_callback = nullptr;
    options.event_callback_user_data = nullptr;

    setenv("FIBER_SECRET_KEY_PASSWORD", "fiber-demo-secret-key-password", 0);

    FiberHandle *started_handle = nullptr;
    FiberFfiResult result = fiber_start(&options, &started_handle);
    if (result.status == FIBER_FFI_STATUS_OK) {
        fiber_handle = started_handle;
    }

    env->ReleaseStringUTFChars(config_path, config_path_chars);
    env->ReleaseStringUTFChars(database_prefix, database_prefix_chars);
    env->ReleaseStringUTFChars(log_level, log_level_chars);

    return to_java_string(env, result_message("started", result));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fiberdemo_FiberRuntime_nativeStop(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(fiber_mutex);
    if (fiber_handle == nullptr) {
        return to_java_string(env, "Fiber already stopped");
    }

    FiberHandle *handle_to_stop = fiber_handle;
    fiber_handle = nullptr;

    FiberFfiResult result = fiber_stop(handle_to_stop);
    if (result.status != FIBER_FFI_STATUS_OK) {
        fiber_handle = handle_to_stop;
    }

    return to_java_string(env, result_message("stopped", result));
}
