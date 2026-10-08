/**
 * @file pn532.h
 * @brief Low-level PN532 NFC controller driver over I2C.
 *
 * Shared by both TOMS Master (Initiator) and Slave (Target) devices.
 * Supports NFC-DEP (ISO 18092) peer-to-peer data exchange at 424 kbps.
 *
 * I2C Protocol:
 *   Address: 0x24 (7-bit)
 *   Frame:   [Preamble 0x00][Start 0x00 0xFF][LEN][LCS][TFI][Data...][DCS][Postamble 0x00]
 *   ACK:     [0x00 0x00 0xFF 0x00 0xFF 0x00]
 *
 * IRQ pin is used for zero-latency response detection (active LOW).
 */

#ifndef TOMS_PN532_H
#define TOMS_PN532_H

#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ── I2C Constants ────────────────────────────────────────────────────── */

#define PN532_I2C_ADDR              0x24    /**< 7-bit I2C address */
#define PN532_I2C_READY_BIT         0x01    /**< Ready status bit in I2C read */

/* ── Frame Constants ──────────────────────────────────────────────────── */

#define PN532_PREAMBLE              0x00
#define PN532_STARTCODE1            0x00
#define PN532_STARTCODE2            0xFF
#define PN532_HOSTTOPN532           0xD4    /**< TFI: host → PN532 */
#define PN532_PN532TOHOST           0xD5    /**< TFI: PN532 → host */
#define PN532_POSTAMBLE             0x00

/** Maximum data payload in a single PN532 frame (TFI + command + params) */
#define PN532_MAX_DATA_LEN          265

/** ACK frame bytes */
#define PN532_ACK_LEN               6

/* ── PN532 Command Codes ──────────────────────────────────────────────── */

#define PN532_CMD_GETFIRMWAREVERSION    0x02
#define PN532_CMD_SAMCONFIGURATION      0x14
#define PN532_CMD_RFCONFIGURATION       0x32
#define PN532_CMD_INJUMPFORDEP          0x56
#define PN532_CMD_INDATAEXCHANGE        0x40
#define PN532_CMD_INRELEASE             0x52
#define PN532_CMD_TGINITASTARGET        0x8C
#define PN532_CMD_TGGETDATA             0x86
#define PN532_CMD_TGSETDATA             0x8E

/* ── NFC-DEP Baud Rates ──────────────────────────────────────────────── */

#define PN532_DEP_BAUD_106          0x00
#define PN532_DEP_BAUD_212          0x01
#define PN532_DEP_BAUD_424          0x02

/* ── NFC-DEP Modes ────────────────────────────────────────────────────── */

#define PN532_DEP_PASSIVE           0x00
#define PN532_DEP_ACTIVE            0x01

/* ── TgInitAsTarget Mode Byte ─────────────────────────────────────────── */

#define PN532_TG_MODE_PICC_ONLY     0x00
#define PN532_TG_MODE_DEP_ONLY      0x02
#define PN532_TG_MODE_PICC_DEP      0x03

/* ── Configuration ────────────────────────────────────────────────────── */

typedef struct {
    int      sda_pin;           /**< I2C SDA GPIO */
    int      scl_pin;           /**< I2C SCL GPIO */
    int      irq_pin;           /**< IRQ GPIO (active LOW, -1 to disable) */
    int      i2c_port;          /**< I2C port number (I2C_NUM_0 or I2C_NUM_1) */
    uint32_t i2c_freq_hz;       /**< I2C clock frequency (100000 or 400000) */
} pn532_config_t;

typedef struct {
    pn532_config_t config;
    bool           initialized;
    uint8_t        _rx_buf[PN532_MAX_DATA_LEN + 16]; /**< Internal receive buffer */
} pn532_handle_t;

/* ── Initialization ───────────────────────────────────────────────────── */

/**
 * @brief Initialize the PN532 over I2C.
 *
 * Configures I2C master bus, sets up IRQ GPIO interrupt, verifies
 * PN532 firmware version, and configures SAM to normal mode.
 *
 * @param handle  Handle to initialize.
 * @param config  Pin and I2C configuration.
 * @return ESP_OK on success.
 */
esp_err_t pn532_init(pn532_handle_t *handle, const pn532_config_t *config);

/**
 * @brief Read PN532 firmware version.
 *
 * @param handle  Initialized handle.
 * @param ic      [out] IC type (0x07 for PN532).
 * @param ver     [out] Firmware version.
 * @param rev     [out] Firmware revision.
 * @return ESP_OK on success.
 */
esp_err_t pn532_get_firmware_version(pn532_handle_t *handle,
                                      uint8_t *ic, uint8_t *ver, uint8_t *rev);

/**
 * @brief Configure the Security Access Module (SAM).
 *
 * Sets SAM to normal mode (no virtual card, no wired card).
 *
 * @param handle  Initialized handle.
 * @return ESP_OK on success.
 */
