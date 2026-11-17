#pragma once

#include <atomic>
#include <condition_variable>
#include <mutex>
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
    std::atomic<bool> source_event_barrier_active{false};
    std::atomic<int64_t> source_event_dispatch_inflight{0};
    std::mutex source_event_barrier_mutex;
    std::condition_variable source_event_barrier_condition;
    uint64_t source_event_barrier_request{0};
    uint64_t source_event_barrier_completed{0};
    std::atomic<uint64_t> next_source_event_barrier_request{1};
    int64_t active_playlist_entry_id = 0;
    int64_t active_source_generation = 0;
};
