/*
 * JNI 桥：Java/Kotlin 侧 SerialLink 提供字节读写与 DTR/RTS，C 侧调用官方 esp-serial-flasher。
 */
#include <jni.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_loader.h"
#include "esp_loader_io.h"

extern const esp_loader_port_ops_t android_port_ops;

static JavaVM *g_vm;
static jobject g_link;                 /* 全局引用 */
static jmethodID m_read_fully;
static jmethodID m_write_all;
static jmethodID m_set_dtr;
static jmethodID m_set_rts;
static jmethodID m_set_baud;
static jmethodID m_purge;
static jmethodID m_reopen;
static jmethodID m_is_jtag;
static jmethodID m_log;
static jmethodID m_logline;
static jmethodID m_progress;

static esp_loader_t g_loader;
static esp_loader_port_t g_port;
static bool g_inited;
static volatile uint32_t g_tx_count, g_rx_count;

#define LOG_TAG "EspFlasherJNI"

static JNIEnv *env_get(void)
{
    JNIEnv *env = NULL;
    if (!g_vm) return NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
    }
    return env;
}

static void jb_logf(const char *fmt, ...)
{
    JNIEnv *e = env_get();
    if (!e || !g_link || !m_logline) return;
    char buf[192];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    jstring js = (*e)->NewStringUTF(e, buf);
    if (js) {
        (*e)->CallVoidMethod(e, g_link, m_logline, js);
        (*e)->DeleteLocalRef(e, js);
    }
}

static int check_exception(JNIEnv *env)
{
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return -1;
    }
    return 0;
}

/* ---------- 供 android_port.c 调用的实现 ---------- */

static uint32_t g_read_tries, g_read_ok, g_read_timeouts;

void jb_log(int level, const char *msg);

int jb_read(uint8_t *dst, uint16_t size, uint32_t timeout_ms)
{
    JNIEnv *env = env_get();
    if (!env || !g_link) return -1;
    jbyteArray arr = (*env)->NewByteArray(env, size);
    if (!arr) return -1;
    jint n = (*env)->CallIntMethod(env, g_link, m_read_fully, arr, (jint)size, (jint)timeout_ms);
    g_read_tries++;
    if (n > 0) {
        g_rx_count += (uint32_t)n;
        g_read_ok++;
    } else {
        g_read_timeouts++;
    }
    if (check_exception(env) != 0) n = -1;
    if (n > 0) (*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *)dst);
    // 读通道实况：成功只报一次（避免逐字节刷屏），失败逐条报，方便定位
    if (n > 0 && g_read_ok == 1) {
        char ok[96];
        snprintf(ok, sizeof(ok), "读通道正常：1 字节请求读到 %d 字节", (int)n);
        jb_log(3, ok);
    }
    if (n <= 0 && (g_read_timeouts <= 5 || g_read_timeouts % 1000 == 0)) {
        char buf[112];
        snprintf(buf, sizeof(buf), "读 #%u 失败：size=%u timeout=%ums 返回 %d",
                 (unsigned)g_read_tries, (unsigned)size, (unsigned)timeout_ms, (int)n);
        jb_log(3, buf);
    }
    (*env)->DeleteLocalRef(env, arr);
    return (int)n;
}

int jb_write(const uint8_t *src, uint16_t size, uint32_t timeout_ms)
{
    JNIEnv *env = env_get();
    if (!env || !g_link) return 0;
    jbyteArray arr = (*env)->NewByteArray(env, size);
    if (!arr) return 0;
    (*env)->SetByteArrayRegion(env, arr, 0, size, (const jbyte *)src);
    jboolean ok = (*env)->CallBooleanMethod(env, g_link, m_write_all, arr, (jint)size, (jint)timeout_ms);
    if (ok == JNI_TRUE) g_tx_count += (uint32_t)size;
    if (check_exception(env) != 0) ok = JNI_FALSE;
    (*env)->DeleteLocalRef(env, arr);
    return ok == JNI_TRUE;
}

void jb_set_dtr(int v)
{
    JNIEnv *env = env_get();
    if (!env || !g_link) return;
    (*env)->CallVoidMethod(env, g_link, m_set_dtr, v ? JNI_TRUE : JNI_FALSE);
    check_exception(env);
}

