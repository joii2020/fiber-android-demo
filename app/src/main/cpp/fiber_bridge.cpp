#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>

#include "fiber_ffi.h"

#define LOG_TAG "FiberBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::mutex fiber_mutex;
FiberHandle *fiber_handle = nullptr;
JavaVM *java_vm = nullptr;
jclass fiber_runtime_class = nullptr;

using FiberListChannelsFn = FiberFfiStatus (*)(FiberHandle *, const char *, char **);
using FiberOpenChannelFn = FiberFfiStatus (*)(FiberHandle *, const char *, char **);
using FiberShutdownChannelFn = FiberFfiStatus (*)(FiberHandle *, const char *);
using FiberNewInvoiceFn = FiberFfiStatus (*)(FiberHandle *, const char *, char **);
using FiberSendPaymentFn = FiberFfiStatus (*)(FiberHandle *, const char *, char **);

std::string last_error_message() {
    size_t required = fiber_last_error_message(nullptr, 0);
    if (required == 0) {
        return "";
    }

    std::vector<char> buffer(required + 1);
    size_t written = fiber_last_error_message(buffer.data(), buffer.size());
    if (written == 0) {
        return "";
    }
    return std::string(buffer.data());
}

std::string result_message(const char *action, FiberFfiStatus status) {
    std::string message;
    if (status == FIBER_FFI_STATUS_OK) {
        message = std::string("Fiber ") + action;
    } else {
        message = std::string("Fiber ") + action + " failed (" + std::to_string(status) + ")";
        std::string error = last_error_message();
        if (!error.empty()) {
            message += ": ";
            message += error;
        }
        LOGE("%s", message.c_str());
    }
    return message;
}

