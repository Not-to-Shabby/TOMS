#include "debug_serial.h"

#if TOMS_DEBUG_SERIAL_ENABLED

#include <stdio.h>
#include <string.h>
#include "driver/uart.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

static const char *TAG = "toms_dbg_m";
static SemaphoreHandle_t s_dbg_mutex = NULL;

static const char *msg_type_name(uint8_t t)
{
    switch (t) {
    case 0x01: return "BUTTON_PRESS";
    case 0x02: return "BUTTON_PRESS";
    case 0x04: return "RELEASE";
    case 0x10: return "PASSENGER_BOARD";
    case 0x11: return "HEARTBEAT";
    case 0x12: return "BOARD_COMMAND";
    case 0x13: return "ACK_SLAVE";
    case 0x14: return "ACK_MASTER";
    case 0x15: return "CONFIG_SYNC";
    case 0x16: return "FARE_TABLE_UPDATE";
    case 0x17: return "ALARM_CMD";
    default:   return "UNKNOWN";
    }
}

static void dbg_send(const char *line)
{
    if (!s_dbg_mutex) return;
    xSemaphoreTake(s_dbg_mutex, portMAX_DELAY);
    uart_write_bytes(TOMS_DBG_UART_PORT, line, strlen(line));
    uart_write_bytes(TOMS_DBG_UART_PORT, "\n", 1);
    xSemaphoreGive(s_dbg_mutex);
}

void debug_serial_init(void)
{
    /* Create mutex first — must exist before any dbg_send call */
    if (!s_dbg_mutex) {
        s_dbg_mutex = xSemaphoreCreateMutex();
    }

    if (uart_is_driver_installed(TOMS_DBG_UART_PORT)) {
        ESP_LOGI(TAG, "Debug serial: using already installed UART%d driver", TOMS_DBG_UART_PORT);
        dbg_send("{\"dbg\":\"boot\",\"msg\":\"TOMS Master debug UART ready (Shared)\"}");
        return;
    }

    uart_config_t cfg = {
        .baud_rate  = TOMS_DBG_BAUD_RATE,
        .data_bits  = UART_DATA_8_BITS,
        .parity     = UART_PARITY_DISABLE,
        .stop_bits  = UART_STOP_BITS_1,
        .flow_ctrl  = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };

    esp_err_t err = uart_param_config(TOMS_DBG_UART_PORT, &cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "uart_param_config failed (%d)", err);
        return;
    }

    err = uart_set_pin(TOMS_DBG_UART_PORT,
                       TOMS_DBG_TX_GPIO,
                       TOMS_DBG_RX_GPIO,
                       UART_PIN_NO_CHANGE,
                       UART_PIN_NO_CHANGE);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "uart_set_pin failed (%d)", err);
        return;
    }

    err = uart_driver_install(TOMS_DBG_UART_PORT, 512, 512, 0, NULL, 0);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "uart_driver_install failed (%d)", err);
        return;
    }

    ESP_LOGI(TAG, "Debug UART ready on GPIO%d (TX) at %d baud", TOMS_DBG_TX_GPIO, TOMS_DBG_BAUD_RATE);
    dbg_send("{\"dbg\":\"boot\",\"msg\":\"TOMS Master debug UART ready\"}");
}

void debug_serial_send_state(bool usb_connected,
                             const uint8_t *slave_mac,
                             uint16_t batt_mv,
                             uint8_t batt_pct,
                             uint8_t active_route)
{
    char buf[192];
    if (slave_mac) {
        snprintf(buf, sizeof(buf),
                 "{\"dbg\":\"state\",\"usb_connected\":%d,"
                 "\"slave\":\"%02X:%02X:%02X:%02X:%02X:%02X\","
                 "\"batt_mv\":%u,\"batt_pct\":%u,\"active_route\":%u}",
                 usb_connected ? 1 : 0,
                 slave_mac[0], slave_mac[1], slave_mac[2],
                 slave_mac[3], slave_mac[4], slave_mac[5],
                 (unsigned)batt_mv, (unsigned)batt_pct, (unsigned)active_route);
    } else {
        snprintf(buf, sizeof(buf),
                 "{\"dbg\":\"state\",\"usb_connected\":%d,"
                 "\"slave\":\"00:00:00:00:00:00\","
                 "\"batt_mv\":%u,\"batt_pct\":%u,\"active_route\":%u}",
                 usb_connected ? 1 : 0,
                 (unsigned)batt_mv, (unsigned)batt_pct, (unsigned)active_route);
    }
    dbg_send(buf);
}

void debug_serial_send_espnow_rx(uint8_t msg_type, const uint8_t *src_mac, uint8_t seq)
{
    char buf[160];
    if (src_mac) {
        snprintf(buf, sizeof(buf),
                 "{\"dbg\":\"espnow_rx\",\"type\":\"%s\","
                 "\"mac\":\"%02X:%02X:%02X:%02X:%02X:%02X\",\"seq\":%u}",
                 msg_type_name(msg_type),
                 src_mac[0], src_mac[1], src_mac[2],
                 src_mac[3], src_mac[4], src_mac[5],
                 (unsigned)seq);
    } else {
        snprintf(buf, sizeof(buf),
                 "{\"dbg\":\"espnow_rx\",\"type\":\"%s\",\"mac\":\"00:00:00:00:00:00\",\"seq\":%u}",
                 msg_type_name(msg_type), (unsigned)seq);
    }
    dbg_send(buf);
}

void debug_serial_send_espnow_tx(uint8_t msg_type, const uint8_t *dst_mac, uint8_t seq)
{
    char buf[160];
    if (dst_mac) {
        snprintf(buf, sizeof(buf),
                 "{\"dbg\":\"espnow_tx\",\"type\":\"%s\","
                 "\"mac\":\"%02X:%02X:%02X:%02X:%02X:%02X\",\"seq\":%u}",
                 msg_type_name(msg_type),
                 dst_mac[0], dst_mac[1], dst_mac[2],
                 dst_mac[3], dst_mac[4], dst_mac[5],
                 (unsigned)seq);
    } else {
        snprintf(buf, sizeof(buf),
                 "{\"dbg\":\"espnow_tx\",\"type\":\"%s\",\"mac\":\"00:00:00:00:00:00\",\"seq\":%u}",
                 msg_type_name(msg_type), (unsigned)seq);
    }
    dbg_send(buf);
}

void debug_serial_send_usb_rx(const char *cmd)
{
    char buf[128];
    snprintf(buf, sizeof(buf), "{\"dbg\":\"usb_rx\",\"cmd\":\"%s\"}", cmd);
    dbg_send(buf);
}

void debug_serial_send_usb_tx(const char *evt)
{
    char buf[128];
    snprintf(buf, sizeof(buf), "{\"dbg\":\"usb_tx\",\"evt\":\"%s\"}", evt);
    dbg_send(buf);
}

void debug_serial_send_error(const char *msg)
{
    char buf[128];
    snprintf(buf, sizeof(buf), "{\"dbg\":\"error\",\"msg\":\"%s\"}", msg);
    dbg_send(buf);
}

#endif
