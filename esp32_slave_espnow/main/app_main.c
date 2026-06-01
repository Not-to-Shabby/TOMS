/**
 * @file app_main.c
 * @brief TOMS Slave — Passenger interface entry point.
 *
 * Phase 2.1: Master-driven passive architecture with dual-trigger support.
 *
 * Boarding can be triggered two ways:
 *   1. Master-driven: ESP-NOW BOARD_COMMAND or UART BOARD_COMMAND (primary)
 *   2. Manual: Physical button press → notifies Master, waits for BOARD_COMMAND
 *
 * Anti-fraud: All BOARD_COMMAND packets are validated against this Slave's
 * unique hardware UID (eFuse MAC). Mismatched UIDs are silently dropped.
 *
 * Lifecycle:
 *   Wake → Init → Read UID → Show Welcome
 *     → Wait for Master Command / Button
 *     → Validate UID → Show Fare → Show QR → Idle → Sleep
 */

#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_mac.h"
#include "esp_random.h"
#include "nvs_flash.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"

/* TOMS modules */
#include "display.h"
#include "qr_gen.h"
#include "button.h"
#include "ui.h"
#include "sleep.h"
#include "crypto.h"
#include "espnow_comm.h"
#include "protocol.h"
#include "nfc_sync.h"
#include "debug_serial.h"
#include "lvgl.h"

static const char *TAG = "toms_slave";

/* ── Configuration ────────────────────────────────────────────────────── */

