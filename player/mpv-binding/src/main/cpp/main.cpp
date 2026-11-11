#include <jni.h>
#include <cstdlib>
#include <cstdio>
#include <ctime>
#include <clocale>
#include <atomic>
#include <cstring>

#include <mpv/client.h>

#include <pthread.h>

extern "C" {
    int av_jni_set_java_vm(void *vm, void *log_ctx);
    int av_jni_set_android_app_ctx(void *app_ctx, void *log_ctx);
}

#include "log.h"
#include "jni_utils.h"
#include "event.h"
#include "globals.h"

#define ARRAYLEN(a) (sizeof(a)/sizeof(a[0]))

// Update the JNI functions to accept a jlong parameter, which will be a
// pointer to the MPVInstance
extern "C" {
    jni_func(jlong, nativeCreate, jobject thiz, jobject appctx);
    jni_func(void, nativeInit, jlong instance);
    jni_func(void, nativeDestroy, jlong instance);
    jni_func(void, nativeCommand, jlong instance, jobjectArray jarray);
    jni_func(jlongArray, nativeCommandForSource, jlong instance, jobjectArray jarray);
    jni_func(jlong, nativeGetNativeGeneration, jlong instance);
};

static void prepare_environment(JNIEnv *env, MPVInstance* instance) {
    setlocale(LC_NUMERIC, "C");

    if (env->GetJavaVM(&instance->vm) == JNI_OK && instance->vm)
        av_jni_set_java_vm(instance->vm, nullptr);

    if (instance->appCtx)
        av_jni_set_android_app_ctx(instance->appCtx, nullptr);

    init_methods_cache(env);
}

jni_func(jlong, nativeCreate, jobject thiz, jobject appctx) {
    auto instance = new MPVInstance();
    instance->event_thread_id = 0;
    instance->event_thread_request_exit = false;
    instance->javaObject = env->NewGlobalRef(thiz);
    instance->appCtx = env->NewGlobalRef(appctx);
    prepare_environment(env, instance);

    instance->mpv = mpv_create();
    if (!instance->mpv) {
        delete instance;
        return 0;
    }

    mpv_request_log_messages(instance->mpv, "v");
    return reinterpret_cast<jlong>(instance);
}

jni_func(void, nativeInit, jlong instance) {
    auto mpv_instance = reinterpret_cast<MPVInstance*>(instance);
    if (!mpv_instance->mpv) {
        die(env, "mpv is not created");
        return;
    }

    if (mpv_initialize(mpv_instance->mpv) < 0) {
        die(env, "mpv init failed");
        return;
    }

    mpv_instance->event_thread_request_exit = false;
    if (pthread_create(&mpv_instance->event_thread_id, nullptr, event_thread, mpv_instance) != 0) {
        die(env, "thread create failed");
        return;
    }
    pthread_setname_np(mpv_instance->event_thread_id, "event_thread");
}

jni_func(void, nativeDestroy, jlong instance) {
    auto mpv_instance = reinterpret_cast<MPVInstance*>(instance);
    if (!mpv_instance->mpv) {
        ALOGV("Cannot destroy mpv: mpv is not initialized");
        return;
    }

    mpv_instance->event_thread_request_exit = true;
    mpv_wakeup(mpv_instance->mpv);

    if (mpv_instance->event_thread_id != 0) {
        pthread_join(mpv_instance->event_thread_id, nullptr);
        mpv_instance->event_thread_id = 0;
    }

    mpv_terminate_destroy(mpv_instance->mpv);
    if (mpv_instance->surface) {
        env->DeleteGlobalRef(mpv_instance->surface);
        mpv_instance->surface = nullptr;
    }
    env->DeleteGlobalRef(mpv_instance->appCtx);
    env->DeleteGlobalRef(mpv_instance->javaObject);
    delete mpv_instance;
}

