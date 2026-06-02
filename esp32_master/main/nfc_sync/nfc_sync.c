/**
 * @file nfc_sync.c
 * @brief TOMS Master — NFC-DEP Initiator synchronization implementation.
 *
 * State machine:
 *   IDLE → poll InJumpForDEP (424 kbps, 250ms timeout)
 *   CONNECTED → extract NFCID3 (Slave UID), fire tap_cb, send NFC_BOARD_CMD
 *   DISPATCHING → read NFC_ACK from Slave
 *   COOLDOWN → 1.5s anti-double-tap delay
 *   ERROR → reconfigure PN532, back to IDLE
 */

#include "nfc_sync.h"
#include "pn532.h"
#include "protocol.h"

#include <string.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "toms_nfc_sync";

/* ── Internal State ───────────────────────────────────────────────────── */

#if TOMS_USE_NFC
static pn532_handle_t       s_pn532;
static toms_nfc_state_t     s_state         = NFC_STATE_IDLE;
static toms_nfc_tap_cb_t    s_tap_cb        = NULL;
static toms_nfc_connect_cb_t s_connect_cb   = NULL;
static bool                 s_initialized   = false;

/* Active fare config to send on next tap */
static uint8_t  s_active_fare_id    = 1;    /* Default: first entry */
static uint8_t  s_active_seat       = 0;

/* Last tapped Slave UID for cooldown dedup */
static uint8_t  s_last_uid[6]       = {0};

/* ── Public API ───────────────────────────────────────────────────────── */

int toms_nfc_sync_init(void)
{
    if (s_initialized) return 0;

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
    ESP_LOGI(TAG, "NFC sync initialized (Initiator mode)");
    return 0;
}

toms_nfc_state_t toms_nfc_sync_get_state(void) { return s_state; }
void toms_nfc_sync_set_tap_cb(toms_nfc_tap_cb_t cb) { s_tap_cb = cb; }
void toms_nfc_sync_set_connect_cb(toms_nfc_connect_cb_t cb) { s_connect_cb = cb; }

void toms_nfc_sync_set_fare(uint8_t fare_id, uint8_t seat)
{
    s_active_fare_id = fare_id;
    s_active_seat    = seat;
}

/* ── State Machine Task ───────────────────────────────────────────────── */