/* Master MAC (updated via CONFIG_SYNC) */
static uint8_t s_master_mac[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

/* ESP-NOW LMK — must match Master */
static const uint8_t s_espnow_lmk[16] = {
    0x54, 0x4F, 0x4D, 0x53, 0x5F, 0x4C, 0x4D, 0x4B,
    0x5F, 0x53, 0x4C, 0x41, 0x56, 0x45, 0x30, 0x31
};

/* This Slave's unique hardware UID (eFuse MAC, 16 bytes, zero-padded) */
static uint8_t s_slave_uid[16];

/* Sequence counter */
static uint8_t s_seq = 0;

/* ── Shared State (written by tasks, read by main logic) ──────────────── */

static volatile bool s_button_pressed    = false;  /* Manual button trigger */
static volatile bool s_board_cmd_pending = false;  /* Master boarding command ready */
static toms_board_command_t s_pending_cmd;          /* Latest board command payload */
static volatile bool s_debug_active      = false;  /* Debug mode active */
static bool          s_debug_qr_shown    = false;
static toms_debug_receipt_t s_debug_receipt;
static volatile bool s_release_requested = false;
static volatile toms_ui_screen_t s_held_screen_before_progress = TOMS_UI_WELCOME;

/* Issue 5 fixed: use FreeRTOS EventGroup for cross-core ACK signal (atomic on ESP32-S3) */
static EventGroupHandle_t s_evt_group;
#define EVT_ACK_RECEIVED  BIT0

/* Issue 1 fixed: non-blocking button-wait state — avoids blocking main_logic_task */
static bool        s_waiting_for_board   = false;
static TickType_t  s_button_wait_start   = 0;
#define BUTTON_BOARD_WAIT_TICKS  pdMS_TO_TICKS(5000)
#define BUTTON_ERROR_SHOW_TICKS  pdMS_TO_TICKS(2000)

/* ── Forward Declarations ─────────────────────────────────────────────── */

static void restore_passenger_screen(void);

static void on_button_event(toms_button_event_t event);
static void on_nfc_board_command(const toms_board_command_t *cmd);
static void espnow_handler_task(void *arg);
static void main_logic_task(void *arg);
static void lvgl_timer_task(void *arg);
static void execute_boarding(const toms_board_command_t *cmd);
static void send_heartbeat(void);
static void build_debug_receipt(toms_debug_receipt_t *out);
static void build_debug_qr(const toms_debug_receipt_t *receipt, char *out, size_t out_size);

/* ── UID Utilities ────────────────────────────────────────────────────── */

static void uid_init(void)
{
    memset(s_slave_uid, 0, sizeof(s_slave_uid));
    esp_read_mac(s_slave_uid, ESP_MAC_WIFI_STA);
    ESP_LOGI(TAG, "Slave UID: %02X:%02X:%02X:%02X:%02X:%02X",
             s_slave_uid[0], s_slave_uid[1], s_slave_uid[2],
             s_slave_uid[3], s_slave_uid[4], s_slave_uid[5]);
}

static bool uid_matches(const uint8_t *target_uid)
{
    /* Issue 4 fixed: compare only the 6-byte eFuse MAC portion.
     * The master always fills only 6 bytes; bytes 6–15 stay zero.
     * Comparing 16 bytes worked by accident but breaks once RFID
     * card UIDs (full 16 bytes) are introduced. */
    return (memcmp(target_uid, s_slave_uid, 6) == 0);
}

/* ── Debug Receipt Helpers ───────────────────────────────────────────── */

static void build_debug_receipt(toms_debug_receipt_t *out)
{
    if (!out) return;

    memset(out, 0, sizeof(*out));
    out->fare_centavos = 1200;

    snprintf(out->title, sizeof(out->title), "Mitsco");
    snprintf(out->datetime, sizeof(out->datetime), "05/04/2026 11:47 AM");
    snprintf(out->route, sizeof(out->route), "City Proper-Country Hills");
    snprintf(out->terminal, sizeof(out->terminal), "DPWH - TAMBO TERMINAL");
    snprintf(out->discount, sizeof(out->discount),
             "DISCOUNT: 1 x %u.%02u = P %u.%02u",
             (unsigned int)(out->fare_centavos / 100),
             (unsigned int)(out->fare_centavos % 100),
             (unsigned int)(out->fare_centavos / 100),
             (unsigned int)(out->fare_centavos % 100));

    out->ticket_no = (uint32_t)(esp_random() % 100000000U);
    snprintf(out->payment_mode, sizeof(out->payment_mode), "Cash");
    snprintf(out->bus_id, sizeof(out->bus_id), "CDB%04u",
             (unsigned int)(esp_random() % 10000U));
    snprintf(out->etim_no, sizeof(out->etim_no), "MITSCO%02u",
             (unsigned int)(esp_random() % 100U));
    out->waybill_no = (uint32_t)(esp_random() % 100000U);
    snprintf(out->duty_no, sizeof(out->duty_no), "NA");
    snprintf(out->driver_id, sizeof(out->driver_id), "DCDR1001");
}

static void build_debug_qr(const toms_debug_receipt_t *receipt, char *out, size_t out_size)
{
    if (!receipt || !out || out_size == 0) return;

    snprintf(out, out_size,
             "TOMS|DEBUG|%s|P%u.%02u|T%08u|%s|%s",
             receipt->datetime,
             (unsigned int)(receipt->fare_centavos / 100),
             (unsigned int)(receipt->fare_centavos % 100),
             (unsigned int)receipt->ticket_no,
             receipt->bus_id,
             receipt->etim_no);
}

/* ── Button Handler ───────────────────────────────────────────────────── */

static void restore_passenger_screen(void)
{
    if (s_held_screen_before_progress == TOMS_UI_FARE) {
        char route_str[16];
        snprintf(route_str, sizeof(route_str), "Route %d", s_pending_cmd.route_id);
        toms_ui_show_fare(route_str, s_pending_cmd.fare_centavos, s_pending_cmd.seat_number);
    } else if (s_held_screen_before_progress == TOMS_UI_QR) {
        char qr_buf[160];
        char uid_hex[13];
        snprintf(uid_hex, sizeof(uid_hex), "%02X%02X%02X%02X%02X%02X",
                 s_slave_uid[0], s_slave_uid[1], s_slave_uid[2],
                 s_slave_uid[3], s_slave_uid[4], s_slave_uid[5]);
        if (toms_qr_build_receipt(
                (const char *)s_pending_cmd.vehicle_id,
                s_pending_cmd.timestamp,
                s_pending_cmd.fare_centavos,
                uid_hex,
                qr_buf, sizeof(qr_buf))) {
            toms_ui_show_qr(qr_buf);
        }
    }
}

static void on_button_event(toms_button_event_t event)
{
    toms_ui_screen_t curr = toms_ui_get_current();

    if (event == TOMS_BTN_LONG_PRESS) {
        debug_serial_send_button(event);
        if (curr == TOMS_UI_WELCOME) {
            ESP_LOGI(TAG, "Debug long press detected -> show receipt");
            s_debug_active = true;
            s_debug_qr_shown = false;
            build_debug_receipt(&s_debug_receipt);
            toms_ui_show_debug_receipt(&s_debug_receipt);
        }
        return;
    }

    if (event == TOMS_BTN_HOLD_1S) {
        debug_serial_send_button(event);
        if (curr == TOMS_UI_FARE || curr == TOMS_UI_QR) {
            s_held_screen_before_progress = curr;
            toms_ui_show_error("Release in 2s...");
            toms_sleep_reset_idle();
        }
        return;
    }

    if (event == TOMS_BTN_HOLD_2S) {
        debug_serial_send_button(event);
        if (s_held_screen_before_progress == TOMS_UI_FARE || s_held_screen_before_progress == TOMS_UI_QR) {
            toms_ui_show_error("Release in 1s...");
            toms_sleep_reset_idle();
        }
        return;
    }

    if (event == TOMS_BTN_HOLD_3S) {
        debug_serial_send_button(event);
        if (s_held_screen_before_progress == TOMS_UI_FARE || s_held_screen_before_progress == TOMS_UI_QR) {
            ESP_LOGI(TAG, "Hold 3s reached -> request release");
            s_release_requested = true;
            toms_sleep_reset_idle();
        }
        return;
    }

    if (event == TOMS_BTN_RELEASE) {
        if (s_held_screen_before_progress == TOMS_UI_FARE || s_held_screen_before_progress == TOMS_UI_QR) {
            if (!s_release_requested) {
                ESP_LOGI(TAG, "Button released early -> restore screen");
                restore_passenger_screen();
            }
            s_held_screen_before_progress = TOMS_UI_WELCOME;
        }
        return;
    }

    if (event == TOMS_BTN_PRESS && s_debug_active) {
        if (curr == TOMS_UI_DEBUG_RECEIPT) {
            ESP_LOGI(TAG, "Debug mode: receipt -> QR");
            char qr_buf[200];
            build_debug_qr(&s_debug_receipt, qr_buf, sizeof(qr_buf));
            toms_ui_show_qr(qr_buf);
            s_debug_qr_shown = true;
        } else if (curr == TOMS_UI_QR) {
            ESP_LOGI(TAG, "Debug mode: exit -> welcome");
            s_debug_active = false;
            s_debug_qr_shown = false;
            toms_ui_show_welcome();
        }
        return;
    }

    if (event == TOMS_BTN_PRESS) {
        debug_serial_send_button(event);
        ESP_LOGI(TAG, "=> Physical Master/Dock connection detected! (GPIO 4 LOW)");
        ESP_LOGI(TAG, "Button event: PRESS (flagging s_button_pressed)");
        s_button_pressed = true;
        toms_sleep_reset_idle();

        /* Notify Master of button press so it can trigger a boarding command */
        toms_packet_t pkt;
        toms_packet_build(&pkt, TOMS_MSG_BUTTON_PRESS, s_seq++,
                          s_slave_uid, 6);  /* Include UID so master knows who pressed */
        toms_espnow_send(s_master_mac, &pkt);
        ESP_LOGI(TAG, "Button press notified to master");
    }
}

/* ── NFC Board Command Callback ─────────────────────────────────────── */

static void on_nfc_board_command(const toms_board_command_t *cmd)
{
    ESP_LOGI(TAG, "=> Master NFC tap session — boarding command received!");

    /* Copy and set flag — expanded from dictionary by nfc_sync.c */
    memcpy(&s_pending_cmd, cmd, sizeof(toms_board_command_t));
    s_board_cmd_pending = true;
    toms_sleep_reset_idle();
}

/* ── ESP-NOW Handler Task ─────────────────────────────────────────────── */

static void espnow_handler_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "ESP-NOW handler task started");

    toms_espnow_event_t event;

    while (1) {
        if (!toms_espnow_receive(&event, 500)) continue;

        ESP_LOGI(TAG, "ESP-NOW RX: type=0x%02X from " MACSTR,
                 event.packet.msg_type, MAC2STR(event.src_mac));
        toms_sleep_reset_idle();

        switch (event.packet.msg_type) {

        case TOMS_MSG_BOARD_COMMAND:
            if (event.packet.length >= sizeof(toms_board_command_t)) {
                const toms_board_command_t *cmd =
                    (const toms_board_command_t *)event.packet.payload;

                /* Anti-fraud UID check */
                if (uid_matches(cmd->target_uid)) {
                    ESP_LOGI(TAG, "ESP-NOW board command validated — fare=%d",
                             cmd->fare_centavos);
                    debug_serial_send_espnow_rx(event.packet.msg_type,
                                                cmd->fare_centavos,
                                                cmd->seat_number);
                    memcpy(&s_pending_cmd, cmd, sizeof(toms_board_command_t));
                    s_board_cmd_pending = true;

                    /* Save master MAC */
                    memcpy(s_master_mac, event.src_mac, 6);

                    /* ACK */
                    toms_packet_t ack;
                    toms_packet_build(&ack, TOMS_MSG_ACK_SLAVE, s_seq++, NULL, 0);
                    toms_espnow_send(event.src_mac, &ack);
                } else {
                    ESP_LOGW(TAG, "Board command UID mismatch — dropped");
                }
            }
            break;

        case TOMS_MSG_CONFIG_SYNC:
            if (event.packet.length >= sizeof(toms_config_payload_t)) {
                const toms_config_payload_t *cfg =
                    (const toms_config_payload_t *)event.packet.payload;
                ESP_LOGI(TAG, "Config sync: route=%d, base_fare=%d",
                         cfg->route_id, cfg->base_fare_centavos);
                memcpy(s_master_mac, event.src_mac, 6);

                toms_packet_t ack;
                toms_packet_build(&ack, TOMS_MSG_ACK_SLAVE, s_seq++, NULL, 0);
                toms_espnow_send(event.src_mac, &ack);
            }
            break;

        case TOMS_MSG_FARE_TABLE_UPDATE:
            ESP_LOGI(TAG, "Fare table update received");
            /* TODO: persist to NVS */
            {
                toms_packet_t ack;
                toms_packet_build(&ack, TOMS_MSG_ACK_SLAVE, s_seq++, NULL, 0);
                toms_espnow_send(event.src_mac, &ack);
            }
            break;

        case TOMS_MSG_ALARM_CMD:
            if (event.packet.length >= sizeof(toms_alarm_cmd_t)) {
                const toms_alarm_cmd_t *alarm =
                    (const toms_alarm_cmd_t *)event.packet.payload;

                /* Anti-fraud UID check — same pattern as BOARD_COMMAND */
                if (uid_matches(alarm->target_uid)) {
                    ESP_LOGI(TAG, "Alarm command validated: type=%d, minutes=%d",
                             alarm->alarm_type, alarm->minutes_left);

                    /* Show alarm screen on the slave display */
                    toms_ui_show_alarm(alarm->alarm_type, alarm->minutes_left);

                    /* Keep slave awake while alarm is active */
                    toms_sleep_reset_idle();

                    /* ACK back to master */
                    toms_packet_t ack;
                    toms_packet_build(&ack, TOMS_MSG_ACK_SLAVE, s_seq++, NULL, 0);
                    toms_espnow_send(event.src_mac, &ack);
                } else {
                    ESP_LOGW(TAG, "Alarm command UID mismatch — dropped");
                }
            }
            break;

        case TOMS_MSG_HEARTBEAT:
            ESP_LOGD(TAG, "Heartbeat from master");
            break;

        case TOMS_MSG_ACK_MASTER:
            ESP_LOGI(TAG, "ACK received from master");
            debug_serial_send_espnow_rx(event.packet.msg_type, 0, 0);
            /* Issue 5 fixed: set EventGroup bit (atomic cross-core signal) */
            xEventGroupSetBits(s_evt_group, EVT_ACK_RECEIVED);
            break;

        default:
            ESP_LOGD(TAG, "Unhandled msg: 0x%02X", event.packet.msg_type);
            break;
        }
    }
}