esp_err_t pn532_sam_configuration(pn532_handle_t *handle);

/* ── IRQ / Ready ──────────────────────────────────────────────────────── */

/**
 * @brief Wait until PN532 signals data ready via IRQ or polling.
 *
 * Uses IRQ GPIO interrupt (falling edge) if configured, otherwise
 * polls I2C status byte.
 *
 * @param handle      Initialized handle.
 * @param timeout_ms  Maximum wait time in milliseconds.
 * @return true if PN532 is ready, false on timeout.
 */
bool pn532_wait_ready(pn532_handle_t *handle, uint32_t timeout_ms);

/* ── Initiator Commands (Master) ──────────────────────────────────────── */

/**
 * @brief Establish NFC-DEP link as Initiator.
 *
 * Sends InJumpForDEP at the specified baud rate. On success, the target
 * number and NFCID3 are available in the response.
 *
 * @param handle      Initialized handle.
 * @param baud        Baud rate: PN532_DEP_BAUD_106/212/424.
 * @param tg          [out] Target number (usually 1).
 * @param tg_nfcid3   [out] Target's NFCID3 (10 bytes). Can be NULL.
 * @param timeout_ms  Timeout for target detection.
 * @return ESP_OK on success, ESP_ERR_TIMEOUT if no target found.
 */
esp_err_t pn532_in_jump_for_dep(pn532_handle_t *handle, uint8_t baud,
                                 uint8_t *tg, uint8_t *tg_nfcid3,
                                 uint32_t timeout_ms);

/**
 * @brief Exchange data with target as Initiator.
 *
 * Sends data to the connected target and reads the response.
 *
 * @param handle      Initialized handle.
 * @param send        Data to send.
 * @param send_len    Length of data to send.
 * @param resp        [out] Response data buffer.
 * @param resp_len    [in/out] On input: buffer size. On output: bytes received.
 * @param timeout_ms  Timeout for response.
 * @return ESP_OK on success.
 */
esp_err_t pn532_in_data_exchange(pn532_handle_t *handle,
                                  const uint8_t *send, uint8_t send_len,
                                  uint8_t *resp, uint8_t *resp_len,
                                  uint32_t timeout_ms);

/**
 * @brief Release the current target.
 *
 * @param handle  Initialized handle.
 * @param tg      Target number to release (0 = all).
 * @return ESP_OK on success.
 */
esp_err_t pn532_in_release(pn532_handle_t *handle, uint8_t tg);

/* ── Target Commands (Slave) ──────────────────────────────────────────── */

/**
 * @brief Initialize as NFC-DEP Target and wait for Initiator.
 *
 * Blocks until an Initiator activates this target or timeout.
 * The NFCID3 is derived from the provided UID bytes.
 *
 * @param handle      Initialized handle.
 * @param nfcid3      10-byte NFCID3 to present to initiator (embed eFuse MAC here).
 * @param timeout_ms  Timeout waiting for initiator (0 = indefinite).
 * @return ESP_OK when activated, ESP_ERR_TIMEOUT on timeout.
 */
esp_err_t pn532_tg_init_as_target(pn532_handle_t *handle,
                                   const uint8_t *nfcid3,
                                   uint32_t timeout_ms);

/**
 * @brief Receive data from Initiator (Target mode).
 *
 * Blocks until data is available from the connected initiator.
 *
 * @param handle      Initialized handle.
 * @param data        [out] Received data buffer.
 * @param data_len    [in/out] On input: buffer size. On output: bytes received.
 * @param timeout_ms  Timeout.
 * @return ESP_OK on success.
 */
esp_err_t pn532_tg_get_data(pn532_handle_t *handle,
                             uint8_t *data, uint8_t *data_len,
                             uint32_t timeout_ms);

/**
 * @brief Send data to Initiator (Target mode).
 *
 * @param handle      Initialized handle.
 * @param data        Data to send.
 * @param data_len    Length of data.
 * @param timeout_ms  Timeout for ACK.
 * @return ESP_OK on success.
 */
esp_err_t pn532_tg_set_data(pn532_handle_t *handle,
                             const uint8_t *data, uint8_t data_len,
                             uint32_t timeout_ms);

/* ── Low-Level (internal, exposed for testing) ────────────────────────── */

/**
 * @brief Send a raw command frame to PN532.
 */
esp_err_t pn532_write_command(pn532_handle_t *handle,
                               const uint8_t *cmd, uint8_t cmd_len);

/**
 * @brief Read a raw response frame from PN532.
 *
 * @param handle      Handle.
 * @param buf         [out] Response data (after TFI + response code).
 * @param len         [in/out] Buffer size / bytes read.
 * @param timeout_ms  Timeout.
 * @return ESP_OK on success.
 */
esp_err_t pn532_read_response(pn532_handle_t *handle,
                               uint8_t *buf, uint8_t *len,
                               uint32_t timeout_ms);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_PN532_H */
