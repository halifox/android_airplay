//
// Created by hjhua on 16-7-28.
//

#ifdef __cplusplus
extern "C" {
#endif

#include <pthread.h>
#include <fcntl.h>
#include <jni.h>
#include <android/log.h>
#include "com_halifox_airplay_AirplayJni.h"
#include "include/shairplay/raop.h"
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <asm/errno.h>
#include "utils.h"
#include "httpd.h"


#ifndef LOG_TAG
#define LOG_TAG "shairplay-jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#endif

#define MAX_AIRPLAY_CLIENTS     (1)


#define RSA_KEY \
"-----BEGIN RSA PRIVATE KEY-----\n"\
"MIIEpQIBAAKCAQEA59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUt\n"\
"wC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4U\n"\
"wPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf\n"\
"/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/\n"\
"UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEewIDAQABAoIBAQDl8Axy9XfW\n"\
"BLmkzkEiqoSwF0PsmVrPzH9KsnwLGH+QZlvjWd8SWYGN7u1507HvhF5N3drJoVU3O14nDY4TFQAa\n"\
"LlJ9VM35AApXaLyY1ERrN7u9ALKd2LUwYhM7Km539O4yUFYikE2nIPscEsA5ltpxOgUGCY7b7ez5\n"\
"NtD6nL1ZKauw7aNXmVAvmJTcuPxWmoktF3gDJKK2wxZuNGcJE0uFQEG4Z3BrWP7yoNuSK3dii2jm\n"\
"lpPHr0O/KnPQtzI3eguhe0TwUem/eYSdyzMyVx/YpwkzwtYL3sR5k0o9rKQLtvLzfAqdBxBurciz\n"\
"aaA/L0HIgAmOit1GJA2saMxTVPNhAoGBAPfgv1oeZxgxmotiCcMXFEQEWflzhWYTsXrhUIuz5jFu\n"\
"a39GLS99ZEErhLdrwj8rDDViRVJ5skOp9zFvlYAHs0xh92ji1E7V/ysnKBfsMrPkk5KSKPrnjndM\n"\
"oPdevWnVkgJ5jxFuNgxkOLMuG9i53B4yMvDTCRiIPMQ++N2iLDaRAoGBAO9v//mU8eVkQaoANf0Z\n"\
"oMjW8CN4xwWA2cSEIHkd9AfFkftuv8oyLDCG3ZAf0vrhrrtkrfa7ef+AUb69DNggq4mHQAYBp7L+\n"\
"k5DKzJrKuO0r+R0YbY9pZD1+/g9dVt91d6LQNepUE/yY2PP5CNoFmjedpLHMOPFdVgqDzDFxU8hL\n"\
"AoGBANDrr7xAJbqBjHVwIzQ4To9pb4BNeqDndk5Qe7fT3+/H1njGaC0/rXE0Qb7q5ySgnsCb3DvA\n"\
"cJyRM9SJ7OKlGt0FMSdJD5KG0XPIpAVNwgpXXH5MDJg09KHeh0kXo+QA6viFBi21y340NonnEfdf\n"\
"54PX4ZGS/Xac1UK+pLkBB+zRAoGAf0AY3H3qKS2lMEI4bzEFoHeK3G895pDaK3TFBVmD7fV0Zhov\n"\
"17fegFPMwOII8MisYm9ZfT2Z0s5Ro3s5rkt+nvLAdfC/PYPKzTLalpGSwomSNYJcB9HNMlmhkGzc\n"\
"1JnLYT4iyUyx6pcZBmCd8bD0iwY/FzcgNDaUmbX9+XDvRA0CgYEAkE7pIPlE71qvfJQgoA9em0gI\n"\
"LAuE4Pu13aKiJnfft7hIjbK+5kyb3TysZvoyDnb3HOKvInK7vXbKuU4ISgxB2bB3HcYzQMGsz1qJ\n"\
"2gG0N5hvJpzwwhbhXqFKA4zaaSrw622wDniAK5MlIE0tIAKKP4yxNGjoD2QYjhBGuhvkWKY=\n"\
"-----END RSA PRIVATE KEY-----\n"

static JavaVM *javaVm = NULL;
static jobject airplayObject = NULL;

static jclass airlayClass = NULL;

static jmethodID audio_init = NULL;
static jmethodID audio_destroy = NULL;
static jmethodID audio_flush = NULL;
static jmethodID audio_set_volume = NULL;
static jmethodID audio_set_metadata = NULL;
static jmethodID audio_set_coverart = NULL;

static jmethodID audio_set_total_len = NULL;
static jmethodID audio_set_progress = NULL;

static jmethodID audio_data = NULL;


static void
raop_log_callback(void *cls, int level, const char *msg) {
//    LOGI("RAOP LOG(%d): %s\n", level, msg);

  switch (level) {
    case RAOP_LOG_EMERG :
    case RAOP_LOG_ALERT :
    case RAOP_LOG_CRIT  :
    case RAOP_LOG_ERR   :
      LOGE("%s\n", msg);
      break;
    case RAOP_LOG_WARNING:
      LOGW("%s\n", msg);
      break;
    case RAOP_LOG_NOTICE :
    case RAOP_LOG_INFO   :
      LOGI("%s\n", msg);
      break;
    case RAOP_LOG_DEBUG  :
      LOGI("%s\n", msg);
      break;
    default:
      LOGE("can not happen\n");
      return;
  }

  return;
}

static void send_data(const void *pbuffer, int bufferlen) {
  JNIEnv *sendDataJniEnv = NULL;
  (*javaVm)->AttachCurrentThread(javaVm, &sendDataJniEnv, NULL);
  jbyteArray bytearray;
  bytearray = (*sendDataJniEnv)->NewByteArray(sendDataJniEnv, bufferlen);
  if (NULL != bytearray) {
    (*sendDataJniEnv)->SetByteArrayRegion(sendDataJniEnv, bytearray, 0, bufferlen, (const jbyte *) pbuffer);
  }
  if (NULL != audio_data) {
    (*sendDataJniEnv)->CallVoidMethod(sendDataJniEnv,
                                      airplayObject,
                                      audio_data,
                                      bytearray,
                                      bufferlen);
  }
  (*sendDataJniEnv)->DeleteLocalRef(sendDataJniEnv, bytearray);
  (*javaVm)->DetachCurrentThread(javaVm);
}

static int run = 1;

static void *
audio_init_cb(void *cls, int bits, int channels, int samplerate) {
  int ret = -1;

  LOGI("call %s function\n", __FUNCTION__);
  JNIEnv *globalJniEnv = NULL;
  (*javaVm)->AttachCurrentThread(javaVm, &globalJniEnv, NULL);

  audio_init = (*globalJniEnv)->GetMethodID(globalJniEnv, airlayClass, "audio_init", "(III)V");
  if ((*globalJniEnv)->ExceptionCheck(globalJniEnv)) {  // 检查JNI调用是否有引发异常
    (*globalJniEnv)->ExceptionDescribe(globalJniEnv);
    (*globalJniEnv)->ExceptionClear(globalJniEnv);        // 清除引发的异常，在Java层不会打印异常的堆栈信息
  }

  audio_data = (*globalJniEnv)->GetMethodID(globalJniEnv, airlayClass, "audio_data", "([BI)V");

  audio_destroy = (*globalJniEnv)->GetMethodID(globalJniEnv, airlayClass, "audio_destroy", "()V");
  if ((*globalJniEnv)->ExceptionCheck(globalJniEnv)) {  // 检查JNI调用是否有引发异常
    (*globalJniEnv)->ExceptionDescribe(globalJniEnv);
    (*globalJniEnv)->ExceptionClear(globalJniEnv);        // 清除引发的异常，在Java层不会打印异常的堆栈信息
  }
  audio_flush = (*globalJniEnv)->GetMethodID(globalJniEnv, airlayClass, "audio_flush", "()V");
  if ((*globalJniEnv)->ExceptionCheck(globalJniEnv)) {  // 检查JNI调用是否有引发异常
    (*globalJniEnv)->ExceptionDescribe(globalJniEnv);
    (*globalJniEnv)->ExceptionClear(globalJniEnv);        // 清除引发的异常，在Java层不会打印异常的堆栈信息
  }
  audio_set_volume =
      (*globalJniEnv)->GetMethodID(globalJniEnv, airlayClass, "audio_set_volume", "(F)V");
  if ((*globalJniEnv)->ExceptionCheck(globalJniEnv)) {  // 检查JNI调用是否有引发异常
    (*globalJniEnv)->ExceptionDescribe(globalJniEnv);
    (*globalJniEnv)->ExceptionClear(globalJniEnv);        // 清除引发的异常，在Java层不会打印异常的堆栈信息
  }
  audio_set_metadata =
      (*globalJniEnv)->GetMethodID(globalJniEnv, airlayClass, "audio_set_metadata", "([BI)V");
  if ((*globalJniEnv)->ExceptionCheck(globalJniEnv)) {  // 检查JNI调用是否有引发异常
    (*globalJniEnv)->ExceptionDescribe(globalJniEnv);
    (*globalJniEnv)->ExceptionClear(globalJniEnv);        // 清除引发的异常，在Java层不会打印异常的堆栈信息
  }
  audio_set_coverart =
      (*globalJniEnv)->GetMethodID(globalJniEnv, airlayClass, "audio_set_coverart", "([BI)V");
  if ((*globalJniEnv)->ExceptionCheck(globalJniEnv)) {  // 检查JNI调用是否有引发异常
    (*globalJniEnv)->ExceptionDescribe(globalJniEnv);
    (*globalJniEnv)->ExceptionClear(globalJniEnv);        // 清除引发的异常，在Java层不会打印异常的堆栈信息
  }
  audio_set_total_len =
      (*globalJniEnv)->GetMethodID(globalJniEnv, airlayClass, "audio_set_total_len", "([BI)V");
  if ((*globalJniEnv)->ExceptionCheck(globalJniEnv)) {  // 检查JNI调用是否有引发异常
    (*globalJniEnv)->ExceptionDescribe(globalJniEnv);
    (*globalJniEnv)->ExceptionClear(globalJniEnv);        // 清除引发的异常，在Java层不会打印异常的堆栈信息
  }

  audio_set_progress =
      (*globalJniEnv)->GetMethodID(globalJniEnv, airlayClass, "audio_set_progress", "([BI)V");
  if ((*globalJniEnv)->ExceptionCheck(globalJniEnv)) {  // 检查JNI调用是否有引发异常
    (*globalJniEnv)->ExceptionDescribe(globalJniEnv);
    (*globalJniEnv)->ExceptionClear(globalJniEnv);        // 清除引发的异常，在Java层不会打印异常的堆栈信息
  }

  (*globalJniEnv)->CallVoidMethod(globalJniEnv,
                                  airplayObject,
                                  audio_init,
                                  bits,
                                  channels,
                                  samplerate);

  return NULL;
}

static void
audio_process_cb(void *cls, void *session, const void *buffer, int buflen) {
  send_data(buffer, buflen);
}

static void
audio_destroy_cb(void *cls, void *session) {

  JNIEnv *globalJniEnv = NULL;
  (*javaVm)->AttachCurrentThread(javaVm, &globalJniEnv, NULL);
  LOGI("call %s function000\n", __FUNCTION__);

  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  (*globalJniEnv)->CallVoidMethod(globalJniEnv, airplayObject, audio_destroy);

  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  if (NULL != audio_init) {
//        (*globalJniEnv)->DeleteLocalRef(globalJniEnv, audio_init);
  }
  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  if (NULL != audio_flush) {
//        (*globalJniEnv)->DeleteLocalRef(globalJniEnv, audio_flush);
  }
  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  if (NULL != audio_destroy) {
//        (*globalJniEnv)->DeleteLocalRef(globalJniEnv, audio_destroy);
  }
  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  if (NULL != audio_set_volume) {
//        (*globalJniEnv)->DeleteLocalRef(globalJniEnv, audio_set_volume);
  }
  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  if (NULL != audio_set_metadata) {
//        (*globalJniEnv)->DeleteLocalRef(globalJniEnv, audio_set_metadata);
  }
  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  if (NULL != audio_set_coverart) {
//        (*globalJniEnv)->DeleteLocalRef(globalJniEnv, audio_set_coverart);
  }
  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  if (NULL != audio_set_total_len) {
//        (*globalJniEnv)->DeleteLocalRef(globalJniEnv, audio_set_total_len);
  }
  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  if (NULL != audio_set_progress) {
//        (*globalJniEnv)->DeleteLocalRef(globalJniEnv, audio_set_progress);
  }
  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  audio_init = NULL;
  audio_flush = NULL;

  audio_destroy = NULL;
  audio_set_volume = NULL;
  audio_set_metadata = NULL;
  audio_set_coverart = NULL;

  audio_set_total_len = NULL;
  audio_set_progress = NULL;
  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  (*javaVm)->DetachCurrentThread(javaVm);
  LOGI("call %s function %d line\n", __FUNCTION__, __LINE__);
  globalJniEnv = NULL;
  return;
}

static void
audio_flush_cb(void *cls, void *session) {
  LOGI("call %s function\n", __FUNCTION__);
  JNIEnv *globalJniEnv = NULL;
  (*javaVm)->AttachCurrentThread(javaVm, &globalJniEnv, NULL);
  (*globalJniEnv)->CallVoidMethod(globalJniEnv, airplayObject, audio_flush);
  (*javaVm)->DetachCurrentThread(javaVm);
  return;
}

static void
audio_set_volume_cb(void *cls, void *session, float volume) {
  LOGI("call %s function\n", __FUNCTION__);
  JNIEnv *globalJniEnv = NULL;
  (*javaVm)->AttachCurrentThread(javaVm, &globalJniEnv, NULL);
  (*globalJniEnv)->CallVoidMethod(globalJniEnv, airplayObject, audio_set_volume, volume);
  (*javaVm)->DetachCurrentThread(javaVm);
  return;
}

static void
audio_set_total_len_cb(void *cls, void *session, const void *buffer, int buflen) {
  LOGI("call %s function\n", __FUNCTION__);
  JNIEnv *globalJniEnv = NULL;
  (*javaVm)->AttachCurrentThread(javaVm, &globalJniEnv, NULL);
  jbyteArray bytearray;
  bytearray = (*globalJniEnv)->NewByteArray(globalJniEnv, buflen);
  if (NULL != bytearray) {
    (*globalJniEnv)->SetByteArrayRegion(globalJniEnv, bytearray, 0, buflen, buffer);
  }
  if (NULL != audio_set_total_len)
    (*globalJniEnv)->CallVoidMethod(globalJniEnv,
                                    airplayObject,
                                    audio_set_total_len,
                                    bytearray,
                                    buflen);
  (*globalJniEnv)->DeleteLocalRef(globalJniEnv, bytearray);
  (*javaVm)->DetachCurrentThread(javaVm);

  return;
}


static void
audio_set_progress_cb(void *cls, void *session, const void *buffer, int buflen) {
//    LOGI("call %s function\n", __FUNCTION__);
  JNIEnv *globalJniEnv = NULL;
  (*javaVm)->AttachCurrentThread(javaVm, &globalJniEnv, NULL);
  jbyteArray bytearray;
  bytearray = (*globalJniEnv)->NewByteArray(globalJniEnv, buflen);
  if (NULL != bytearray) {
    (*globalJniEnv)->SetByteArrayRegion(globalJniEnv, bytearray, 0, buflen, buffer);
  }

  if (NULL != audio_set_progress)
    (*globalJniEnv)->CallVoidMethod(globalJniEnv,
                                    airplayObject,
                                    audio_set_progress,
                                    bytearray,
                                    buflen);
  (*globalJniEnv)->DeleteLocalRef(globalJniEnv, bytearray);
  (*javaVm)->DetachCurrentThread(javaVm);
  return;
}

static void
audio_set_metadata_cb(void *cls, void *session, const void *buffer, int buflen) {
  JNIEnv *globalJniEnv = NULL;
  (*javaVm)->AttachCurrentThread(javaVm, &globalJniEnv, NULL);
  LOGI("call %s function\n", __FUNCTION__);

  jbyteArray bytearray;
  bytearray = (*globalJniEnv)->NewByteArray(globalJniEnv, buflen);
  if (NULL != bytearray) {
    (*globalJniEnv)->SetByteArrayRegion(globalJniEnv, bytearray, 0, buflen, buffer);
  }

  if (NULL != audio_set_metadata)
    (*globalJniEnv)->CallVoidMethod(globalJniEnv,
                                    airplayObject,
                                    audio_set_metadata,
                                    bytearray,
                                    buflen);
  (*globalJniEnv)->DeleteLocalRef(globalJniEnv, bytearray);
  (*javaVm)->DetachCurrentThread(javaVm);
  return;
}

static void
audio_set_coverart_cb(void *cls, void *session, const void *buffer, int buflen) {
  LOGI("call %s function\n", __FUNCTION__);
  JNIEnv *globalJniEnv = NULL;
  (*javaVm)->AttachCurrentThread(javaVm, &globalJniEnv, NULL);
  LOGI("call %s function\n", __FUNCTION__);
  jbyteArray bytearray;
  bytearray = (*globalJniEnv)->NewByteArray(globalJniEnv, buflen);
  if (NULL != bytearray) {
    (*globalJniEnv)->SetByteArrayRegion(globalJniEnv, bytearray, 0, buflen, buffer);
  }

  if (NULL != audio_set_coverart)
    (*globalJniEnv)->CallVoidMethod(globalJniEnv,
                                    airplayObject,
                                    audio_set_coverart,
                                    bytearray,
                                    buflen);
  (*globalJniEnv)->DeleteLocalRef(globalJniEnv, bytearray);
  (*javaVm)->DetachCurrentThread(javaVm);
  return;
}

static raop_t *pgstRaop = NULL;


static char toHex(const char *data) {
  char result = 0;

  if ('0' <= data[0] && '9' >= data[0]) {
    result = (data[0] - '0') << 4;
  } else if ('A' <= data[0] && 'F' >= data[0]) {
    result = ((data[0] - 'A') + 10) << 4;
  }

  if ('0' <= data[1] && '9' >= data[1]) {
    result += (data[1] - '0') << 0;
  } else if ('A' <= data[1] && 'F' >= data[1]) {
    result += ((data[1] - 'A') + 10) << 0;
  }

  return result;
}
/*
 * Class:     com_halifox_airplay_AirplayJni
 * Method:    init
 * Signature: (ILjava/lang/String;)Z
 */
JNIEXPORT jint JNICALL Java_com_halifox_airplay_AirplayJni_init
    (JNIEnv *env, jobject obj, jint shairport, jstring hardwareAddr) {
  int iRet;
  unsigned short airport = shairport;
  char chwaddr[6];
  raop_callbacks_t stCallBacks;
  jclass temp;
  const char *hdAddr = (*env)->GetStringUTFChars(env, hardwareAddr, NULL);


  LOGW("Java_com_halifox_airplay_AirplayJni_init %p\n", obj);

  if (NULL != pgstRaop) {
    LOGW("the shairport jni modulie have been initialized\n");
    return -1;
  }

  chwaddr[0] = toHex(hdAddr);
  chwaddr[1] = toHex(hdAddr + 2);
  chwaddr[2] = toHex(hdAddr + 4);
  chwaddr[3] = toHex(hdAddr + 6);
  chwaddr[4] = toHex(hdAddr + 8);
  chwaddr[5] = toHex(hdAddr + 10);

    LOGW("chwaddr[0] : 0x%x", chwaddr[0]);
    LOGW("chwaddr[1] : 0x%x", chwaddr[1]);
    LOGW("chwaddr[2] : 0x%x", chwaddr[2]);
    LOGW("chwaddr[3] : 0x%x", chwaddr[3]);
    LOGW("chwaddr[4] : 0x%x", chwaddr[4]);
    LOGW("chwaddr[5] : 0x%x", chwaddr[5]);

    //9C:2E:A1:BA:C1:7F
//  chwaddr[0] = 0x9c;
//  chwaddr[1] = 0x2e;
//  chwaddr[2] = 0xa1;
//  chwaddr[3] = 0xba;
//  chwaddr[4] = 0xc1;
//  chwaddr[5] = 0x7f;


  stCallBacks.cls = NULL;
  stCallBacks.audio_init = audio_init_cb;
  stCallBacks.audio_destroy = audio_destroy_cb;
  stCallBacks.audio_flush = audio_flush_cb;
  stCallBacks.audio_process = audio_process_cb;

  stCallBacks.audio_set_coverart = audio_set_coverart_cb;
  stCallBacks.audio_set_metadata = audio_set_metadata_cb;
  stCallBacks.audio_set_volume = audio_set_volume_cb;
  stCallBacks.audio_set_total_len = audio_set_total_len_cb;
  stCallBacks.audio_set_progress = audio_set_progress_cb;

  pgstRaop = raop_init(MAX_AIRPLAY_CLIENTS, &stCallBacks, RSA_KEY, NULL);
  if (NULL == pgstRaop) {
    (*env)->ReleaseStringUTFChars(env, hardwareAddr, hdAddr);
    return -1;
  }

  raop_set_log_level(pgstRaop, RAOP_LOG_DEBUG);
  raop_set_log_callback(pgstRaop, raop_log_callback, NULL);


  iRet = raop_start(pgstRaop, &airport, chwaddr, 6, NULL);
  if (0 > iRet) {
    LOGE("failed to start raop : iret:%d \n", iRet);
    raop_destroy(pgstRaop);
    pgstRaop = NULL;
    (*env)->ReleaseStringUTFChars(env, hardwareAddr, hdAddr);
    return -1;
  }

  (*env)->ReleaseStringUTFChars(env, hardwareAddr, hdAddr);
  LOGI("init shairplay successfully");

  temp = (*env)->GetObjectClass(env, obj);
  airlayClass = (jclass) (*env)->NewGlobalRef(env, temp);
  airplayObject = (*env)->NewGlobalRef(env, obj);
  (*env)->GetJavaVM(env, &javaVm);
  return airport;
}

/*
 * Class:     com_halifox_airplay_AirplayJni
 * Method:    start
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_com_halifox_airplay_AirplayJni_start
    (JNIEnv *env, jobject obj) {
  LOGI("call shairplay start function\n");
  return JNI_TRUE;
}

/*
 * Class:     com_halifox_airplay_AirplayJni
 * Method:    stop
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_com_halifox_airplay_AirplayJni_stop
    (JNIEnv *env, jobject obj) {
  LOGI("call shairplay stop function\n");
  send_close_connections_cmd();
  return JNI_TRUE;
}
/*
 * Class:     com_halifox_airplay_AirplayJni
 * Method:    unInit
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_com_halifox_airplay_AirplayJni_unInit
    (JNIEnv *env, jobject obj) {
  LOGI("Java_com_halifox_airplay_AirplayJni_unInit 000");
  if (NULL != pgstRaop) {
    raop_stop(pgstRaop);
    raop_destroy(pgstRaop);
    pgstRaop = NULL;
  }

  LOGI("Java_com_halifox_airplay_AirplayJni_unInit 001");
  if (airplayObject) {
    (*env)->DeleteGlobalRef(env, airplayObject);
    airplayObject = NULL;
  }

  LOGI("Java_com_halifox_airplay_AirplayJni_unInit 002");
  if (airlayClass) {
    (*env)->DeleteGlobalRef(env, airlayClass);
    airlayClass = NULL;
  }

  LOGI("Java_com_halifox_airplay_AirplayJni_unInit 003");
  javaVm = NULL;
  return JNI_TRUE;
}



#ifdef __cplusplus
}
#endif