/* ── Boarding Execution ───────────────────────────────────────────────── */

/**
 * Executes the full boarding display sequence using data from the Master's
 * BOARD_COMMAND. This is the only path that generates the QR receipt.
 */
static void execute_boarding(const toms_board_command_t *cmd)
{
    ESP_LOGI(TAG, "Executing boarding — fare=%d centavos, seat=%d",
             cmd->fare_centavos, cmd->seat_number);

    toms_ui_show_processing();
    vTaskDelay(pdMS_TO_TICKS(500));

    /* Show fare screen */
    char route_str[16];
    snprintf(route_str, sizeof(route_str), "Route %d", cmd->route_id);
    toms_ui_show_fare(route_str, cmd->fare_centavos, cmd->seat_number);
    vTaskDelay(pdMS_TO_TICKS(3000));

    /* Build QR receipt string: TOMS|VEH_ID|TIMESTAMP|FARE|UID */
    char qr_buf[160];
    char uid_hex[13];
    snprintf(uid_hex, sizeof(uid_hex), "%02X%02X%02X%02X%02X%02X",
             s_slave_uid[0], s_slave_uid[1], s_slave_uid[2],
             s_slave_uid[3], s_slave_uid[4], s_slave_uid[5]);

    if (toms_qr_build_receipt(
            (const char *)cmd->vehicle_id,
            cmd->timestamp,
            cmd->fare_centavos,
            uid_hex,
            qr_buf, sizeof(qr_buf))) {
        toms_ui_show_qr(qr_buf);
        vTaskDelay(pdMS_TO_TICKS(8000));
    }

    /* Notify Master with the completed passenger event */
    toms_passenger_event_t pe = {0};
    pe.timestamp     = cmd->timestamp;
    /* Issue 2 fixed: use the boarding_type set by master (BUTTON, CARD, QR)
     * instead of hardcoding TOMS_BOARD_CARD for all Demo Mode boardings. */
    pe.boarding_type = cmd->boarding_type;
    pe.fare_centavos = cmd->fare_centavos;
    pe.seat_number   = cmd->seat_number;
    pe.route_id      = cmd->route_id;
    memcpy(pe.passenger_id, s_slave_uid, 6);  /* UID as passenger identity */

    toms_packet_t pkt;
    toms_packet_build(&pkt, TOMS_MSG_PASSENGER_BOARD, s_seq++,
                      (const uint8_t *)&pe, sizeof(pe));
    toms_espnow_send(s_master_mac, &pkt);
    debug_serial_send_espnow_tx(TOMS_MSG_PASSENGER_BOARD, s_seq - 1);

    toms_ui_show_welcome();
    debug_serial_send_state("welcome", 0, 0, s_master_mac, s_seq);
}

