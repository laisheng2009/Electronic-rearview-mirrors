#include "../../main/cpp/oem_log_filter.h"
#include <assert.h>
#include <stdio.h>

int main() {
    const char* normal[] = {"frame count: 676", "inference time:0.173956s",
        "camera 1 bright value: 21", "PersonDetectionResult: 0", "SNPEBackend::Infer",
        "class id :1; score :0.538086; index: 1; xmin :445.036194; ymin :195.466095;xmax :507.160217; ymax :234.420639"};
    for (const char* message : normal) {
        assert(oemFrameNoise(3, "WZT_MNN", message));
        assert(!oemFrameNoise(5, "WZT_MNN", message));
        assert(!oemFrameNoise(6, "WZT_MNN", message));
        assert(!oemFrameNoise(3, "unrelated", message));
    }
    assert(oemFrameNoise(4, nullptr, "processNewFrame: buffer time 48.26"));
    assert(oemFrameNoise(4, "bydextend_helper", "processNewFrame dqzcheck render width:3840 height:2600"));
    const char* retained[] = {"inference time:-1s", "inference time:nans", "frame count: 1 failure",
        "PersonDetectionResult: 1", "DetectionResult: -1", "camera 1 bright value: 2 error",
        "processNewFrame dequeueBuffer -110", "unknown record"};
    for (const char* message : retained) {
        assert(!oemFrameNoise(3, "WZT_MNN", message));
        assert(!oemFrameNoise(4, "bydextend_helper", message));
    }
    assert(!oemFrameNoise(4, "WZT_MNN", nullptr));
    puts("OEM log filter: known normal records suppressed; errors, detections and unknowns retained");
}
