// Adapted from Galaxy XR ALVR Research, MIT; see LICENSE.
// Output buffers are leased until pyroclient_release, never reused while held.
#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct AHardwareBuffer AHardwareBuffer;
typedef struct pyroclient pyroclient;
typedef struct pyroclient_frame_info { double decode_ms, convert_ms, total_ms; int complete; } pyroclient_frame_info;
int pyroclient_probe(void);
pyroclient *pyroclient_create(uint32_t width,uint32_t height,int chroma444,int full_range,uint32_t ring_size);
int pyroclient_push_packet(pyroclient *,const void *,size_t);
int pyroclient_is_ready(pyroclient *,int);
int pyroclient_decode(pyroclient *,AHardwareBuffer **,pyroclient_frame_info *);
void pyroclient_release(pyroclient *,AHardwareBuffer *);
void pyroclient_clear(pyroclient *);
void pyroclient_destroy(pyroclient *);
#ifdef __cplusplus
}
#endif