/* ── Battery ADC Helpers ───────────────────────────────────────────────── */

/**
 * Read the LiPo battery voltage from ADC1 channel 0 (GPIO 1).
 *
 * Uses the ESP-IDF oneshot ADC driver to take a single reading.
 * Calibration via the curve-fitting scheme (eFuse Vref) is attempted;
 * falls back to a linear approximation (raw × 3300 / 4095) if not available.
 *
 * @param[out] mv   Voltage in millivolts.
 * @param[out] pct  State-of-charge (0–100%), linear between 3200 mV and 4200 mV.
 */
static void battery_read(uint16_t *mv, uint8_t *pct)
{
    /* Configure oneshot ADC handle */
    adc_oneshot_unit_handle_t adc_handle;
    adc_oneshot_unit_init_cfg_t unit_cfg = {
        .unit_id  = ADC_UNIT_1,
        .ulp_mode = ADC_ULP_MODE_DISABLE,
    };
    if (adc_oneshot_new_unit(&unit_cfg, &adc_handle) != ESP_OK) {
        *mv  = 0;
        *pct = 0;
        return;
    }

    adc_oneshot_chan_cfg_t chan_cfg = {
        .atten    = ADC_ATTEN_DB_12,   /* Full 0–3.3 V range (ADC_ATTEN_11db alias) */
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    adc_oneshot_config_channel(adc_handle, ADC_CHANNEL_0, &chan_cfg);

    /* Try curve-fitting calibration (uses eFuse Vref burned at factory) */
    adc_cali_handle_t cali_handle = NULL;
    bool calibrated = false;

#if ADC_CALI_SCHEME_CURVE_FITTING_SUPPORTED
    adc_cali_curve_fitting_config_t cali_cfg = {
        .unit_id  = ADC_UNIT_1,
        .chan     = ADC_CHANNEL_0,
        .atten    = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    if (adc_cali_create_scheme_curve_fitting(&cali_cfg, &cali_handle) == ESP_OK) {
        calibrated = true;
    }
#endif

    /* Take a reading */
    int raw = 0;
    adc_oneshot_read(adc_handle, ADC_CHANNEL_0, &raw);

    int voltage_mv = 0;
    if (calibrated && cali_handle) {
        adc_cali_raw_to_voltage(cali_handle, raw, &voltage_mv);
#if ADC_CALI_SCHEME_CURVE_FITTING_SUPPORTED
        adc_cali_delete_scheme_curve_fitting(cali_handle);
#endif
    } else {
        /* Linear fallback: 12-bit ADC at 3.3 V reference */
        voltage_mv = (raw * 3300) / 4095;
    }
    adc_oneshot_del_unit(adc_handle);

    /* Map to state-of-charge: 3200 mV = 0%, 4200 mV = 100% */
    const int MV_MIN = 3200;
    const int MV_MAX = 4200;
    int pct_raw = ((voltage_mv - MV_MIN) * 100) / (MV_MAX - MV_MIN);
    if (pct_raw < 0)   pct_raw = 0;
    if (pct_raw > 100) pct_raw = 100;

    *mv  = (uint16_t)voltage_mv;
    *pct = (uint8_t)pct_raw;

    ESP_LOGD(TAG, "Battery: raw=%d mv=%d pct=%d", raw, voltage_mv, pct_raw);
}

/* ── Heartbeat ────────────────────────────────────────────────────────── */

static void send_heartbeat(void)
{
    uint16_t batt_mv  = 0;
    uint8_t  batt_pct = 0;
    battery_read(&batt_mv, &batt_pct);

    toms_heartbeat_payload_t hb = {0};
    memcpy(hb.uid, s_slave_uid, 6);
    hb.battery_mv  = batt_mv;
    hb.battery_pct = batt_pct;

    toms_packet_t pkt;
    toms_packet_build(&pkt, TOMS_MSG_HEARTBEAT, s_seq++,
                      (const uint8_t *)&hb, sizeof(hb));
    toms_espnow_send(s_master_mac, &pkt);
    ESP_LOGI(TAG, "Heartbeat sent — battery %d mV (%d%%)", batt_mv, batt_pct);
    debug_serial_send_batt(batt_mv, batt_pct);
}

/* ── LVGL Timer Task ─────────────────────────────────────────────────── */

static void lvgl_timer_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "LVGL timer task started");
    while (1) {
        toms_ui_process();
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

/* ── Main Logic Task ──────────────────────────────────────────────────── */

static void main_logic_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "Main logic task started");

    toms_ui_show_welcome();

    while (1) {
        /* Priority 1: Master board command (ESP-NOW or NFC) */
        if (s_board_cmd_pending) {
            ESP_LOGI(TAG, "Board command pending -> execute_boarding");
            s_board_cmd_pending  = false;
            s_button_pressed     = false;  /* Clear manual flag too */
            s_waiting_for_board  = false;  /* Cancel any pending button wait */
            execute_boarding(&s_pending_cmd);
        }

        /* Priority 1.5: Manual release request */
        if (s_release_requested) {
            ESP_LOGI(TAG, "Release flag seen -> sending RELEASE to master");
            s_release_requested = false;

            toms_packet_t pkt;
            toms_packet_build(&pkt, TOMS_MSG_RELEASE, s_seq++,
                              s_slave_uid, 6);

            /* Issue 5 fixed: clear EventGroup bit before sending so we don't
             * pick up a stale ACK from a previous operation. */
            xEventGroupClearBits(s_evt_group, EVT_ACK_RECEIVED);
            toms_espnow_send(s_master_mac, &pkt);
            debug_serial_send_espnow_tx(TOMS_MSG_RELEASE, s_seq - 1);

            toms_ui_show_processing();

            /* Block up to 3s for master ACK — EventGroup is atomic across cores */
            EventBits_t bits = xEventGroupWaitBits(
                    s_evt_group, EVT_ACK_RECEIVED,
                    pdTRUE,   /* clear on exit */
                    pdFALSE,  /* any bit */
                    pdMS_TO_TICKS(3000));

            if (bits & EVT_ACK_RECEIVED) {
                ESP_LOGI(TAG, "Release ACKed by master");
            } else {
                ESP_LOGW(TAG, "Release ACK timeout");
                debug_serial_send_timeout("release_ack");
            }
            toms_ui_show_welcome();
            debug_serial_send_state("welcome", 0, 0, s_master_mac, s_seq);
        }

        /* Priority 2: Button pressed — show processing, start non-blocking wait */
        if (s_button_pressed) {
            ESP_LOGI(TAG, "Manual button flag seen -> waiting for master board command");
            s_button_pressed    = false;
            s_waiting_for_board = true;
            s_button_wait_start = xTaskGetTickCount();
            toms_ui_show_processing();  /* "Waiting for master..." */
        }

        /* Priority 2.5: Issue 1 fixed — non-blocking board response timeout check.
         * Instead of spinning in a while-loop for 5s (which blocked s_release_requested
         * and other state), we check elapsed ticks on every 50ms main loop iteration. */
        if (s_waiting_for_board) {
            TickType_t elapsed = xTaskGetTickCount() - s_button_wait_start;
            if (elapsed > BUTTON_BOARD_WAIT_TICKS) {
                /* Timeout: master never sent a board command */
                s_waiting_for_board = false;
                ESP_LOGW(TAG, "Master timeout waiting for board command");
                debug_serial_send_timeout("board_wait");
                toms_ui_show_error("Master Timeout");
                /* Use a short non-blocking delay via vTaskDelay — only 2s not 5s */
                vTaskDelay(BUTTON_ERROR_SHOW_TICKS);
                toms_ui_show_welcome();
            }
            /* If s_board_cmd_pending arrives before timeout, Priority 1 above
             * will clear s_waiting_for_board and call execute_boarding. */
        }

        /* Deep sleep on idle timeout */
        if (toms_sleep_should_sleep()) {
            ESP_LOGI(TAG, "Idle timeout — entering deep sleep");
            toms_ui_show_sleep();
            vTaskDelay(pdMS_TO_TICKS(100));
            toms_sleep_enter();
        }

        vTaskDelay(pdMS_TO_TICKS(50));
    }
}

