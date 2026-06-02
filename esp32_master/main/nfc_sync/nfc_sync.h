/**
 * @file nfc_sync.h
 * @brief TOMS Master — NFC-DEP Initiator synchronization interface.
 *
 * Replaces the pogo-pin UART dock mechanism. The Master PN532 continuously
 * polls for NFC-DEP targets. When a Slave is tapped:
 *   1. DEP link established → Slave NFCID3 contains its eFuse MAC (= UID)
 *   2. Master sends compact NFC_BOARD_CMD (fare_id + seat + timestamp)
 *   3. Slave responds with NFC_ACK
 *   4. Tap complete. Slave processes boarding locally.
 *   5. Slave confirms via ESP-NOW PASSENGER_BOARD (async).
 *
 * Pin assignments (reuses former UART/dock GPIOs):
 *   SDA  → GPIO 2  (was UART TX)
 *   SCL  → GPIO 3  (was UART RX)
 *   IRQ  → GPIO 4  (was dock detect)
 */

#ifndef TOMS_NFC_SYNC_H
#define TOMS_NFC_SYNC_H

#include <stdint.h>
#include <stdbool.h>
#include "protocol.h"

/* Set to 1 to enable NFC (PN532), or 0 to completely disable NFC and use ESP-NOW only */
#define TOMS_USE_NFC 0

#ifdef __cplusplus
extern "C" {
#endif

/* ── Pin Configuration ────────────────────────────────────────────────── */

#define TOMS_NFC_I2C_SDA        2       /**< I2C data (was UART TX) */
#define TOMS_NFC_I2C_SCL        3       /**< I2C clock (was UART RX) */
#define TOMS_NFC_IRQ_PIN        4       /**< PN532 IRQ (was dock detect) */
#define TOMS_NFC_I2C_PORT       0       /**< I2C port number */
#define TOMS_NFC_I2C_FREQ       400000  /**< I2C frequency (400 kHz) */

/** Polling interval for InJumpForDEP (ms) */
#define TOMS_NFC_POLL_TIMEOUT_MS    250

/** Anti-double-tap cooldown (ms) */
#define TOMS_NFC_COOLDOWN_MS        1500

/* ── State Machine ────────────────────────────────────────────────────── */

typedef enum {
    NFC_STATE_IDLE,             /**< Polling for targets */
    NFC_STATE_CONNECTED,        /**< DEP link established, NFCID3 read */
    NFC_STATE_DISPATCHING,      /**< NFC_BOARD_CMD sent, waiting ACK */
    NFC_STATE_COOLDOWN,         /**< Post-tap cooldown */
    NFC_STATE_ERROR,            /**< Error — will reset */
} toms_nfc_state_t;

/* ── Callback Types ───────────────────────────────────────────────────── */

/**
 * @brief Called when a Slave is detected via NFC tap.
 *
 * The uid contains the Slave's eFuse MAC extracted from NFCID3.
 * The Master should use this for optimistic passenger counting.
 *
 * @param slave_uid  Slave UID (first 6 bytes of NFCID3 = eFuse MAC).
 * @param uid_len    Length of UID (6 bytes).
 */
typedef void (*toms_nfc_tap_cb_t)(const uint8_t *slave_uid, uint8_t uid_len);

/**
 * @brief Called when NFC connection state changes.
 */
typedef void (*toms_nfc_connect_cb_t)(bool connected);

/* ── Public API ───────────────────────────────────────────────────────── */

/**
 * @brief Initialize the NFC sync subsystem (PN532 as Initiator).
 * @return ESP_OK on success.
 */
int toms_nfc_sync_init(void);

/**
 * @brief Get current NFC sync state.
 */
toms_nfc_state_t toms_nfc_sync_get_state(void);

/**
 * @brief Register callback for NFC tap detection (optimistic count).
 */
void toms_nfc_sync_set_tap_cb(toms_nfc_tap_cb_t cb);

/**
 * @brief Register callback for NFC connection state changes.
 */
void toms_nfc_sync_set_connect_cb(toms_nfc_connect_cb_t cb);

/**
 * @brief Set the active fare_id to send in the next NFC tap.
 *
 * @param fare_id    Fare dictionary index.
 * @param seat       Seat number.
 */
void toms_nfc_sync_set_fare(uint8_t fare_id, uint8_t seat);

/**
 * @brief NFC sync polling task (FreeRTOS). Never returns.
 */
void toms_nfc_sync_task(void *arg);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_NFC_SYNC_H */