jstring to_java_string(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

std::string prefixed_result(FiberFfiStatus status, const std::string &value, const char *action) {
    if (status == FIBER_FFI_STATUS_OK) {
        return "OK\n" + value;
    }
    return "ERROR\n" + result_message(action, status);
}

template<typename Fn>
Fn load_fiber_symbol(const char *name) {
    return reinterpret_cast<Fn>(dlsym(RTLD_DEFAULT, name));
}

void emit_native_event(const char *event_json, void *) {
    if (java_vm == nullptr || fiber_runtime_class == nullptr || event_json == nullptr) {
        return;
    }

    JNIEnv *env = nullptr;
    bool attached = false;
    jint env_result = java_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (env_result == JNI_EDETACHED) {
        if (java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    } else if (env_result != JNI_OK) {
        return;
    }

    jmethodID method = env->GetStaticMethodID(
            fiber_runtime_class,
            "onNativeEvent",
            "(Ljava/lang/String;)V"
    );
    if (method != nullptr) {
        jstring event = env->NewStringUTF(event_json);
        env->CallStaticVoidMethod(fiber_runtime_class, method, event);
        env->DeleteLocalRef(event);
    }

    if (attached) {
        java_vm->DetachCurrentThread();
    }
}
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    java_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass local_class = env->FindClass("com/example/fiberdemo/FiberRuntime");
    if (local_class == nullptr) {
        return JNI_ERR;
    }
    fiber_runtime_class = reinterpret_cast<jclass>(env->NewGlobalRef(local_class));
    env->DeleteLocalRef(local_class);
    return JNI_VERSION_1_6;
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
    options.event_callback = emit_native_event;
    options.event_callback_user_data = nullptr;

    setenv("FIBER_SECRET_KEY_PASSWORD", "fiber-demo-secret-key-password", 0);

    FiberHandle *started_handle = nullptr;
    FiberFfiStatus result = fiber_start(&options, &started_handle);
    if (result == FIBER_FFI_STATUS_OK) {
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

    FiberFfiStatus result = fiber_stop(handle_to_stop);
    return to_java_string(env, result_message("stopped", result));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fiberdemo_FiberRuntime_nativeNodeInfo(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(fiber_mutex);
    if (fiber_handle == nullptr) {
        return to_java_string(env, "ERROR\nFiber nodeInfo failed: node is not running");
    }

    char *json = nullptr;
    FiberFfiStatus result = fiber_node_info(fiber_handle, &json);
    std::string value;
    if (result == FIBER_FFI_STATUS_OK && json != nullptr) {
        value = json;
        fiber_string_free(json);
    }
    return to_java_string(env, prefixed_result(result, value, "nodeInfo"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fiberdemo_FiberRuntime_nativeListPeers(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(fiber_mutex);
    if (fiber_handle == nullptr) {
        return to_java_string(env, "ERROR\nFiber listPeers failed: node is not running");
    }

    char *json = nullptr;
    FiberFfiStatus result = fiber_list_peers(fiber_handle, &json);
    std::string value;
    if (result == FIBER_FFI_STATUS_OK && json != nullptr) {
        value = json;
        fiber_string_free(json);
    }
    return to_java_string(env, prefixed_result(result, value, "listPeers"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fiberdemo_FiberRuntime_nativeConnectPeer(
        JNIEnv *env,
        jclass,
        jstring address,
        jstring pubkey,
        jstring addr_type,
        jboolean save) {
    std::lock_guard<std::mutex> lock(fiber_mutex);
    if (fiber_handle == nullptr) {
        return to_java_string(env, "ERROR\nFiber connectPeer failed: node is not running");
    }

    const char *address_chars = address == nullptr ? nullptr : env->GetStringUTFChars(address, nullptr);
    const char *pubkey_chars = pubkey == nullptr ? nullptr : env->GetStringUTFChars(pubkey, nullptr);
    const char *addr_type_chars = addr_type == nullptr ? nullptr : env->GetStringUTFChars(addr_type, nullptr);

    FiberConnectPeerOptions options = {};
    options.address = address_chars;
    options.pubkey = pubkey_chars;
    options.addr_type = addr_type_chars;
    options.save = save ? 1 : 0;

    FiberFfiStatus result = fiber_connect_peer(fiber_handle, &options);

    if (address_chars != nullptr) {
        env->ReleaseStringUTFChars(address, address_chars);
    }
    if (pubkey_chars != nullptr) {
        env->ReleaseStringUTFChars(pubkey, pubkey_chars);
    }
    if (addr_type_chars != nullptr) {
        env->ReleaseStringUTFChars(addr_type, addr_type_chars);
    }

    return to_java_string(env, prefixed_result(result, "Fiber peer connected", "connectPeer"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fiberdemo_FiberRuntime_nativeListChannels(
        JNIEnv *env,
        jclass,
        jstring params_json) {
    std::lock_guard<std::mutex> lock(fiber_mutex);
    if (fiber_handle == nullptr) {
        return to_java_string(env, "ERROR\nFiber listChannels failed: node is not running");
    }

    FiberListChannelsFn list_channels = load_fiber_symbol<FiberListChannelsFn>("fiber_list_channels");
    if (list_channels == nullptr) {
        return to_java_string(env, "ERROR\nFiber listChannels failed: fiber_ffi does not export fiber_list_channels");
    }

    const char *params_json_chars = env->GetStringUTFChars(params_json, nullptr);
    char *json = nullptr;
    FiberFfiStatus result = list_channels(fiber_handle, params_json_chars, &json);
    std::string value;
    if (result == FIBER_FFI_STATUS_OK && json != nullptr) {
        value = json;
        fiber_string_free(json);
    }
    env->ReleaseStringUTFChars(params_json, params_json_chars);
    return to_java_string(env, prefixed_result(result, value, "listChannels"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fiberdemo_FiberRuntime_nativeOpenChannel(
        JNIEnv *env,
        jclass,
        jstring params_json) {
    std::lock_guard<std::mutex> lock(fiber_mutex);
    if (fiber_handle == nullptr) {
        return to_java_string(env, "ERROR\nFiber createChannel failed: node is not running");
    }

    FiberOpenChannelFn open_channel = load_fiber_symbol<FiberOpenChannelFn>("fiber_open_channel");
    if (open_channel == nullptr) {
        return to_java_string(env, "ERROR\nFiber createChannel failed: fiber_ffi does not export fiber_open_channel");
    }

    const char *params_json_chars = env->GetStringUTFChars(params_json, nullptr);
    char *json = nullptr;
    FiberFfiStatus result = open_channel(fiber_handle, params_json_chars, &json);
    std::string value;
    if (result == FIBER_FFI_STATUS_OK && json != nullptr) {
        value = json;
        fiber_string_free(json);
    }
    env->ReleaseStringUTFChars(params_json, params_json_chars);
    return to_java_string(env, prefixed_result(result, value, "createChannel"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fiberdemo_FiberRuntime_nativeShutdownChannel(
        JNIEnv *env,
        jclass,
        jstring params_json) {
    std::lock_guard<std::mutex> lock(fiber_mutex);
    if (fiber_handle == nullptr) {
        return to_java_string(env, "ERROR\nFiber shutdownChannel failed: node is not running");
    }

    FiberShutdownChannelFn shutdown_channel =
            load_fiber_symbol<FiberShutdownChannelFn>("fiber_shutdown_channel");
    if (shutdown_channel == nullptr) {
        return to_java_string(env,
                              "ERROR\nFiber shutdownChannel failed: fiber_ffi does not export fiber_shutdown_channel");
    }

    const char *params_json_chars = env->GetStringUTFChars(params_json, nullptr);
    FiberFfiStatus result = shutdown_channel(fiber_handle, params_json_chars);
    env->ReleaseStringUTFChars(params_json, params_json_chars);
    return to_java_string(env, prefixed_result(result, "Fiber channel shutdown requested", "shutdownChannel"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fiberdemo_FiberRuntime_nativeNewInvoice(
        JNIEnv *env,
        jclass,
        jstring params_json) {
    std::lock_guard<std::mutex> lock(fiber_mutex);
    if (fiber_handle == nullptr) {
        return to_java_string(env, "ERROR\nFiber newInvoice failed: node is not running");
    }

    FiberNewInvoiceFn new_invoice = load_fiber_symbol<FiberNewInvoiceFn>("fiber_new_invoice");
    if (new_invoice == nullptr) {
        return to_java_string(env, "ERROR\nFiber newInvoice failed: fiber_ffi does not export fiber_new_invoice");
    }

    const char *params_json_chars = env->GetStringUTFChars(params_json, nullptr);
    char *json = nullptr;
    FiberFfiStatus result = new_invoice(fiber_handle, params_json_chars, &json);
    std::string value;
    if (result == FIBER_FFI_STATUS_OK && json != nullptr) {
        value = json;
        fiber_string_free(json);
    }
    env->ReleaseStringUTFChars(params_json, params_json_chars);
    return to_java_string(env, prefixed_result(result, value, "newInvoice"));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_fiberdemo_FiberRuntime_nativeSendPayment(
        JNIEnv *env,
        jclass,
        jstring params_json) {
    std::lock_guard<std::mutex> lock(fiber_mutex);
    if (fiber_handle == nullptr) {
        return to_java_string(env, "ERROR\nFiber sendPayment failed: node is not running");
    }

    FiberSendPaymentFn send_payment = load_fiber_symbol<FiberSendPaymentFn>("fiber_send_payment");
    if (send_payment == nullptr) {
        return to_java_string(env, "ERROR\nFiber sendPayment failed: fiber_ffi does not export fiber_send_payment");
    }

    const char *params_json_chars = env->GetStringUTFChars(params_json, nullptr);
    char *json = nullptr;
    FiberFfiStatus result = send_payment(fiber_handle, params_json_chars, &json);
    std::string value;
    if (result == FIBER_FFI_STATUS_OK && json != nullptr) {
        value = json;
        fiber_string_free(json);
    }
    env->ReleaseStringUTFChars(params_json, params_json_chars);
    return to_java_string(env, prefixed_result(result, value, "sendPayment"));
}
