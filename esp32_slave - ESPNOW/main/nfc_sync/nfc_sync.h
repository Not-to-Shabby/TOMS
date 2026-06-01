/**
 * @file nfc_sync.h
 * @brief TOMS Slave — NFC-DEP Target synchronization interface.
 *
 * The Slave PN532 operates as an NFC-DEP Target. It waits passively
 * for the Master Initiator to establish a P2P link. When tapped:
 *   1. Master sends NFC_BOARD_CMD (fare_id + seat + timestamp)
 *   2. Slave looks up fare_id in local dictionary
 *   3. Slave responds with NFC_ACK
 *   4. Slave processes boarding locally (fare screen, QR)
 *   5. Slave sends PASSENGER_BOARD via ESP-NOW (confirmation)
 *
 * Pin assignments:
 *   SDA  → GPIO 2  (was UART TX)
 *   SCL  → GPIO 3  (was UART RX)
 *   IRQ  → GPIO 5  (GPIO 4 is still the wake button)
 */

#ifndef TOMS_SLAVE_NFC_SYNC_H
#define TOMS_SLAVE_NFC_SYNC_H

#include <stdint.h>
#include <stdbool.h>
#include "protocol.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── Pin Configuration ────────────────────────────────────────────────── */

#define TOMS_NFC_I2C_SDA        2       /**< I2C data (was UART TX) */
#define TOMS_NFC_I2C_SCL        3       /**< I2C clock (was UART RX) */
#define TOMS_NFC_IRQ_PIN        5       /**< PN532 IRQ (GPIO 4 = button) */
#define TOMS_NFC_I2C_PORT       0       /**< I2C port number */
#define TOMS_NFC_I2C_FREQ       400000  /**< I2C frequency (400 kHz) */

/* ── Callback Type ────────────────────────────────────────────────────── */

/**
 * @brief Called when a validated BOARD_COMMAND is received via NFC tap.
 *
 * The compact NFC_BOARD_CMD has been expanded to a full toms_board_command_t
 * using the local fare dictionary, so the existing boarding flow works
 * without modification.
 */
typedef void (*toms_slave_nfc_board_cb_t)(const toms_board_command_t *cmd);

/* ── Public API ───────────────────────────────────────────────────────── */

/**
 * @brief Initialize the Slave NFC subsystem (PN532 as Target).
 * @return ESP_OK on success.
 */
int toms_slave_nfc_init(void);

/**
 * @brief Register callback for validated boarding commands.
 */
void toms_slave_nfc_set_board_cb(toms_slave_nfc_board_cb_t cb);

/**
 * @brief NFC Target listener task (FreeRTOS). Never returns.
 *
 * Waits passively for Master NFC tap using TgInitAsTarget,
 * receives compact commands, expands via dictionary, and fires callback.
 */
void toms_slave_nfc_task(void *arg);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_SLAVE_NFC_SYNC_H */