jni_func(void, nativeCommand, jlong instance, jobjectArray jarray) {
    auto mpv_instance = reinterpret_cast<MPVInstance*>(instance);
    if (!mpv_instance->mpv) {
        ALOGE("Cannot run command: mpv is not initialized");
        return;
    }

    const char *arguments[128] = { nullptr };
    jstring stringRefs[128] = { nullptr };

    int len = env->GetArrayLength(jarray);

    if (len >= ARRAYLEN(arguments)) {
        ALOGE("Cannot run command: too many arguments");
        return;
    }

    for (int i = 0; i < len; ++i) {
        stringRefs[i] = (jstring)env->GetObjectArrayElement(jarray, i);
        arguments[i] = env->GetStringUTFChars(stringRefs[i], nullptr);
    }

    mpv_command(mpv_instance->mpv, arguments);

    for (int i = 0; i < len; ++i) {
        if (stringRefs[i]) {
            env->ReleaseStringUTFChars(stringRefs[i], arguments[i]);
            env->DeleteLocalRef(stringRefs[i]);
        }
    }
}

static int64_t playlist_entry_id_from_result(mpv_node *result) {
    if (!result || result->format != MPV_FORMAT_NODE_MAP)
        return -1;
    for (int i = 0; i < result->u.list->num; ++i) {
        const char *key = result->u.list->keys[i];
        mpv_node *value = &result->u.list->values[i];
        if (key && strcmp(key, "playlist_entry_id") == 0 &&
            value->format == MPV_FORMAT_INT64) {
            return value->u.int64;
        }
    }
    return -1;
}

static int64_t current_playlist_entry_id(mpv_handle *mpv) {
    int64_t current_position = 0;
    if (mpv_get_property(
            mpv, "playlist-current-pos", MPV_FORMAT_INT64, &current_position) < 0) {
        return -1;
    }
    char property[64] = { 0 };
    snprintf(property, sizeof(property), "playlist/%lld/id",
             static_cast<long long>(current_position));
    int64_t playlist_entry_id = 0;
    if (mpv_get_property(
            mpv, property, MPV_FORMAT_INT64, &playlist_entry_id) < 0) {
        return -1;
    }
    return playlist_entry_id;
}

jni_func(jlongArray, nativeCommandForSource, jlong instance, jobjectArray jarray) {
    auto mpv_instance = reinterpret_cast<MPVInstance*>(instance);
    if (!mpv_instance->mpv)
        return nullptr;

    const char *arguments[128] = { nullptr };
    jstring stringRefs[128] = { nullptr };
    const int len = env->GetArrayLength(jarray);
    if (len >= ARRAYLEN(arguments))
        return nullptr;

    for (int i = 0; i < len; ++i) {
        stringRefs[i] = static_cast<jstring>(env->GetObjectArrayElement(jarray, i));
        arguments[i] = env->GetStringUTFChars(stringRefs[i], nullptr);
    }

    mpv_node result = { 0 };
    const int command_result = mpv_command_ret(mpv_instance->mpv, arguments, &result);
    int64_t playlist_entry_id =
        command_result < 0 ? -1 : playlist_entry_id_from_result(&result);

    if (command_result >= 0)
        mpv_free_node_contents(&result);
    if (playlist_entry_id <= 0 && command_result >= 0)
        playlist_entry_id = current_playlist_entry_id(mpv_instance->mpv);
    const int64_t native_generation_after_command =
        mpv_instance->next_native_generation.load();
    for (int i = 0; i < len; ++i) {
        if (stringRefs[i]) {
            env->ReleaseStringUTFChars(stringRefs[i], arguments[i]);
            env->DeleteLocalRef(stringRefs[i]);
        }
    }
    const jlong values[2] = {
        static_cast<jlong>(playlist_entry_id),
        static_cast<jlong>(native_generation_after_command),
    };
    jlongArray command_result_array = env->NewLongArray(2);
    if (!command_result_array)
        return nullptr;
    env->SetLongArrayRegion(command_result_array, 0, 2, values);
    return command_result_array;
}

jni_func(jlong, nativeGetNativeGeneration, jlong instance) {
    auto mpv_instance = reinterpret_cast<MPVInstance*>(instance);
    return mpv_instance->next_native_generation.load();
}