void jb_set_rts(int v)
{
    JNIEnv *env = env_get();
    if (!env || !g_link) return;
    (*env)->CallVoidMethod(env, g_link, m_set_rts, v ? JNI_TRUE : JNI_FALSE);
    check_exception(env);
}

int jb_set_baud(uint32_t baud)
{
    JNIEnv *env = env_get();
    if (!env || !g_link) return 0;
    jboolean ok = (*env)->CallBooleanMethod(env, g_link, m_set_baud, (jint)baud);
    if (check_exception(env) != 0) ok = JNI_FALSE;
    return ok == JNI_TRUE;
}

void jb_purge_input(void)
{
    JNIEnv *env = env_get();
    if (!env || !g_link) return;
    (*env)->CallVoidMethod(env, g_link, m_purge);
    check_exception(env);
}

int jb_reopen(uint32_t timeout_ms)
{
    JNIEnv *env = env_get();
    if (!env || !g_link) return 0;
    jboolean ok = (*env)->CallBooleanMethod(env, g_link, m_reopen, (jint)timeout_ms);
    if (check_exception(env) != 0) ok = JNI_FALSE;
    return ok == JNI_TRUE;
}

int jb_is_usb_jtag(void)
{
    JNIEnv *env = env_get();
    if (!env || !g_link) return 0;
    jboolean ok = (*env)->CallBooleanMethod(env, g_link, m_is_jtag);
    if (check_exception(env) != 0) return 0;
    return ok == JNI_TRUE;
}

void jb_log(int level, const char *msg)
{
    JNIEnv *env = env_get();
    if (!env || !g_link) return;
    char line[600];
    snprintf(line, sizeof(line), "[%d] %s", level, msg);
    jstring s = (*env)->NewStringUTF(env, line);
    if (!s) return;
    (*env)->CallVoidMethod(env, g_link, m_log, s);
    check_exception(env);
    (*env)->DeleteLocalRef(env, s);
}

/* ---------- Java 可见的 native 方法 ---------- */

