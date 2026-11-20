#include <jni.h>
#include <string>
#include <android/log.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_headless_android_MainActivity_nativeEngineInfo(JNIEnv* env, jobject /* this */) {
    const char* info = "Headless native engine · C++17 · NDK";
    __android_log_print(ANDROID_LOG_INFO, "HeadlessNative", "%s", info);
    return env->NewStringUTF(info);
}
