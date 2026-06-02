/**
 * @file debug_serial.h
 * @brief TOMS Master — UART debug output for STM32 monitor.
 *
 * Sends newline-delimited JSON packets over UART1 (GPIO5 TX, GPIO6 RX)
 * at 115200 baud so the STM32 Blue Pill monitor can track master state.
 */

#ifndef TOMS_DEBUG_SERIAL_H
#define TOMS_DEBUG_SERIAL_H

#include <stdint.h>
#include <stdbool.h>
#include "driver/uart.h"

#ifdef __cplusplus
extern "C" {
#endif

#ifndef TOMS_DEBUG_SERIAL_ENABLED
#define TOMS_DEBUG_SERIAL_ENABLED  1
#endif

#define TOMS_DBG_UART_PORT    UART_NUM_1
#define TOMS_DBG_TX_GPIO      5
#define TOMS_DBG_RX_GPIO      6
#define TOMS_DBG_BAUD_RATE    115200

#if TOMS_DEBUG_SERIAL_ENABLED

void debug_serial_init(void);

void debug_serial_send_state(bool usb_connected,
                             const uint8_t *slave_mac,
                             uint16_t batt_mv,
                             uint8_t batt_pct,
                             uint8_t active_route);

void debug_serial_send_espnow_rx(uint8_t msg_type, const uint8_t *src_mac, uint8_t seq);

void debug_serial_send_espnow_tx(uint8_t msg_type, const uint8_t *dst_mac, uint8_t seq);

void debug_serial_send_usb_rx(const char *cmd);

void debug_serial_send_usb_tx(const char *evt);

void debug_serial_send_error(const char *msg);

#else

static inline void debug_serial_init(void) {}
static inline void debug_serial_send_state(bool uc, const uint8_t *mac, uint16_t mv, uint8_t pct, uint8_t r)
    { (void)uc; (void)mac; (void)mv; (void)pct; (void)r; }
static inline void debug_serial_send_espnow_rx(uint8_t t, const uint8_t *m, uint8_t s) { (void)t; (void)m; (void)s; }
static inline void debug_serial_send_espnow_tx(uint8_t t, const uint8_t *m, uint8_t s) { (void)t; (void)m; (void)s; }
static inline void debug_serial_send_usb_rx(const char *cmd) { (void)cmd; }
static inline void debug_serial_send_usb_tx(const char *evt) { (void)evt; }
static inline void debug_serial_send_error(const char *msg) { (void)msg; }

#endif

#ifdef __cplusplus
}
#endif

#endif /* TOMS_DEBUG_SERIAL_H */
