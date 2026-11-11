#pragma once

struct MPVInstance;

void *event_thread(void *arg);
void begin_source_event_barrier(MPVInstance *instance);
void end_source_event_barrier(MPVInstance *instance);
bool complete_source_event_barrier(MPVInstance *instance, uint64_t reply_userdata);
