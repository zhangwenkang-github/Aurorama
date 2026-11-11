#include <jni.h>

#include <mpv/client.h>

#include "globals.h"
#include "jni_utils.h"
#include "log.h"


static void sendPropertyUpdateToJava(JNIEnv *env, MPVInstance* instance, mpv_event_property *prop) {
    jstring jprop = env->NewStringUTF(prop->name);
    jstring jvalue = nullptr;
    switch (prop->format) {
    case MPV_FORMAT_NONE:
        env->CallVoidMethod(instance->javaObject, mpv_MPVLib_eventProperty_S, jprop);
        break;
    case MPV_FORMAT_FLAG:
        env->CallVoidMethod(instance->javaObject, mpv_MPVLib_eventProperty_Sb, jprop, *(int*)prop->data);
        break;
    case MPV_FORMAT_INT64:
        env->CallVoidMethod(instance->javaObject, mpv_MPVLib_eventProperty_Sl, jprop, *(int64_t*)prop->data);
        break;
    case MPV_FORMAT_DOUBLE:
        env->CallVoidMethod(instance->javaObject, mpv_MPVLib_eventProperty_Sd, jprop, *(double*)prop->data);
        break;
    case MPV_FORMAT_STRING:
        jvalue = env->NewStringUTF(*(const char**)prop->data);
        env->CallVoidMethod(instance->javaObject, mpv_MPVLib_eventProperty_SS, jprop, jvalue);
        break;
    default:
        ALOGV("sendPropertyUpdateToJava: Unknown property update format received in callback: %d!", prop->format);
        break;
    }
    if (jprop)
        env->DeleteLocalRef(jprop);
    if (jvalue)
        env->DeleteLocalRef(jvalue);
}

static void sendEventToJava(JNIEnv *env, MPVInstance* instance, int event) {
    env->CallVoidMethod(instance->javaObject, mpv_MPVLib_event, event);
}

static void sendEventWithIdentityToJava(
        JNIEnv *env,
        MPVInstance* instance,
        int event,
        int64_t playlist_entry_id,
        int64_t native_generation) {
    env->CallVoidMethod(
        instance->javaObject,
        mpv_MPVLib_eventWithIdentity,
        event,
        playlist_entry_id,
        native_generation);
}

static void sendLogMessageToJava(JNIEnv *env, MPVInstance* instance, mpv_event_log_message *msg) {
    // filter the most obvious cases of invalid utf-8, since Java would choke on it
    const auto invalid_utf8 = [] (unsigned char c) {
        return c == 0xc0 || c == 0xc1 || c >= 0xf5;
    };
    for (int i = 0; msg->text[i]; i++) {
        if (invalid_utf8(static_cast<unsigned char>(msg->text[i])))
            return;
    }

    jstring jprefix = env->NewStringUTF(msg->prefix);
    jstring jtext = env->NewStringUTF(msg->text);

    env->CallVoidMethod(instance->javaObject, mpv_MPVLib_logMessage_SiS,
        jprefix, (jint) msg->log_level, jtext);

    if (jprefix)
        env->DeleteLocalRef(jprefix);
    if (jtext)
        env->DeleteLocalRef(jtext);
}

void *event_thread(void *arg) {
    auto instance = static_cast<MPVInstance*>(arg);
    JNIEnv *env = nullptr;
    acquire_jni_env(instance->vm, &env);
    if (!env) {
        ALOGE("failed to acquire java env");
        return nullptr;
    }

    while (true) {
        mpv_event *mp_event;
        mpv_event_property *mp_property;
        mpv_event_log_message *msg;

        mp_event = mpv_wait_event(instance->mpv, -1.0);

        if (instance->event_thread_request_exit)
            break;

        if (mp_event->event_id == MPV_EVENT_NONE)
            continue;

        switch (mp_event->event_id) {
        case MPV_EVENT_LOG_MESSAGE:
            msg = (mpv_event_log_message*)mp_event->data;
            ALOGV("[%s:%s] %s", msg->prefix, msg->level, msg->text);
            sendLogMessageToJava(env, instance, msg);
            break;
        case MPV_EVENT_PROPERTY_CHANGE:
            mp_property = (mpv_event_property*)mp_event->data;
            sendPropertyUpdateToJava(env, instance, mp_property);
            break;
        case MPV_EVENT_START_FILE: {
            auto start_file = static_cast<mpv_event_start_file*>(mp_event->data);
            instance->active_playlist_entry_id = start_file->playlist_entry_id;
            instance->active_source_generation = ++instance->next_native_generation;
            sendEventWithIdentityToJava(
                env,
                instance,
                mp_event->event_id,
                instance->active_playlist_entry_id,
                instance->active_source_generation);
            break;
        }
        case MPV_EVENT_FILE_LOADED:
            sendEventWithIdentityToJava(
                env,
                instance,
                mp_event->event_id,
                instance->active_playlist_entry_id,
                instance->active_source_generation);
            break;
        case MPV_EVENT_END_FILE: {
            auto end_file = static_cast<mpv_event_end_file*>(mp_event->data);
            const bool active = end_file->playlist_entry_id == instance->active_playlist_entry_id;
            sendEventWithIdentityToJava(
                env,
                instance,
                mp_event->event_id,
                end_file->playlist_entry_id,
                active ? instance->active_source_generation : 0);
            if (active) {
                instance->active_playlist_entry_id = 0;
                instance->active_source_generation = 0;
            }
            break;
        }
        default:
            ALOGV("event: %s\n", mpv_event_name(mp_event->event_id));
            sendEventToJava(env, instance, mp_event->event_id);
            break;
        }
    }

    instance->vm->DetachCurrentThread();

    return nullptr;
}
