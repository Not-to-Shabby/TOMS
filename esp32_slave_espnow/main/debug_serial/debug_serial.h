/**
 * @file debug_serial.h
 * @brief TOMS Slave — UART debug output for STM32 monitor.
 *
 * Sends newline-delimited JSON packets over UART1 (GPIO17 TX, GPIO18 RX)
 * at 115200 baud so the STM32 Blue Pill monitor can track slave state.
 *
 * This module is compile-time optional via TOMS_DEBUG_SERIAL_ENABLED.
 * Set to 0 to remove all UART overhead from production builds.
 */

#ifndef TOMS_DEBUG_SERIAL_H
#define TOMS_DEBUG_SERIAL_H

#include <stdint.h>
#include <stdbool.h>
#include "protocol.h"
#include "button/button.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── Build-time enable/disable ───────────────────────────────────────── */

#ifndef TOMS_DEBUG_SERIAL_ENABLED
#define TOMS_DEBUG_SERIAL_ENABLED  1   /* Set to 0 to strip all debug output */
#endif

/* ── Hardware configuration ──────────────────────────────────────────── */

/** UART port used for debug output (UART_NUM_1 is separate from logging UART_NUM_0) */
#define TOMS_DBG_UART_PORT    UART_NUM_1

/** TX pin (connects to STM32 PA3 RX) */
#define TOMS_DBG_TX_GPIO      17

/** RX pin (connects to STM32 PA2 TX — for future PC→slave commands) */
#define TOMS_DBG_RX_GPIO      18

/** Baud rate — matches STM32 monitor */
#define TOMS_DBG_BAUD_RATE    115200

/* ── Public API ──────────────────────────────────────────────────────── */

#if TOMS_DEBUG_SERIAL_ENABLED

/**
 * @brief Initialize the debug UART.
 *
 * Must be called once in app_main() before any debug_serial_send_*() calls.
 * Safe to call even if no STM32 monitor is connected.
 */
void debug_serial_init(void);

/**
 * @brief Report the current UI screen state and battery level.
 *
 * Call after any screen transition.
 *
 * @param screen_name  Lowercase name of the screen (e.g. "fare", "welcome").
 * @param batt_mv      Battery voltage in millivolts.
 * @param batt_pct     Battery percentage (0–100).
 * @param master_mac   Master MAC address bytes (6 bytes).
 * @param seq          Current sequence counter.
 */
void debug_serial_send_state(const char *screen_name,
                              uint16_t    batt_mv,
                              uint8_t     batt_pct,
                              const uint8_t *master_mac,
                              uint8_t     seq);

/**
 * @brief Report a button event.
 *
 * @param event  The button event type.
 */
void debug_serial_send_button(toms_button_event_t event);

/**
 * @brief Report a received ESP-NOW packet.
 *
 * @param msg_type   Message type from toms_msg_type_t.
 * @param fare       Fare in centavos (only relevant for BOARD_COMMAND).
 * @param seat       Seat number (only relevant for BOARD_COMMAND).
 */
void debug_serial_send_espnow_rx(uint8_t msg_type, uint16_t fare, uint8_t seat);

/**
 * @brief Report a transmitted ESP-NOW packet.
 *
 * @param msg_type  Message type from toms_msg_type_t.
 * @param seq       Sequence number used.
 */
void debug_serial_send_espnow_tx(uint8_t msg_type, uint8_t seq);

/**
 * @brief Report a battery reading.
 *
 * @param mv   Voltage in millivolts.
 * @param pct  Percentage (0–100).
 */
void debug_serial_send_batt(uint16_t mv, uint8_t pct);

/**
 * @brief Report a timeout event.
 *
 * @param reason  Short description (e.g. "board_wait", "release_ack").
 */
void debug_serial_send_timeout(const char *reason);

/**
 * @brief Report an error.
 *
 * @param msg  Error message string.
 */
void debug_serial_send_error(const char *msg);

#else /* TOMS_DEBUG_SERIAL_ENABLED == 0 */

/* Strip all calls in production builds */
static inline void debug_serial_init(void) {}
static inline void debug_serial_send_state(const char *s, uint16_t mv, uint8_t pct,
                                            const uint8_t *mac, uint8_t seq)
    { (void)s; (void)mv; (void)pct; (void)mac; (void)seq; }
static inline void debug_serial_send_button(toms_button_event_t e) { (void)e; }
static inline void debug_serial_send_espnow_rx(uint8_t t, uint16_t f, uint8_t s)
    { (void)t; (void)f; (void)s; }
static inline void debug_serial_send_espnow_tx(uint8_t t, uint8_t s) { (void)t; (void)s; }
static inline void debug_serial_send_batt(uint16_t mv, uint8_t pct) { (void)mv; (void)pct; }
static inline void debug_serial_send_timeout(const char *r) { (void)r; }
static inline void debug_serial_send_error(const char *m) { (void)m; }

#endif /* TOMS_DEBUG_SERIAL_ENABLED */

#ifdef __cplusplus
}
#endif

#endif /* TOMS_DEBUG_SERIAL_H */
