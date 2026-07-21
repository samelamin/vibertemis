#include <stdio.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
}

struct CodecRequirement
{
    AVCodecID id;
    const char* name;
};

static bool hasDecoder(AVCodecID codecId)
{
    void* opaque = nullptr;
    const AVCodec* decoder;
    while ((decoder = av_codec_iterate(&opaque)) != nullptr) {
        if (av_codec_is_decoder(decoder) && decoder->id == codecId) {
            return true;
        }
    }
    return false;
}

static bool hasHardwareDevice(AVCodecID codecId, AVHWDeviceType deviceType)
{
    void* opaque = nullptr;
    const AVCodec* decoder;
    while ((decoder = av_codec_iterate(&opaque)) != nullptr) {
        if (!av_codec_is_decoder(decoder) || decoder->id != codecId) {
            continue;
        }

        for (int index = 0; ; ++index) {
            const AVCodecHWConfig* config = avcodec_get_hw_config(decoder, index);
            if (config == nullptr) {
                break;
            }
            if (config->device_type == deviceType) {
                return true;
            }
        }
    }
    return false;
}

int main()
{
    const CodecRequirement codecs[] = {
        { AV_CODEC_ID_H264, "H.264" },
        { AV_CODEC_ID_HEVC, "HEVC" },
        { AV_CODEC_ID_AV1, "AV1" },
    };
    const AVHWDeviceType devices[] = {
        AV_HWDEVICE_TYPE_VAAPI,
        AV_HWDEVICE_TYPE_VULKAN,
    };

    bool failed = false;
    for (const CodecRequirement& codec : codecs) {
        if (!hasDecoder(codec.id)) {
            fprintf(stderr, "%s decoder not found\n", codec.name);
            failed = true;
            continue;
        }

        for (AVHWDeviceType device : devices) {
            if (!hasHardwareDevice(codec.id, device)) {
                const char* deviceName = av_hwdevice_get_type_name(device);
                fprintf(stderr,
                        "%s decoder has no %s hardware configuration\n",
                        codec.name,
                        deviceName != nullptr ? deviceName : "unknown");
                failed = true;
            }
        }
    }

    return failed ? 1 : 0;
}