void toms_nfc_sync_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "NFC sync task started (Initiator, 424 kbps)");

    uint32_t consecutive_errors = 0;
    uint32_t last_log_time = 0;

    while (1) {
        /* If PN532 is not initialized, periodically retry to avoid tight-loop failures */
        if (!s_initialized) {
            uint32_t now = (uint32_t)(esp_timer_get_time() / 1000000ULL);
            if (now - last_log_time >= 10) {
                ESP_LOGW(TAG, "PN532 not initialized. Retrying connection...");
                last_log_time = now;
            }
            if (toms_nfc_sync_init() == 0) {
                consecutive_errors = 0;
            } else {
                vTaskDelay(pdMS_TO_TICKS(5000));
                continue;
            }
        }

        switch (s_state) {

        /* ── IDLE: poll for NFC-DEP targets ─────────────────────────── */
        case NFC_STATE_IDLE: {
            uint8_t tg = 0;
            uint8_t tg_nfcid3[10] = {0};

            esp_err_t err = pn532_in_jump_for_dep(
                &s_pn532, PN532_DEP_BAUD_424, &tg, tg_nfcid3,
                TOMS_NFC_POLL_TIMEOUT_MS);

            if (err == ESP_OK) {
                consecutive_errors = 0;
                /* DEP link established — NFCID3 first 6 bytes = Slave eFuse MAC */
                ESP_LOGI(TAG, "NFC-DEP link: target=%d, UID=%02X:%02X:%02X:%02X:%02X:%02X",
                         tg, tg_nfcid3[0], tg_nfcid3[1], tg_nfcid3[2],
                         tg_nfcid3[3], tg_nfcid3[4], tg_nfcid3[5]);

                /* Check for double-tap of same device */
                if (memcmp(s_last_uid, tg_nfcid3, 6) == 0) {
                    ESP_LOGW(TAG, "Double-tap detected — ignoring");
                    pn532_in_release(&s_pn532, 0);
                    vTaskDelay(pdMS_TO_TICKS(200));
                    break;
                }

                memcpy(s_last_uid, tg_nfcid3, 6);
                s_state = NFC_STATE_CONNECTED;

                /* Phase 1: Optimistic count — notify immediately */
                if (s_tap_cb) s_tap_cb(tg_nfcid3, 6);
                if (s_connect_cb) s_connect_cb(true);
            } else if (err == ESP_ERR_TIMEOUT) {
                consecutive_errors = 0;
                /* Timeout is normal — no target in range */
            } else {
                consecutive_errors++;
                uint32_t now = (uint32_t)(esp_timer_get_time() / 1000000ULL);
                if (now - last_log_time >= 10) {
                    ESP_LOGE(TAG, "PN532 polling failed: %s (errors=%lu)", esp_err_to_name(err), (unsigned long)consecutive_errors);
                    last_log_time = now;
                }

                /* If too many consecutive errors, trigger full driver reload */
                if (consecutive_errors >= 5) {
                    ESP_LOGW(TAG, "Too many consecutive failures. De-initializing driver.");
                    s_initialized = false;
                }

                /* Delay to prevent CPU spinning when I2C write/read fails immediately */
                vTaskDelay(pdMS_TO_TICKS(1000));
            }
            break;
        }

        /* ── CONNECTED: send compact NFC_BOARD_CMD ──────────────────── */
        case NFC_STATE_CONNECTED: {
            toms_nfc_board_cmd_t cmd = {
                .fare_id     = s_active_fare_id,
                .seat_number = s_active_seat,
                .timestamp   = (uint32_t)(esp_timer_get_time() / 1000000ULL),
            };

            /* Prefix with message type byte for Slave to identify */
            uint8_t send_buf[1 + sizeof(cmd)];
            send_buf[0] = TOMS_MSG_NFC_BOARD_CMD;
            memcpy(&send_buf[1], &cmd, sizeof(cmd));

            uint8_t resp[8];
            uint8_t resp_len = sizeof(resp);

            esp_err_t err = pn532_in_data_exchange(
                &s_pn532, send_buf, sizeof(send_buf),
                resp, &resp_len, 500);

            if (err == ESP_OK && resp_len >= 2 &&
                resp[0] == TOMS_MSG_NFC_ACK && resp[1] == TOMS_NFC_ACK_OK) {
                ESP_LOGI(TAG, "NFC boarding command accepted by Slave");
                s_state = NFC_STATE_COOLDOWN;
            } else {
                ESP_LOGW(TAG, "NFC data exchange failed or NACK (err=%s, resp_len=%d)",
                         esp_err_to_name(err), resp_len);
                s_state = NFC_STATE_ERROR;
            }

            /* Release target */
            pn532_in_release(&s_pn532, 0);
            if (s_connect_cb) s_connect_cb(false);
            break;
        }

        /* ── COOLDOWN: prevent re-trigger ───────────────────────────── */
        case NFC_STATE_COOLDOWN:
            vTaskDelay(pdMS_TO_TICKS(TOMS_NFC_COOLDOWN_MS));
            memset(s_last_uid, 0, sizeof(s_last_uid));
            s_state = NFC_STATE_IDLE;
            break;

        /* ── ERROR: reset and retry ─────────────────────────────────── */
        case NFC_STATE_ERROR:
            ESP_LOGW(TAG, "NFC error — reconfiguring PN532");
            pn532_in_release(&s_pn532, 0);
            pn532_sam_configuration(&s_pn532);
            vTaskDelay(pdMS_TO_TICKS(500));
            s_state = NFC_STATE_IDLE;
            break;

        default:
            s_state = NFC_STATE_IDLE;
            break;
        }
    }
}
#else
/* ── Mock Implementations ──────────────────────────────────────────────── */
int toms_nfc_sync_init(void)
{
    ESP_LOGI(TAG, "NFC sync disabled by compile-time macro");
    return 0;
}

toms_nfc_state_t toms_nfc_sync_get_state(void) { return NFC_STATE_IDLE; }
void toms_nfc_sync_set_tap_cb(toms_nfc_tap_cb_t cb) { (void)cb; }
void toms_nfc_sync_set_connect_cb(toms_nfc_connect_cb_t cb) { (void)cb; }
void toms_nfc_sync_set_fare(uint8_t fare_id, uint8_t seat) { (void)fare_id; (void)seat; }

void toms_nfc_sync_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "NFC sync task suspended (NFC disabled)");
    while (1) {
        vTaskDelay(portMAX_DELAY);
    }
}
#endif
