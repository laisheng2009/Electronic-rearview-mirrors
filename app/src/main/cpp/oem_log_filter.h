#pragma once
#include <string.h>
#include <stdlib.h>
#include <math.h>

// Match only complete known normal records; a new suffix or negative result is not noise.
inline bool oemNumber(const char* text, const char* prefix, const char* suffix = "") {
    size_t length = strlen(prefix);
    if (strncmp(text, prefix, length)) return false;
    char* end;
    double value = strtod(text + length, &end);
    if (end == text + length || !isfinite(value) || value < 0) return false;
    if (strncmp(end, suffix, strlen(suffix))) return false;
    end += strlen(suffix);
    while (*end == ' ' || *end == '\n' || *end == '\r') ++end;
    return *end == 0;
}

template<size_t N>
inline bool oemNumbers(const char* text, const char* const (&prefixes)[N]) {
    for (const char* prefix : prefixes) {
        size_t length = strlen(prefix);
        if (strncmp(text, prefix, length)) return false;
        char* end;
        double value = strtod(text + length, &end);
        if (end == text + length || !isfinite(value) || value < 0) return false;
        text = end;
    }
    while (*text == ' ' || *text == '\n' || *text == '\r') ++text;
    return *text == 0;
}

inline bool oemFrameNoise(int priority, const char* tag, const char* text) {
    if (!text || priority < 3 || priority > 4) return false;
    if ((!tag || !*tag || !strcmp(tag, "bydextend_helper"))
            && oemNumber(text, "processNewFrame: buffer time ")) return true;
    if (!tag || !*tag || !strcmp(tag, "bydextend_helper")) {
        const char* prefix = "processNewFrame dqzcheck render width:";
        if (!strncmp(text, prefix, strlen(prefix))) {
            char* end;
            long width = strtol(text + strlen(prefix), &end, 10);
            if (width > 0 && oemNumber(end, " height:")) return true;
        }
    }
    if (!tag || strcmp(tag, "WZT_MNN")) return false;
    if (!strcmp(text, "SNPEBackend::Infer") || !strcmp(text, "SNPEBackend::Infer\n")) return true;
    const char* prefixes[] = {"frame count: ", "img_data.nSize :", "boxInfo size: ",
        "objects.size: ", "personsNum: ", "carsNum: ", "results size: ", "realDetPerson: ",
        "SENSI_LEVEL: ", "sensor setSensorValue: ", "Detection Time: "};
    for (const char* prefix : prefixes) if (oemNumber(text, prefix)) return true;
    if (oemNumber(text, "inference time:", "s")) return true;
    const char* const box[] = {"class id :", "; score :", "; index: ", "; xmin :",
        "; ymin :", ";xmax :", "; ymax :"};
    const char* const brightness[] = {"camera ", " bright value: "};
    if (oemNumbers(text, box) || oemNumbers(text, brightness)) return true;
    // Nonzero detections remain visible even when verbose diagnostics are disabled.
    return !strcmp(text, "PersonDetectionResult: 0") || !strcmp(text, "CarDetectionResult: 0")
        || !strcmp(text, "DetectionResult: 0");
}