/* ── Main Entry Point ─────────────────────────────────────────────────── */

void app_main(void)
{
    ESP_LOGI(TAG, "=== TOMS Slave Firmware v0.2.0 ===");

    toms_wake_cause_t wake = toms_sleep_get_wake_cause();
    ESP_LOGI(TAG, "Wake cause: %d", (int)wake);

    /* ── Step 1: NVS ────────────────────────────────────────────────── */
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        nvs_flash_init();
    }

    /* ── Step 2: Slave UID (eFuse MAC) ──────────────────────────────── */
    uid_init();

    /* ── Step 2b: FreeRTOS synchronization primitives ───────────────── */
    s_evt_group = xEventGroupCreate();
    configASSERT(s_evt_group != NULL);

    /* ── Step 2c: Debug UART (GPIO17 TX → STM32 PA3 RX) ─────────────── */
    debug_serial_init();

    /* ── Step 3: Crypto ─────────────────────────────────────────────── */
    toms_crypto_init();

    /* ── Step 4: ESP-NOW ────────────────────────────────────────────── */
    toms_espnow_init(NULL);
    toms_espnow_add_peer(s_master_mac, s_espnow_lmk, TOMS_ESPNOW_CHANNEL);

    /* ── Step 5: NFC Sync (PN532 Target) ───────────────────────────── */
    err = toms_slave_nfc_init();
    if (err != 0) {
        ESP_LOGE(TAG, "NFC init failed (%d) — continuing without NFC sync", err);
    }
    toms_slave_nfc_set_board_cb(on_nfc_board_command);

    /* ── Step 6: Display ──────────────────────────────────────────── */
    toms_display_init();
    toms_ui_init();

    /* ── Step 7: Button ─────────────────────────────────────────────── */
    toms_button_init();
    toms_button_set_callback(on_button_event);

    /* ── Step 8: Sleep Manager ──────────────────────────────────────── */
    toms_sleep_init();

    /* ── Timer wake: send heartbeat with UID and go back to sleep ────── */
    if (wake == TOMS_WAKE_TIMER) {
        ESP_LOGI(TAG, "Timer wake — sending heartbeat");
        send_heartbeat();
        vTaskDelay(pdMS_TO_TICKS(200));
        toms_sleep_enter();
        /* Never returns */
    }

    /* ── Launch tasks ───────────────────────────────────────────────── */
    ESP_LOGI(TAG, "Launching tasks...");

    /* ESP-NOW handler — Core 1, latency-sensitive */
    xTaskCreatePinnedToCore(espnow_handler_task, "espnow_handler",
                            4096, NULL, 6, NULL, 1);

    /* NFC Target listener — Core 0 */
    xTaskCreatePinnedToCore(toms_slave_nfc_task, "nfc_sync",
                            4096, NULL, 5, NULL, 0);

    /* Button input — Core 0 */
    xTaskCreatePinnedToCore(toms_button_task, "button",
                            4096, NULL, 4, NULL, 0);

    /* LVGL timer — Core 0 */
    xTaskCreatePinnedToCore(lvgl_timer_task, "lvgl_timer",
                            8192, NULL, 7, NULL, 0);

    /* Main logic — Core 0 */
    xTaskCreatePinnedToCore(main_logic_task, "main_logic",
                            8192, NULL, 3, NULL, 0);

    ESP_LOGI(TAG, "=== TOMS Slave ready (wake: %d) ===", (int)wake);
}
