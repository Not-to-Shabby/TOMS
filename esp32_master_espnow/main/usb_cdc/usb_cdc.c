/**
 * @file usb_cdc.c
 * @brief TOMS USB CDC-ACM device implementation using TinyUSB.
 */

#include "usb_cdc.h"

#include <string.h>
#include <stdio.h>
#include "esp_log.h"
#include "tinyusb.h"
#include "tusb_cdc_acm.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "toms_usb_cdc";

/* ── Internal State ───────────────────────────────────────────────────── */

static toms_usb_recv_cb_t s_recv_cb    = NULL;
static bool               s_connected  = false;
static bool               s_initialized = false;

/* Line buffer for accumulating incoming bytes until '\n' */
static char    s_line_buf[TOMS_USB_MAX_MSG_LEN];
static size_t  s_line_pos = 0;

/* ── TinyUSB CDC Callbacks ────────────────────────────────────────────── */

static void tinyusb_cdc_rx_callback(int itf, cdcacm_event_t *event)
{
    (void)itf;
    (void)event;
    /* Data is available — will be read in the task loop */
}

static void tinyusb_cdc_line_state_callback(int itf, cdcacm_event_t *event)
{
    (void)itf;
    int dtr = event->line_state_changed_data.dtr;
    int rts = event->line_state_changed_data.rts;

    s_connected = (dtr != 0);
    ESP_LOGI(TAG, "Line state: DTR=%d, RTS=%d, connected=%d", dtr, rts, s_connected);
}

/* ── Public API ───────────────────────────────────────────────────────── */

int toms_usb_cdc_init(void)
{
    if (s_initialized) {
        return ESP_OK;
    }

    ESP_LOGI(TAG, "Initializing USB CDC device");

    /* TinyUSB driver configuration */
    const tinyusb_config_t tusb_cfg = {
        .device_descriptor = NULL,       /* Use default from sdkconfig */
        .string_descriptor = NULL,
        .external_phy      = false,
        .configuration_descriptor = NULL,
    };

    esp_err_t err = tinyusb_driver_install(&tusb_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "TinyUSB install failed: %s", esp_err_to_name(err));
        return (int)err;
    }

    /* CDC-ACM configuration */
    tinyusb_config_cdcacm_t acm_cfg = {
        .usb_dev       = TINYUSB_USBDEV_0,
        .cdc_port      = TINYUSB_CDC_ACM_0,
        .rx_unread_buf_sz = TOMS_USB_RX_BUF_SIZE,
        .callback_rx              = tinyusb_cdc_rx_callback,
        .callback_line_state_changed = tinyusb_cdc_line_state_callback,
        .callback_line_coding_changed = NULL,
    };

    err = tusb_cdc_acm_init(&acm_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "CDC-ACM init failed: %s", esp_err_to_name(err));
        return (int)err;
    }

    s_initialized = true;
    ESP_LOGI(TAG, "USB CDC device ready");
    return ESP_OK;
}

void toms_usb_cdc_set_recv_cb(toms_usb_recv_cb_t cb)
{
    s_recv_cb = cb;
}

bool toms_usb_cdc_is_connected(void)
{
    return s_connected;
}

int toms_usb_cdc_send(const char *json)
{
    if (!s_initialized || !json) return -1;

    size_t len = strlen(json);
    if (len == 0) return 0;

    /* Send JSON data */
    size_t written = 0;
    esp_err_t err = tinyusb_cdcacm_write_queue(TINYUSB_CDC_ACM_0,
                                                (const uint8_t *)json, len);
    if (err != ESP_OK) return -1;

    /* Send newline delimiter */
    const uint8_t newline = '\n';
    tinyusb_cdcacm_write_queue(TINYUSB_CDC_ACM_0, &newline, 1);

    /* Flush */
    err = tinyusb_cdcacm_write_flush(TINYUSB_CDC_ACM_0, pdMS_TO_TICKS(100));
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "USB write flush timeout");
        return -1;
    }

    written = len + 1;
    return (int)written;
}

int toms_usb_cdc_send_raw(const uint8_t *data, size_t len)
{
    if (!s_initialized || !data || len == 0) return -1;

    esp_err_t err = tinyusb_cdcacm_write_queue(TINYUSB_CDC_ACM_0, data, len);
    if (err != ESP_OK) return -1;

    err = tinyusb_cdcacm_write_flush(TINYUSB_CDC_ACM_0, pdMS_TO_TICKS(100));
    return (err == ESP_OK) ? (int)len : -1;
}

/* ── Processing Task ──────────────────────────────────────────────────── */

void toms_usb_cdc_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "USB CDC task started");

    uint8_t rx_buf[64];

    while (1) {
        if (!s_initialized) {
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }

        /* Read available data */
        size_t rx_size = 0;
        esp_err_t err = tinyusb_cdcacm_read(TINYUSB_CDC_ACM_0,
                                              rx_buf, sizeof(rx_buf), &rx_size);
        if (err != ESP_OK || rx_size == 0) {
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }

        /* Process byte by byte, looking for newline delimiters */
        for (size_t i = 0; i < rx_size; i++) {
            char ch = (char)rx_buf[i];

            if (ch == '\n' || ch == '\r') {
                if (s_line_pos > 0) {
                    s_line_buf[s_line_pos] = '\0';

                    /* Invoke callback */
                    if (s_recv_cb) {
                        s_recv_cb(s_line_buf, s_line_pos);
                    } else {
                        ESP_LOGD(TAG, "USB RX (no cb): %s", s_line_buf);
                    }

                    s_line_pos = 0;
                }
            } else {
                if (s_line_pos < TOMS_USB_MAX_MSG_LEN - 1) {
                    s_line_buf[s_line_pos++] = ch;
                } else {
                    ESP_LOGW(TAG, "USB line buffer overflow — discarding");
                    s_line_pos = 0;
                }
            }
        }
    }
}
