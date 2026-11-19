// SPDX-License-Identifier: GPL-3.0-or-later
#ifndef ONETUNNEL_UTF8_JNI_H
#define ONETUNNEL_UTF8_JNI_H
#include <stdint.h>
#include <string.h>

// JNI modified UTF-8 cannot represent emoji as standard UTF-8. Clash node/group
// names frequently contain flags, so convert UTF-16 explicitly at this boundary.
static char *copy_utf8(JNIEnv *env, jstring value) {
    if (!value) return NULL;
    jsize length = (*env)->GetStringLength(env, value);
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (!chars) return NULL;
    char *result = malloc((size_t)length * 3 + 1);
    if (!result) { (*env)->ReleaseStringChars(env, value, chars); return NULL; }
    size_t out = 0;
    for (jsize i = 0; i < length; i++) {
        uint32_t c = chars[i];
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < length && chars[i+1] >= 0xdc00 && chars[i+1] <= 0xdfff) {
            c = 0x10000 + ((c - 0xd800) << 10) + (chars[++i] - 0xdc00);
        } else if (c >= 0xd800 && c <= 0xdfff) c = 0xfffd;
        if (c < 0x80) result[out++] = (char)c;
        else if (c < 0x800) { result[out++] = (char)(0xc0 | (c >> 6)); result[out++] = (char)(0x80 | (c & 63)); }
        else if (c < 0x10000) { result[out++] = (char)(0xe0 | (c >> 12)); result[out++] = (char)(0x80 | ((c >> 6) & 63)); result[out++] = (char)(0x80 | (c & 63)); }
        else { result[out++] = (char)(0xf0 | (c >> 18)); result[out++] = (char)(0x80 | ((c >> 12) & 63)); result[out++] = (char)(0x80 | ((c >> 6) & 63)); result[out++] = (char)(0x80 | (c & 63)); }
    }
    result[out] = 0;
    (*env)->ReleaseStringChars(env, value, chars);
    return result;
}

static jstring from_utf8(JNIEnv *env, const char *value) {
    if (!value) value = "";
    size_t length = strlen(value), out = 0;
    jchar *chars = malloc((length + 1) * sizeof(jchar));
    if (!chars) return NULL;
    for (size_t i = 0; i < length;) {
        uint32_t c = (unsigned char)value[i++];
        int continuation = 0;
        if (c >= 0xc2 && c <= 0xdf) { c &= 31; continuation = 1; }
        else if (c >= 0xe0 && c <= 0xef) { c &= 15; continuation = 2; }
        else if (c >= 0xf0 && c <= 0xf4) { c &= 7; continuation = 3; }
        else if (c >= 0x80) c = 0xfffd;
        for (int k = 0; k < continuation; k++) {
            if (i >= length || ((unsigned char)value[i] & 0xc0) != 0x80) { c = 0xfffd; break; }
            c = (c << 6) | ((unsigned char)value[i++] & 63);
        }
        if (c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) c = 0xfffd;
        if (c > 0xffff) { c -= 0x10000; chars[out++] = (jchar)(0xd800 | (c >> 10)); chars[out++] = (jchar)(0xdc00 | (c & 1023)); }
        else chars[out++] = (jchar)c;
    }
    jstring result = (*env)->NewString(env, chars, (jsize)out);
    free(chars);
    return result;
}
#endif