static const char *err_text(int code)
{
    switch (code) {
    case ESP_LOADER_SUCCESS: return "成功";
    case ESP_LOADER_ERROR_FAIL: return "失败（芯片未响应或参数错误）";
    case ESP_LOADER_ERROR_TIMEOUT: return "超时（检查端口/接线/是否已进下载模式）";
    case ESP_LOADER_ERROR_IMAGE_SIZE: return "镜像大于 Flash 容量";
    case ESP_LOADER_ERROR_INVALID_MD5: return "MD5 校验不通过（数据写入后不完整）";
    case ESP_LOADER_ERROR_INVALID_PARAM: return "参数非法";
    case ESP_LOADER_ERROR_INVALID_TARGET: return "目标芯片无效";
    case ESP_LOADER_ERROR_UNSUPPORTED_CHIP: return "不支持的芯片型号";
    case ESP_LOADER_ERROR_UNSUPPORTED_FUNC: return "该芯片不支持此功能";
    case ESP_LOADER_ERROR_INVALID_RESPONSE: return "芯片回包格式错误";
    default: return "未知错误";
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_espflash_burn_NativeFlasher_nativeAttach(JNIEnv *env, jobject thiz, jobject link)
{
    (void)thiz;
    if (g_link) {
        (*env)->DeleteGlobalRef(env, g_link);
        g_link = NULL;
    }
    g_link = (*env)->NewGlobalRef(env, link);
    jclass cls = (*env)->GetObjectClass(env, link);
    m_read_fully = (*env)->GetMethodID(env, cls, "readFully", "([BII)I");
    m_write_all = (*env)->GetMethodID(env, cls, "writeAll", "([BII)Z");
    m_set_dtr = (*env)->GetMethodID(env, cls, "setDtr", "(Z)V");
    m_set_rts = (*env)->GetMethodID(env, cls, "setRts", "(Z)V");
    m_set_baud = (*env)->GetMethodID(env, cls, "setBaud", "(I)Z");
    m_purge = (*env)->GetMethodID(env, cls, "purgeInput", "()V");
    m_reopen = (*env)->GetMethodID(env, cls, "reopenAfterReset", "(I)Z");
    m_is_jtag = (*env)->GetMethodID(env, cls, "isUsbJtag", "()Z");
    m_log = m_logline = (*env)->GetMethodID(env, cls, "logLine", "(Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);

    jclass pcls = (*env)->FindClass(env, "com/espflash/burn/ProgressSink");
    if (pcls) {
        m_progress = (*env)->GetMethodID(env, pcls, "onProgress", "(I)V");
        (*env)->DeleteLocalRef(env, pcls);
    }
}

JNIEXPORT jint JNICALL
Java_com_espflash_burn_NativeFlasher_nativeConnect(JNIEnv *env, jobject thiz,
                                                       jint sync_timeout_ms, jint trials,
                                                       jboolean want_stub)
{
    (void)env;
    (void)thiz;
    g_port.ops = &android_port_ops;
    esp_loader_error_t err = esp_loader_init_serial(&g_loader, &g_port);
    if (err != ESP_LOADER_SUCCESS) return (jint)err;

    esp_loader_connect_args_t args = {
        .sync_timeout = (uint32_t)sync_timeout_ms,
        .trials = (int32_t)trials,
    };
    g_tx_count = 0;
    g_rx_count = 0;
    g_read_tries = 0;
    g_read_ok = 0;
    g_read_timeouts = 0;
    if (want_stub == JNI_TRUE) {
        // 压缩写入（FLASH_DEFL_*）和高速擦写都要靠芯片里的 flasher stub，
        // ROM 只认基础命令，所以先让库把自带 stub 下载进 RAM 并跑起来。
        err = esp_loader_connect_with_stub(&g_loader, &args);
        if (err != ESP_LOADER_SUCCESS) {
            jb_log(2, "stub 启动失败，退回 ROM 直连");
            err = esp_loader_connect(&g_loader, &args);
        } else {
            jb_log(3, "已在芯片内启动 flasher stub");
        }
    } else {
        err = esp_loader_connect(&g_loader, &args);
    }
    g_inited = (err == ESP_LOADER_SUCCESS);
    {
        JNIEnv *e2 = env_get();
        if (e2 && g_link && m_logline) {
            char buf[128];
            snprintf(buf, sizeof(buf), "同步结果：错误码 %d，发送 %u 字节，收到 %u 字节",
                     (int)err, (unsigned)g_tx_count, (unsigned)g_rx_count);
            jstring js = (*e2)->NewStringUTF(e2, buf);
            if (js) {
                (*e2)->CallVoidMethod(e2, g_link, m_logline, js);
                (*e2)->DeleteLocalRef(e2, js);
            }
        }
    }
    return (jint)err;
}

JNIEXPORT jstring JNICALL
Java_com_espflash_burn_NativeFlasher_nativeStats(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    char buf[128];
    snprintf(buf, sizeof(buf), "tx=%u rx=%u read_ok=%u read_timeout=%u",
             (unsigned)g_tx_count, (unsigned)g_rx_count,
             (unsigned)g_read_ok, (unsigned)g_read_timeouts);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_espflash_burn_NativeFlasher_nativeChipInfo(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    char buf[96];
    if (!g_inited) return (*env)->NewStringUTF(env, "");
    uint8_t mac[6] = {0};
    esp_loader_error_t e1 = esp_loader_read_mac(&g_loader, mac);
    uint32_t fs = 0;
    esp_loader_error_t e2 = esp_loader_flash_detect_size(&g_loader, &fs);
    if (e1 == ESP_LOADER_SUCCESS && e2 == ESP_LOADER_SUCCESS) {
        snprintf(buf, sizeof(buf), "%02X:%02X:%02X:%02X:%02X:%02X|%u",
                 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5], (unsigned)fs);
    } else if (e2 == ESP_LOADER_SUCCESS) {
        snprintf(buf, sizeof(buf), "|%u", (unsigned)fs);
    } else if (e1 == ESP_LOADER_SUCCESS) {
        snprintf(buf, sizeof(buf), "%02X:%02X:%02X:%02X:%02X:%02X|",
                 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    } else {
        buf[0] = 0;
    }
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_espflash_burn_NativeFlasher_nativeTargetName(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    if (!g_inited) return (*env)->NewStringUTF(env, "未连接");
    target_chip_t t = esp_loader_get_target(&g_loader);
    const char *n = "未知";
    switch (t) {
    case ESP32_CHIP: n = "ESP32"; break;
    case ESP32S2_CHIP: n = "ESP32-S2"; break;
    case ESP32S3_CHIP: n = "ESP32-S3"; break;
    case ESP32C3_CHIP: n = "ESP32-C3"; break;
    case ESP32C2_CHIP: n = "ESP32-C2"; break;
    case ESP32C6_CHIP: n = "ESP32-C6"; break;
    case ESP32H2_CHIP: n = "ESP32-H2"; break;
    case ESP8266_CHIP: n = "ESP8266"; break;
    default: break;
    }
    return (*env)->NewStringUTF(env, n);
}

JNIEXPORT jint JNICALL
Java_com_espflash_burn_NativeFlasher_nativeSetSpeed(JNIEnv *env, jobject thiz, jint rate)
{
    (void)env;
    (void)thiz;
    if (!g_inited) return (jint)ESP_LOADER_ERROR_FAIL;
    return (jint)esp_loader_change_transmission_rate(&g_loader, (uint32_t)rate);
}

JNIEXPORT jint JNICALL
Java_com_espflash_burn_NativeFlasher_nativeFlashFile(JNIEnv *env, jobject thiz,
                                                         jlong offset, jbyteArray data,
                                                         jint block_size, jboolean verify,
                                                         jobject sink)
{
    (void)thiz;
    if (!g_inited) return (jint)ESP_LOADER_ERROR_FAIL;
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) return (jint)ESP_LOADER_ERROR_INVALID_PARAM;

    uint8_t *buf = (uint8_t *)malloc((size_t)len);
    if (!buf) return (jint)ESP_LOADER_ERROR_FAIL;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)buf);

    esp_loader_flash_cfg_t cfg = {
        .offset = (uint32_t)offset,
        .image_size = (uint32_t)len,
        .block_size = (uint32_t)(block_size > 0 ? block_size : 4096),
        .skip_verify = (verify == JNI_TRUE) ? false : true,
    };
    esp_loader_error_t err = esp_loader_flash_start(&g_loader, &cfg);
    if (err != ESP_LOADER_SUCCESS) {
        free(buf);
        return (jint)err;
    }

    jb_logf("擦除并写入：偏移 0x%X，%u 字节，块 %u 字节",
            (unsigned)cfg.offset, (unsigned)cfg.image_size, (unsigned)cfg.block_size);

    uint32_t written = 0;
    int last_pct = -1;
    int last_step = -1;
    while (written < (uint32_t)len) {
        uint32_t chunk = (uint32_t)len - written;
        if (chunk > cfg.block_size) chunk = cfg.block_size;
        err = esp_loader_flash_write(&g_loader, &cfg, buf + written, chunk);
        if (err != ESP_LOADER_SUCCESS) {
            jb_logf("块写入失败：块 #%u，文件内偏移 0x%X，%u 字节，错误码 %d",
                    (unsigned)(written / cfg.block_size), (unsigned)written, (unsigned)chunk, (int)err);
            break;
        }
        written += chunk;
        int step = (int)(written * 10u / (uint32_t)len);
        if (step != last_step) {
            last_step = step;
            jb_logf("写入进度：%u/%u 字节（%d%%）", (unsigned)written, (unsigned)len, step * 10);
        }
        int pct = (int)((uint64_t)written * 100u / (uint64_t)len);
        if (pct != last_pct && sink && m_progress) {
            last_pct = pct;
            (*env)->CallVoidMethod(env, sink, m_progress, pct);
            check_exception(env);
        }
    }
    free(buf);
    if (err != ESP_LOADER_SUCCESS) return (jint)err;

    err = esp_loader_flash_finish(&g_loader, &cfg);
    if (err == ESP_LOADER_SUCCESS)
        jb_logf("写入结束：%u 字节，校验%s", (unsigned)len,
                cfg.skip_verify ? "跳过" : "已通过");
    else
        jb_logf("收尾失败：错误码 %d", (int)err);
    return (jint)err;
}

JNIEXPORT jint JNICALL
Java_com_espflash_burn_NativeFlasher_nativeEraseChip(JNIEnv *env, jobject thiz)
{
    (void)env;
    (void)thiz;
    if (!g_inited) return (jint)ESP_LOADER_ERROR_FAIL;
    return (jint)esp_loader_flash_erase(&g_loader);
}

JNIEXPORT jint JNICALL
Java_com_espflash_burn_NativeFlasher_nativeReadFlash(JNIEnv *env, jobject thiz,
                                                         jlong address, jlong length,
                                                         jstring path, jobject sink)
{
    (void)thiz;
    if (!g_inited) return (jint)ESP_LOADER_ERROR_FAIL;
    if (length <= 0) return (jint)ESP_LOADER_ERROR_INVALID_PARAM;

    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return (jint)ESP_LOADER_ERROR_FAIL;
    FILE *f = fopen(p, "wb");
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (!f) return (jint)ESP_LOADER_ERROR_FAIL;

    const uint32_t chunk = 4096;
    uint8_t buf[chunk];
    uint32_t done = 0;
    int last_pct = -1;
    esp_loader_error_t err = ESP_LOADER_SUCCESS;
    while (done < (uint32_t)length) {
        uint32_t n = (uint32_t)length - done;
        if (n > chunk) n = chunk;
        err = esp_loader_flash_read(&g_loader, buf, (uint32_t)(address + done), n);
        if (err != ESP_LOADER_SUCCESS) break;
        if (fwrite(buf, 1, n, f) != n) { err = ESP_LOADER_ERROR_FAIL; break; }
        done += n;
        int pct = (int)((uint64_t)done * 100u / (uint64_t)length);
        if (pct != last_pct && sink && m_progress) {
            last_pct = pct;
            (*env)->CallVoidMethod(env, sink, m_progress, pct);
            check_exception(env);
        }
    }
    fclose(f);
    return (jint)err;
}

JNIEXPORT jint JNICALL
Java_com_espflash_burn_NativeFlasher_nativeFlashFileDeflate(JNIEnv *env, jobject thiz,
                                                               jlong offset, jbyteArray data,
                                                               jlong image_size, jint block_size,
                                                               jobject sink)
{
    (void)thiz;
    if (!g_inited) return (jint)ESP_LOADER_ERROR_FAIL;
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0 || image_size <= 0) return (jint)ESP_LOADER_ERROR_INVALID_PARAM;

    uint8_t *buf = (uint8_t *)malloc((size_t)len);
    if (!buf) return (jint)ESP_LOADER_ERROR_FAIL;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)buf);

    esp_loader_flash_deflate_cfg_t cfg = {
        .offset = (uint32_t)offset,
        .image_size = (uint32_t)image_size,
        .compressed_size = (uint32_t)len,
        .block_size = (uint32_t)(block_size > 0 ? block_size : 4096),
    };
    esp_loader_error_t err = esp_loader_flash_deflate_start(&g_loader, &cfg);
    if (err != ESP_LOADER_SUCCESS) {
        free(buf);
        return (jint)err;
    }

    uint32_t sent = 0;
    int last_pct = -1;
    while (sent < (uint32_t)len) {
        uint32_t chunk = (uint32_t)len - sent;
        if (chunk > cfg.block_size) chunk = cfg.block_size;
        err = esp_loader_flash_deflate_write(&g_loader, &cfg, buf + sent, chunk);
        if (err != ESP_LOADER_SUCCESS) break;
        sent += chunk;
        int pct = (int)((uint64_t)sent * 100u / (uint64_t)len);
        if (pct != last_pct && sink && m_progress) {
            last_pct = pct;
            (*env)->CallVoidMethod(env, sink, m_progress, pct);
            check_exception(env);
        }
    }
    free(buf);
    if (err != ESP_LOADER_SUCCESS) return (jint)err;

    err = esp_loader_flash_deflate_finish(&g_loader, &cfg);
    return (jint)err;
}

JNIEXPORT jstring JNICALL
Java_com_espflash_burn_NativeFlasher_nativeErrorText(JNIEnv *env, jobject thiz, jint code)
{
    (void)thiz;
    char buf[128];
    snprintf(buf, sizeof(buf), "%s", err_text((int)code));
    return (*env)->NewStringUTF(env, buf);
}
