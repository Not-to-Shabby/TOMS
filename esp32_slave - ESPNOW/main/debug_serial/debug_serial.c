/**
 * @file debug_serial.c
 * @brief TOMS Slave — UART debug output implementation.
 *
 * Uses UART1 (GPIO17 TX → STM32 PA3 RX) to emit newline-delimited
 * JSON packets at 115200 baud for the STM32 Blue Pill monitor.
 *
 * All functions are no-ops when TOMS_DEBUG_SERIAL_ENABLED == 0.
 */

#include "debug_serial.h"

#if TOMS_DEBUG_SERIAL_ENABLED

#include <stdio.h>
#include <string.h>
#include "driver/uart.h"
#include "driver/gpio.h"
#include "esp_log.h"

static const char *TAG = "toms_dbg";

/* ── Lookup tables ────────────────────────────────────────────────────── */

static const char *s_btn_name[] = {
    "PRESS",       /* TOMS_BTN_PRESS      */
    "LONG_PRESS",  /* TOMS_BTN_LONG_PRESS */
    "RELEASE",     /* TOMS_BTN_RELEASE    */
    "HOLD_1S",     /* TOMS_BTN_HOLD_1S    */
    "HOLD_2S",     /* TOMS_BTN_HOLD_2S    */
    "HOLD_3S",     /* TOMS_BTN_HOLD_3S    */
};
#define BTN_NAME_COUNT  (sizeof(s_btn_name) / sizeof(s_btn_name[0]))

static const char *msg_type_name(uint8_t t)
{
    switch (t) {
    case 0x01: return "BUTTON_PRESS";
    case 0x02: return "BUTTON_PRESS";   /* alias */
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

/* ── Internal send ────────────────────────────────────────────────────── */

static void dbg_send(const char *line)
{
    /* Append newline and write to UART1 */
    uart_write_bytes(TOMS_DBG_UART_PORT, line, strlen(line));
    uart_write_bytes(TOMS_DBG_UART_PORT, "\n", 1);
}

/* ── Public API ───────────────────────────────────────────────────────── */

void debug_serial_init(void)
{
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
        ESP_LOGE(TAG, "debug_serial: uart_param_config failed (%d)", err);
        return;
    }

    err = uart_set_pin(TOMS_DBG_UART_PORT,
                       TOMS_DBG_TX_GPIO,   /* TX */
                       TOMS_DBG_RX_GPIO,   /* RX (optional) */
                       UART_PIN_NO_CHANGE, /* RTS */
                       UART_PIN_NO_CHANGE  /* CTS */);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "debug_serial: uart_set_pin failed (%d)", err);
        return;
    }

    err = uart_driver_install(TOMS_DBG_UART_PORT,
                              512,  /* RX ring buf */
                              512,  /* TX ring buf */
                              0, NULL, 0);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "debug_serial: uart_driver_install failed (%d)", err);
        return;
    }

    ESP_LOGI(TAG, "Debug UART ready on GPIO%d (TX) at %d baud",
             TOMS_DBG_TX_GPIO, TOMS_DBG_BAUD_RATE);

    dbg_send("{\"dbg\":\"boot\",\"msg\":\"TOMS Slave debug UART ready\"}");
}

void debug_serial_send_state(const char    *screen_name,
                              uint16_t       batt_mv,
                              uint8_t        batt_pct,
                              const uint8_t *master_mac,
                              uint8_t        seq)
{
    char buf[192];
    snprintf(buf, sizeof(buf),
             "{\"dbg\":\"state\","
             "\"screen\":\"%s\","
             "\"batt_mv\":%u,"
             "\"batt_pct\":%u,"
             "\"master\":\"%02X:%02X:%02X:%02X:%02X:%02X\","
             "\"seq\":%u}",
             screen_name,
             (unsigned)batt_mv,
             (unsigned)batt_pct,
             master_mac[0], master_mac[1], master_mac[2],
             master_mac[3], master_mac[4], master_mac[5],
             (unsigned)seq);
    dbg_send(buf);
}

void debug_serial_send_button(toms_button_event_t event)
{
    const char *name = (event < (toms_button_event_t)BTN_NAME_COUNT)
                       ? s_btn_name[event]
                       : "UNKNOWN";
    char buf[64];
    snprintf(buf, sizeof(buf),
             "{\"dbg\":\"btn\",\"event\":\"%s\"}", name);
    dbg_send(buf);
}

void debug_serial_send_espnow_rx(uint8_t msg_type, uint16_t fare, uint8_t seat)
{
    char buf[128];
    snprintf(buf, sizeof(buf),
             "{\"dbg\":\"espnow_rx\","
             "\"type\":\"%s\","
             "\"fare\":%u,"
             "\"seat\":%u}",
             msg_type_name(msg_type),
             (unsigned)fare,
             (unsigned)seat);
    dbg_send(buf);
}

void debug_serial_send_espnow_tx(uint8_t msg_type, uint8_t seq)
{
    char buf[96];
    snprintf(buf, sizeof(buf),
             "{\"dbg\":\"espnow_tx\","
             "\"type\":\"%s\","
             "\"seq\":%u}",
             msg_type_name(msg_type),
             (unsigned)seq);
    dbg_send(buf);
}

void debug_serial_send_batt(uint16_t mv, uint8_t pct)
{
    char buf[64];
    snprintf(buf, sizeof(buf),
             "{\"dbg\":\"batt\",\"mv\":%u,\"pct\":%u}",
             (unsigned)mv, (unsigned)pct);
    dbg_send(buf);
}

void debug_serial_send_timeout(const char *reason)
{
    char buf[96];
    snprintf(buf, sizeof(buf),
             "{\"dbg\":\"timeout\",\"reason\":\"%s\"}", reason);
    dbg_send(buf);
}

void debug_serial_send_error(const char *msg)
{
    char buf[128];
    snprintf(buf, sizeof(buf),
             "{\"dbg\":\"error\",\"msg\":\"%s\"}", msg);
    dbg_send(buf);
}

#endif /* TOMS_DEBUG_SERIAL_ENABLED */
