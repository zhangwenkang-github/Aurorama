#pragma once

#include <atomic>
#include <pthread.h>
#include <jni.h>
#include <mpv/client.h>

// struct to hold the mpv_handle and other related data
struct MPVInstance {
    mpv_handle *mpv;
    JavaVM *vm;
    pthread_t event_thread_id;
    std::atomic<bool> event_thread_request_exit;

    jobject javaObject;
    jobject appCtx;
    jobject surface;
    std::atomic<int64_t> next_native_generation{0};
    int64_t active_playlist_entry_id = 0;
    int64_t active_source_generation = 0;
};
