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

static bool hasHardwareDevice(const AVCodec* decoder, AVHWDeviceType deviceType)
{
    for (int index = 0; ; ++index) {
        const AVCodecHWConfig* config = avcodec_get_hw_config(decoder, index);
        if (config == nullptr) {
            return false;
        }
        if (config->device_type == deviceType) {
            return true;
        }
    }
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
        const AVCodec* decoder = avcodec_find_decoder(codec.id);
        if (decoder == nullptr) {
            fprintf(stderr, "%s decoder not found\n", codec.name);
            failed = true;
            continue;
        }

        for (AVHWDeviceType device : devices) {
            if (!hasHardwareDevice(decoder, device)) {
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
