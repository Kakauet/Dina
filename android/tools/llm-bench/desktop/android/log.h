// Desktop stand-in for <android/log.h>, so the app's JNI source builds unchanged for the PC JVM
// (`dina_native` target, used by `.\dev.ps1 eval`). Only errors are printed.
#pragma once

#include <stdio.h>

enum {
    ANDROID_LOG_VERBOSE = 2,
    ANDROID_LOG_DEBUG = 3,
    ANDROID_LOG_INFO = 4,
    ANDROID_LOG_WARN = 5,
    ANDROID_LOG_ERROR = 6,
};

static inline int __android_log_write(int priority, const char *tag, const char *text) {
    if (priority >= ANDROID_LOG_ERROR) fprintf(stderr, "%s: %s", tag, text);
    return 0;
}
