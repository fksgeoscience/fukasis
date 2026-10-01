// host テスト用の最小限の JNI スタブ (native-lib.cpp が使う分だけ)
#pragma once
#include <cstdint>
#include <string>

#define JNIEXPORT
#define JNICALL

typedef int32_t jint;
typedef int64_t jlong;
typedef double jdouble;
typedef uint8_t jboolean;
typedef int32_t jsize;

struct _jobject
{
    std::string str;
    void *buffer = nullptr;
};
typedef _jobject *jobject;
typedef jobject jclass;
typedef jobject jstring;

struct JNIEnv
{
    jstring NewStringUTF(const char *s)
    {
        jstring o = new _jobject();
        o->str = s;
        return o;
    }
    void *GetDirectBufferAddress(jobject o)
    {
        return o ? o->buffer : nullptr;
    }
};
