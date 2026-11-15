#include <jni.h>

extern "C"
JNIEXPORT jint JNICALL
Java_com_videodubbing_app_MainActivity_nativeTest(
    JNIEnv* env, jobject thiz) {
    return 42;
}
