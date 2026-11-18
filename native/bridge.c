// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include "bridge.h"

extern char *GoStartCore(char *home, char *config, int fd);
extern void GoStopCore(void);
static JavaVM *vm = NULL;
static jobject service = NULL;
static jmethodID protect_method = NULL;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *value, void *reserved) {
    (void)reserved;
    vm = value;
    return JNI_VERSION_1_6;
}

int protect_socket(int fd) {
    if (!vm) return 0;
    JNIEnv *env = NULL;
    int attached = 0;
    jint state = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, (void **)&env, NULL) != JNI_OK) return 0;
        attached = 1;
    } else if (state != JNI_OK) return 0;
    pthread_mutex_lock(&lock);
    jobject local = service ? (*env)->NewLocalRef(env, service) : NULL;
    jmethodID method = protect_method;
    pthread_mutex_unlock(&lock);
    jboolean result = JNI_FALSE;
    if (local && method) {
        result = (*env)->CallBooleanMethod(env, local, method, (jint)fd);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            result = JNI_FALSE;
        }
        (*env)->DeleteLocalRef(env, local);
    }
    if (attached) (*vm)->DetachCurrentThread(vm);
    return result == JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_dev_dsh_onetunnel_NativeCore_start(
    JNIEnv *env, jobject self, jstring home, jstring config, jint fd, jobject owner) {
    (void)self;
    pthread_mutex_lock(&lock);
    if (service) (*env)->DeleteGlobalRef(env, service);
    service = (*env)->NewGlobalRef(env, owner);
    jclass cls = (*env)->GetObjectClass(env, owner);
    protect_method = (*env)->GetMethodID(env, cls, "protectSocket", "(I)Z");
    (*env)->DeleteLocalRef(env, cls);
    pthread_mutex_unlock(&lock);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return (*env)->NewStringUTF(env, "Socket protection callback unavailable");
    }
    const char *h = (*env)->GetStringUTFChars(env, home, NULL);
    const char *c = (*env)->GetStringUTFChars(env, config, NULL);
    if (!h || !c) {
        if (h) (*env)->ReleaseStringUTFChars(env, home, h);
        if (c) (*env)->ReleaseStringUTFChars(env, config, c);
        return NULL; // JNI has raised an allocation error.
    }
    char *error = GoStartCore((char *)h, (char *)c, (int)fd);
    (*env)->ReleaseStringUTFChars(env, home, h);
    (*env)->ReleaseStringUTFChars(env, config, c);
    jstring result = (*env)->NewStringUTF(env, error ? error : "");
    free(error);
    return result;
}

JNIEXPORT void JNICALL Java_dev_dsh_onetunnel_NativeCore_stop(JNIEnv *env, jobject self) {
    (void)self;
    GoStopCore();
    pthread_mutex_lock(&lock);
    if (service) (*env)->DeleteGlobalRef(env, service);
    service = NULL;
    protect_method = NULL;
    pthread_mutex_unlock(&lock);
}
