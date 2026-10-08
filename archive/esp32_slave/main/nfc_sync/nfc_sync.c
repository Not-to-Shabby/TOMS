/**
 * @file nfc_sync.c
 * @brief TOMS Slave — NFC-DEP Target synchronization implementation.
 *
 * The Slave PN532 waits in Target mode (TgInitAsTarget, DEP-only).
 * Its NFCID3 embeds the eFuse MAC so the Master can identify it
 * during link establishment without a separate UID handshake.
 *
 * On NFC tap:
 *   1. TgInitAsTarget blocks until Master activates (IRQ-driven)
 *   2. TgGetData receives NFC_BOARD_CMD (fare_id + seat + timestamp)
 *   3. Look up fare_id in local fare dictionary → expand to full command
 *   4. TgSetData sends NFC_ACK
 *   5. Fire board callback → triggers boarding display flow
 *   6. After boarding, PASSENGER_BOARD is sent via ESP-NOW (by app_main)
 */

#include "nfc_sync.h"
#include "pn532.h"
#include "protocol.h"
#include "fare_dict.h"

#include <string.h>
#include "esp_log.h"
#include "esp_mac.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "toms_slave_nfc";

/* ── Internal State ───────────────────────────────────────────────────── */

static pn532_handle_t              s_pn532;
static bool                        s_initialized = false;
static toms_slave_nfc_board_cb_t   s_board_cb    = NULL;

/* Slave UID (eFuse MAC, 16 bytes, zero-padded) */
static uint8_t s_uid[16];

/* NFCID3 presented to Master (10 bytes, first 6 = eFuse MAC) */
static uint8_t s_nfcid3[10];

/* Local fare dictionary */
static toms_fare_dict_t s_fare_dict;

/* ── UID Helper ───────────────────────────────────────────────────────── */

static void uid_load(void)
{
    memset(s_uid, 0, sizeof(s_uid));
    esp_read_mac(s_uid, ESP_MAC_WIFI_STA);

    /* Build NFCID3: first 6 bytes = MAC, rest zero-padded */
    memset(s_nfcid3, 0, sizeof(s_nfcid3));
    memcpy(s_nfcid3, s_uid, 6);

    ESP_LOGI(TAG, "Slave UID: %02X:%02X:%02X:%02X:%02X:%02X",
             s_uid[0], s_uid[1], s_uid[2],
             s_uid[3], s_uid[4], s_uid[5]);
}

/* ── Public API ───────────────────────────────────────────────────────── */

int toms_slave_nfc_init(void)
{
    if (s_initialized) return 0;

    uid_load();

    /* Load fare dictionary from NVS */
    toms_fare_dict_load(&s_fare_dict);

    pn532_config_t cfg = {
        .sda_pin     = TOMS_NFC_I2C_SDA,
        .scl_pin     = TOMS_NFC_I2C_SCL,
        .irq_pin     = TOMS_NFC_IRQ_PIN,
        .i2c_port    = TOMS_NFC_I2C_PORT,
        .i2c_freq_hz = TOMS_NFC_I2C_FREQ,
    };

    esp_err_t err = pn532_init(&s_pn532, &cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "PN532 init failed: %s", esp_err_to_name(err));
        return (int)err;
    }

    s_initialized = true;
    ESP_LOGI(TAG, "NFC sync initialized (Target mode, %d fare entries)",
             s_fare_dict.count);
    return 0;
}

void toms_slave_nfc_set_board_cb(toms_slave_nfc_board_cb_t cb)
{
    s_board_cb = cb;
}

/* ── NFC Target Listener Task ─────────────────────────────────────────── */

void toms_slave_nfc_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "NFC Target listener started");

    while (1) {
        if (!s_initialized) {
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }

        /* Step 1: Wait for Master to activate us (blocks until tap, IRQ-driven) */
        ESP_LOGD(TAG, "Waiting for NFC tap (TgInitAsTarget)...");
        esp_err_t err = pn532_tg_init_as_target(&s_pn532, s_nfcid3, 0);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "TgInitAsTarget failed: %s", esp_err_to_name(err));
            pn532_sam_configuration(&s_pn532);
            vTaskDelay(pdMS_TO_TICKS(200));
            continue;
        }

        ESP_LOGI(TAG, "NFC tap detected — activated by Master");

        /* Step 2: Receive NFC_BOARD_CMD from Master */
        uint8_t rx_buf[16];
        uint8_t rx_len = sizeof(rx_buf);

        err = pn532_tg_get_data(&s_pn532, rx_buf, &rx_len, 2000);
        if (err != ESP_OK || rx_len < 1) {
            ESP_LOGW(TAG, "TgGetData failed: %s (len=%d)", esp_err_to_name(err), rx_len);
            continue;
        }

        uint8_t msg_type = rx_buf[0];
        ESP_LOGI(TAG, "NFC RX: type=0x%02X, len=%d", msg_type, rx_len);

        if (msg_type != TOMS_MSG_NFC_BOARD_CMD ||
            rx_len < (1 + sizeof(toms_nfc_board_cmd_t))) {
            ESP_LOGW(TAG, "Unexpected NFC message type or short payload");

            /* Send NACK */
            uint8_t nack[] = { TOMS_MSG_NFC_ACK, TOMS_NFC_ACK_ERROR };
            pn532_tg_set_data(&s_pn532, nack, sizeof(nack), 500);
            continue;
        }

        /* Step 3: Parse compact command and look up fare dictionary */
        toms_nfc_board_cmd_t nfc_cmd;
        memcpy(&nfc_cmd, &rx_buf[1], sizeof(toms_nfc_board_cmd_t));

        const toms_fare_entry_t *entry = toms_fare_dict_lookup(&s_fare_dict, nfc_cmd.fare_id);
        if (!entry) {
            ESP_LOGW(TAG, "Unknown fare_id=%d — dictionary miss", nfc_cmd.fare_id);
            uint8_t nack[] = { TOMS_MSG_NFC_ACK, TOMS_NFC_ACK_DICT_MISS };
            pn532_tg_set_data(&s_pn532, nack, sizeof(nack), 500);
            continue;
        }

        ESP_LOGI(TAG, "Fare lookup: id=%d → %s, P%d.%02d, route=%d",
                 entry->fare_id, entry->route_name,
                 entry->fare_centavos / 100, entry->fare_centavos % 100,
                 entry->route_id);

        /* Step 4: Send ACK to Master */
        uint8_t ack[] = { TOMS_MSG_NFC_ACK, TOMS_NFC_ACK_OK };
        err = pn532_tg_set_data(&s_pn532, ack, sizeof(ack), 500);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "TgSetData ACK failed — Master may have disconnected");
            /* Still process the command — we already have the data */
        }

        /* Step 5: Expand compact command to full toms_board_command_t */
        toms_board_command_t full_cmd = {0};
        memcpy(full_cmd.target_uid, s_uid, 16);
        full_cmd.timestamp     = nfc_cmd.timestamp;
        full_cmd.fare_centavos = entry->fare_centavos;
        full_cmd.seat_number   = nfc_cmd.seat_number;
        full_cmd.route_id      = entry->route_id;
        memcpy(full_cmd.vehicle_id, entry->vehicle_id, 16);

        /* Step 6: Fire callback → triggers boarding display flow */
        if (s_board_cb) {
            ESP_LOGI(TAG, "=> NFC boarding command accepted (fare=%d, seat=%d)",
                     full_cmd.fare_centavos, full_cmd.seat_number);
            s_board_cb(&full_cmd);
        }

        /* Loop back — PN532 will re-enter target mode on next iteration */
    }
}
