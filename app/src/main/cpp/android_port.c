/*
 * Android 端口层：把 esp-serial-flasher 的 io 回调转发到 Java 的 USB 串口。
 * 时序/极性沿用官方 linux_port.c 的 DTR/RTS 方案（含 USB-Serial-JTAG 专用序列）。
 */
#include "esp_loader_io.h"

#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* 由 jni_bridge.c 提供 */
int  jb_read(uint8_t *dst, uint16_t size, uint32_t timeout_ms);
int  jb_write(const uint8_t *src, uint16_t size, uint32_t timeout_ms);
void jb_set_dtr(int v);
void jb_set_rts(int v);
int  jb_set_baud(uint32_t baud);
void jb_purge_input(void);
int  jb_reopen(uint32_t timeout_ms);
int  jb_is_usb_jtag(void);
void jb_log(int level, const char *msg);

static int64_t g_deadline_ms;

static int64_t now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000LL + (int64_t)ts.tv_nsec / 1000000LL;
}

static esp_loader_error_t p_init(esp_loader_port_t *port)
{
    (void)port;
    return ESP_LOADER_SUCCESS;
}

static void p_start_timer(esp_loader_port_t *port, uint32_t ms)
{
    (void)port;
    g_deadline_ms = now_ms() + (int64_t)ms;
}

static uint32_t p_remaining_time(esp_loader_port_t *port)
{
    (void)port;
    int64_t d = g_deadline_ms - now_ms();
    return d > 0 ? (uint32_t)d : 0;
}

static void p_delay_ms(esp_loader_port_t *port, uint32_t ms)
{
    (void)port;
    usleep((useconds_t)ms * 1000);
}

static void p_set_lines(int dtr, int rts)
{
    jb_set_dtr(dtr);
    jb_set_rts(rts);
}

/* 进下载模式：由 Java 层在握手前完成（复位 + 重开端口），这里必须什么都不做，
 * 否则库内部这第二次复位会让 USB 重新枚举，握手用到的还是旧连接，写就会一直失败。 */
static void p_enter_bootloader(esp_loader_port_t *port)
{
    (void)port;
    jb_log((int)ESP_LOADER_LOG_LEVEL_INFO, "内部复位跳过（已由 App 完成复位）");
}

/* 复位目标：只脉冲 RTS，别动 DTR（避免误入下载模式） */
static void p_reset_target(esp_loader_port_t *port)
{
    (void)port;
    p_set_lines(0, 1);
    usleep(100000);
    p_set_lines(0, 0);
    jb_purge_input();
}

static esp_loader_error_t p_write(esp_loader_port_t *port, const uint8_t *data,
                                  uint16_t size, uint32_t timeout)
{
    (void)port;
    return jb_write(data, size, timeout) ? ESP_LOADER_SUCCESS : ESP_LOADER_ERROR_TIMEOUT;
}

static esp_loader_error_t p_read(esp_loader_port_t *port, uint8_t *data,
                                 uint16_t size, uint32_t timeout)
{
    (void)port;
    /* 库把 remaining_time 作为超时，可能算出 0ms：给个下限，别一上来就判超时。
     * 上限不能压低：擦除是按 10000ms/MB 给的（16MB 片子上一条擦除命令要几十秒），
     * 卡到 1200ms 就会在擦除还没回包时误判超时。 */
    if (timeout < 20) timeout = 20;
    /* 擦除 16 MB 时 stub 要几十秒才回包，上一次卡在 60 秒上限就误判超时了，
       上限放到 190 秒（正好覆盖 16 MB 擦除的 168 秒预算）。 */
    if (timeout > 190000) timeout = 190000;
    return jb_read(data, size, timeout) == (int)size ? ESP_LOADER_SUCCESS : ESP_LOADER_ERROR_TIMEOUT;
}

static esp_loader_error_t p_change_rate(esp_loader_port_t *port, uint32_t rate)
{
    (void)port;
    return jb_set_baud(rate) ? ESP_LOADER_SUCCESS : ESP_LOADER_ERROR_FAIL;
}

static void p_log(esp_loader_port_t *port, esp_loader_log_level_t level,
                  const char *fmt, va_list args)
{
    (void)port;
    char buf[512];
    vsnprintf(buf, sizeof(buf), fmt, args);
    jb_log((int)level, buf);
}

const esp_loader_port_ops_t android_port_ops = {
    .init = p_init,
    .deinit = NULL,
    .enter_bootloader = p_enter_bootloader,
    .reset_target = p_reset_target,
    .start_timer = p_start_timer,
    .remaining_time = p_remaining_time,
    .delay_ms = p_delay_ms,
    .log = p_log,
    .log_hex = NULL,
    .change_transmission_rate = p_change_rate,
    .write = p_write,
    .read = p_read,
    .spi_set_cs = NULL,
    .sdio_write = NULL,
    .sdio_read = NULL,
    .sdio_card_init = NULL,
};
