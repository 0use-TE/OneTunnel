// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include "bridge.h"
#include "utf8_jni.h"

extern char *GoStartCore(char *home, char *config, int fd);
extern void GoStopCore(void);
extern char *GoPrepareTailscale(char *home, char *config, char *name);
extern char *GoTailscaleAction(char *name, char *action);
extern char *GoReloadCore(char *config);
extern char *GoPrepareCore(char *home, char *config);
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
        // Android and OpenJDK C headers differ in the second argument's type.
        // A void pointer to the output slot is valid for both C declarations.
        if ((*vm)->AttachCurrentThread(vm, (void *)&env, NULL) != JNI_OK) return 0;
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
    char *h = copy_utf8(env, home);
    char *c = copy_utf8(env, config);
    if (!h || !c) {
        free(h); free(c);
        return NULL; // JNI has raised an allocation error.
    }
    char *error = GoStartCore((char *)h, (char *)c, (int)fd);
    free(h); free(c);
    jstring result = from_utf8(env, error);
    free(error);
    return result;
}

JNIEXPORT jstring JNICALL Java_dev_dsh_onetunnel_NativeCore_prepareTailscale(
    JNIEnv *env, jobject self, jstring home, jstring config, jstring name) {
    (void)self;
    char *h = copy_utf8(env, home), *c = copy_utf8(env, config), *n = copy_utf8(env, name);
    if (!h || !c || !n) { free(h); free(c); free(n); return NULL; }
    char *response = GoPrepareTailscale(h, c, n);
    free(h); free(c); free(n);
    jstring result = from_utf8(env, response);
    free(response);
    return result;
}

JNIEXPORT jstring JNICALL Java_dev_dsh_onetunnel_NativeCore_tailscale(
    JNIEnv *env, jobject self, jstring name, jstring action) {
    (void)self;
    char *n = copy_utf8(env, name), *a = copy_utf8(env, action);
    if (!n || !a) { free(n); free(a); return NULL; }
    char *response = GoTailscaleAction(n, a);
    free(n); free(a);
    jstring result = from_utf8(env, response);
    free(response);
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

JNIEXPORT jstring JNICALL Java_dev_dsh_onetunnel_NativeCore_reload(JNIEnv *env, jobject self, jstring config) {
    (void)self;
    char *c = copy_utf8(env, config);
    if (!c) return NULL;
    char *response = GoReloadCore(c);
    free(c);
    jstring result = from_utf8(env, response);
    free(response);
    return result;
}

JNIEXPORT jstring JNICALL Java_dev_dsh_onetunnel_NativeCore_prepare(JNIEnv *env, jobject self, jstring home, jstring config) {
    (void)self;
    char *h = copy_utf8(env, home), *c = copy_utf8(env, config);
    if (!h || !c) { free(h); free(c); return NULL; }
    char *response = GoPrepareCore(h, c);
    free(h); free(c);
    jstring result = from_utf8(env, response);
    free(response);
    return result;
}
