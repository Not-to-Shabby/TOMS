/**
 * @file app_main.c
 * @brief TOMS Master — Main entry point and FreeRTOS task orchestrator.
 *
 * Initializes all subsystems (NVS, SPIFFS, Wi-Fi/ESP-NOW, USB CDC, NFC,
 * battery monitoring) and launches dedicated FreeRTOS tasks on the
 * ESP32-S3's dual cores.
 *
 * Task assignment:
 *   Core 0: USB CDC (phone I/O), NFC sync (PN532 P2P), storage flush
 *   Core 1: ESP-NOW (radio, latency-sensitive)
 */

#include <string.h>
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_mac.h"
#include "esp_timer.h"
#include "driver/gpio.h"

/* TOMS modules */
#include "storage.h"
#include "crypto.h"
#include "espnow_comm.h"
#include "usb_cdc.h"
#include "nfc_sync.h"
#include "power.h"
#include "protocol.h"
#include "fare_dict.h"

static const char *TAG = "toms_master";

/* ── Configuration ────────────────────────────────────────────────────── */

/* Slave MAC — updated when a Slave authenticates via UART UID handshake */
static uint8_t s_slave_mac[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

/* ESP-NOW LMK for native encryption (16 bytes) */
static const uint8_t s_espnow_lmk[16] = {
    0x54, 0x4F, 0x4D, 0x53, 0x5F, 0x4C, 0x4D, 0x4B,
    0x5F, 0x53, 0x4C, 0x41, 0x56, 0x45, 0x30, 0x31
};

/* Sequence counters */
static uint8_t s_espnow_seq = 0;

/* Default vehicle ID — updated via USB from phone */
static uint8_t s_vehicle_id[16] = "VEH001";

/* Active fare/route config (updated from phone via USB sync) */
static uint16_t s_fare_centavos  = 1300;  /* Default: P13.00 */
static uint8_t  s_route_id       = 1;
static uint32_t s_max_capacity   = 20;

/* Fare dictionary (shared with Slaves, synced via ESP-NOW CONFIG_SYNC) */
static toms_fare_dict_t s_fare_dict;

/* ── Status LED Configuration ─────────────────────────────────────────── */
#define TOMS_LED_PIN          8   /* GPIO pin for onboard/external status LED */
#define TOMS_LED_ACTIVE_LEVEL 0   /* 0 = Active Low (standard for ESP32-S3 SuperMini blue LED), 1 = Active High */
#define TOMS_LED_BLINK_MS     200 /* Duration of LED blink in milliseconds */

static esp_timer_handle_t s_led_timer = NULL;

static void led_timer_callback(void *arg)
{
    gpio_set_level(TOMS_LED_PIN, !TOMS_LED_ACTIVE_LEVEL);
}

static void toms_led_init(void)
{
    ESP_LOGI(TAG, "Initializing status LED on GPIO%d...", TOMS_LED_PIN);
    gpio_reset_pin(TOMS_LED_PIN);
    gpio_set_direction(TOMS_LED_PIN, GPIO_MODE_OUTPUT);
    gpio_set_level(TOMS_LED_PIN, !TOMS_LED_ACTIVE_LEVEL); /* LED OFF by default */

    esp_timer_create_args_t timer_args = {
        .callback = &led_timer_callback,
        .name = "led_timer"
    };
    esp_timer_create(&timer_args, &s_led_timer);
}

static void toms_led_blink(void)
{
    if (s_led_timer) {
        esp_timer_stop(s_led_timer);
        gpio_set_level(TOMS_LED_PIN, TOMS_LED_ACTIVE_LEVEL); /* Turn LED ON */
        esp_timer_start_once(s_led_timer, TOMS_LED_BLINK_MS * 1000ULL);
    }
}

/* ── Forward Declarations ─────────────────────────────────────────────── */

static void on_usb_message(const char *data, size_t len);
static void on_nfc_tap(const uint8_t *slave_uid, uint8_t uid_len);
static void on_nfc_connect(bool connected);
static void espnow_handler_task(void *arg);
static void storage_flush_task(void *arg);
static void forward_passenger_to_phone(const toms_passenger_event_t *event);
static void dispatch_board_command_espnow(const uint8_t *slave_mac);
static void dispatch_alarm_command_espnow(const uint8_t *slave_mac,
                                          uint8_t alarm_type, uint8_t minutes_left);

/* ── USB Message Handler ──────────────────────────────────────────────── */

/**
 * Handle JSON-line messages from the phone.
 *
 * Expected format:
 *   {"cmd":"handshake","version":"1.0"}
 *   {"cmd":"sync_fare_table","data":{...}}
 *   {"cmd":"get_status"}
 *   {"cmd":"set_slave_mac","mac":"AA:BB:CC:DD:EE:FF"}
 */
static void on_usb_message(const char *data, size_t len)
{
    ESP_LOGI(TAG, "USB RX: %.*s", (int)len, data);

    /* Simple command parsing (production should use cJSON) */
    if (strstr(data, "\"handshake\"")) {
        /* Respond with device info */
        uint8_t mac[6];
        toms_espnow_get_mac(mac);

        char resp[256];
        snprintf(resp, sizeof(resp),
                 "{\"evt\":\"ack\",\"device\":\"toms_master\","
                 "\"mac\":\"%02X:%02X:%02X:%02X:%02X:%02X\","
                 "\"battery_pct\":%d,"
                 "\"pending_logs\":%d}",
                 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5],
                 toms_power_get_battery_pct(),
                 toms_storage_get_pending_count());
        toms_usb_cdc_send(resp);

    } else if (strstr(data, "\"get_status\"")) {
        uint32_t batt_mv = toms_power_get_battery_mv();
        uint8_t  batt_pct = toms_power_get_battery_pct();
        size_t total = 0, used = 0;
        toms_storage_get_stats(&total, &used);

        char loaded_wp[512] = {0};
        toms_config_get_str("waypoints", loaded_wp, sizeof(loaded_wp));
        if (loaded_wp[0] != '[') {
            strcpy(loaded_wp, "[]");
        }

        char resp[1024];
        snprintf(resp, sizeof(resp),
                 "{\"evt\":\"status\",\"battery_mv\":%lu,\"battery_pct\":%d,"
                 "\"storage_total\":%u,\"storage_used\":%u,"
                 "\"pending_logs\":%d,"
                 "\"nfc_state\":%d,\"max_capacity\":%lu,\"waypoints\":%s}",
                 (unsigned long)batt_mv, batt_pct,
                 (unsigned)total, (unsigned)used,
                 toms_storage_get_pending_count(),
                 (int)toms_nfc_sync_get_state(),
                 (unsigned long)s_max_capacity,
                 loaded_wp);
        toms_usb_cdc_send(resp);

    } else if (strstr(data, "\"send_alarm\"")) {
        /* Parse: {"cmd":"send_alarm","uid":"AABBCCDDEEFF","alarm_type":0,"minutes":2} */
        uint8_t target_mac[6] = {0};
        int alarm_type = 0;
        int minutes_left = 0;

        /* Simple hex UID parse (12 chars = 6 bytes) */
        const char *uid_start = strstr(data, "\"uid\":\"");
        if (uid_start) {
            uid_start += 8; /* skip "uid":" */
            for (int i = 0; i < 6; i++) {
                char byte_str[3] = {uid_start[i*2], uid_start[i*2+1], '\0'};
                target_mac[i] = (uint8_t)strtol(byte_str, NULL, 16);
            }
        }

        const char *atype = strstr(data, "\"alarm_type\":");
        if (atype) alarm_type = atoi(atype + 13);

        const char *mins = strstr(data, "\"minutes\":");
        if (mins) minutes_left = atoi(mins + 10);

        if (uid_start) {
            toms_espnow_add_peer(target_mac, NULL, TOMS_ESPNOW_CHANNEL);
            dispatch_alarm_command_espnow(target_mac, (uint8_t)alarm_type, (uint8_t)minutes_left);
            ESP_LOGI(TAG, "Alarm dispatched to " MACSTR " type=%d min=%d",
                     MAC2STR(target_mac), alarm_type, minutes_left);
            toms_usb_cdc_send("{\"evt\":\"ack\",\"cmd\":\"send_alarm\"}");
        } else {
            toms_usb_cdc_send("{\"evt\":\"error\",\"msg\":\"send_alarm: missing uid\"}");
        }

    } else if (strstr(data, "\"sync_fare_table\"") || strstr(data, "\"sync_config\"")) {
        /* Parse: {"cmd":"sync_fare_table","base_fare":1300,"per_km":200,"stop_count":5,
         *          "entries":[{"fare_id":1,"fare_centavos":1300},...], "max_capacity":20, "waypoints":["..."]} */
        const char *bf = strstr(data, "\"base_fare\":");
        const char *pk = strstr(data, "\"per_km\":");
        uint16_t base_fare  = bf ? (uint16_t)atoi(bf + 12) : s_fare_centavos;
        uint16_t per_km     = pk ? (uint16_t)atoi(pk + 9)  : 200;

        s_fare_centavos = base_fare;

        const char *cap = strstr(data, "\"max_capacity\":");
        if (cap) {
            s_max_capacity = (uint32_t)atoi(cap + 15);
            toms_config_set_u32("max_capacity", s_max_capacity);
            ESP_LOGI(TAG, "Saved max_capacity to NVS: %lu", (unsigned long)s_max_capacity);
        }

        const char *wp_start = strstr(data, "\"waypoints\":");
        if (wp_start) {
            const char *open_bracket = strchr(wp_start, '[');
            if (open_bracket) {
                const char *close_bracket = strchr(open_bracket, ']');
                if (close_bracket) {
                    size_t wp_len = close_bracket - open_bracket + 1;
                    if (wp_len < 500) {
                        char wp_buf[512] = {0};
                        strncpy(wp_buf, open_bracket, wp_len);
                        toms_config_set_str("waypoints", wp_buf);
                        ESP_LOGI(TAG, "Saved waypoints to NVS: %s", wp_buf);
                    }
                }
            }
        }

        /* Build a fare dict with a single default entry (extend for full stop matrix) */
        toms_fare_dict_t new_dict = {0};
        new_dict.count = 1;
        new_dict.entries[0].fare_id        = 1;
        new_dict.entries[0].fare_centavos  = base_fare;
        memcpy(&s_fare_dict, &new_dict, sizeof(toms_fare_dict_t));
        toms_fare_dict_save(&s_fare_dict);

        /* Broadcast FARE_TABLE_UPDATE to all known slaves via ESP-NOW */
        toms_config_payload_t cfg = {0};
        memcpy(cfg.vehicle_id, s_vehicle_id, 16);
        cfg.route_id          = s_route_id;
        cfg.base_fare_centavos = base_fare;
        cfg.per_km_centavos    = per_km;
        cfg.epoch_time         = (uint32_t)(esp_timer_get_time() / 1000000ULL);

        toms_packet_t pkt;
        toms_packet_build(&pkt, TOMS_MSG_FARE_TABLE_UPDATE, s_espnow_seq++,
                          (const uint8_t *)&cfg, sizeof(cfg));
        toms_espnow_send(s_slave_mac, &pkt);

        ESP_LOGI(TAG, "Fare table synced: base=%d per_km=%d, broadcast to slaves",
                 base_fare, per_km);
        toms_usb_cdc_send("{\"evt\":\"ack\",\"cmd\":\"sync_config\"}");

    } else {
        ESP_LOGW(TAG, "Unknown USB command");
        toms_usb_cdc_send("{\"evt\":\"error\",\"msg\":\"unknown command\"}");
    }
}

/* ── NFC Handlers ─────────────────────────────────────────────────────── */

/**
 * Called immediately when a Slave is detected via NFC tap (Phase 1: optimistic).
 * The slave_uid is the eFuse MAC extracted from the Slave's NFCID3.
 * The BOARD_CMD is sent over NFC by nfc_sync.c automatically.
 * PASSENGER_BOARD confirmation arrives later via ESP-NOW (Phase 2).
 */
static void on_nfc_tap(const uint8_t *slave_uid, uint8_t uid_len)
{
    ESP_LOGI(TAG, "NFC tap: Slave UID=%02X:%02X:%02X:%02X:%02X:%02X",
             slave_uid[0], slave_uid[1], slave_uid[2],
             slave_uid[3], slave_uid[4], slave_uid[5]);

    /* Update known Slave MAC for ESP-NOW confirmation path */
    memcpy(s_slave_mac, slave_uid, 6);
    toms_espnow_add_peer(s_slave_mac, NULL, TOMS_ESPNOW_CHANNEL);

    /* Phase 1: Optimistic count — notify phone immediately */
    char msg[192];
    snprintf(msg, sizeof(msg),
             "{\"evt\":\"nfc_tap\","
             "\"uid\":\"%02X%02X%02X%02X%02X%02X\","
             "\"status\":\"pending\"}",
             slave_uid[0], slave_uid[1], slave_uid[2],
             slave_uid[3], slave_uid[4], slave_uid[5]);
    toms_usb_cdc_send(msg);
}

static void on_nfc_connect(bool connected)
{
    ESP_LOGI(TAG, "NFC: %s", connected ? "CONNECTED" : "DISCONNECTED");
    char msg[64];
    snprintf(msg, sizeof(msg), "{\"evt\":\"nfc\",\"state\":\"%s\"}",
             connected ? "connected" : "disconnected");
    toms_usb_cdc_send(msg);
}

/* ── ESP-NOW Handler Task ─────────────────────────────────────────────── */

static void espnow_handler_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "ESP-NOW handler task started on core %d", xPortGetCoreID());

    toms_espnow_event_t event;

    while (1) {
        if (toms_espnow_receive(&event, 1000)) {
            toms_led_blink();
            ESP_LOGI(TAG, "ESP-NOW RX from " MACSTR ": type=0x%02X",
                     MAC2STR(event.src_mac), event.packet.msg_type);

            /* Register the sender as a peer dynamically so we can send ACKs/commands back. 
             * Passing NULL for LMK disables native CCMP encryption for this unicast link,
             * which is necessary since the Slave hasn't explicitly registered our MAC yet.
             * (Payload is still AES-256 encrypted by toms_crypto_encrypt). */
            toms_espnow_add_peer(event.src_mac, NULL, TOMS_ESPNOW_CHANNEL);

            switch (event.packet.msg_type) {
            case TOMS_MSG_PASSENGER_BOARD: {
                if (event.packet.length >= sizeof(toms_passenger_event_t)) {
                    const toms_passenger_event_t *pe =
                        (const toms_passenger_event_t *)event.packet.payload;

                    /* Log to SPIFFS */
                    toms_storage_log_event(pe);

                    /* Forward to phone */
                    forward_passenger_to_phone(pe);

                    /* Send ACK to slave */
                    toms_packet_t ack;
                    toms_packet_build(&ack, TOMS_MSG_ACK_MASTER, s_espnow_seq++, NULL, 0);
                    toms_espnow_send(event.src_mac, &ack);
                }
                break;
            }

            case TOMS_MSG_BUTTON_PRESS:
                ESP_LOGI(TAG, "Button press from slave " MACSTR, MAC2STR(event.src_mac));

                /* Notify phone */
                {
                    char msg[128];
                    snprintf(msg, sizeof(msg),
                             "{\"evt\":\"button_press\",\"source\":\"slave\","
                             "\"mac\":\"%02X:%02X:%02X:%02X:%02X:%02X\"}",
                             event.src_mac[0], event.src_mac[1], event.src_mac[2],
                             event.src_mac[3], event.src_mac[4], event.src_mac[5]);
                    toms_usb_cdc_send(msg);
                }

                /* ACK the button press */
                {
                    toms_packet_t ack;
                    toms_packet_build(&ack, TOMS_MSG_ACK_MASTER, s_espnow_seq++, NULL, 0);
                    toms_espnow_send(event.src_mac, &ack);
                }

                /* Dispatch BOARD_COMMAND back to the Slave via ESP-NOW */
                dispatch_board_command_espnow(event.src_mac);
                break;

            case TOMS_MSG_HEARTBEAT:
                ESP_LOGD(TAG, "Heartbeat from slave");
                /* If the heartbeat carries the battery telemetry payload, relay it */
                if (event.packet.length >= (uint8_t)sizeof(toms_heartbeat_payload_t)) {
                    const toms_heartbeat_payload_t *hb =
                        (const toms_heartbeat_payload_t *)event.packet.payload;
                    char msg[128];
                    snprintf(msg, sizeof(msg),
                             "{\"evt\":\"slave_battery\","
                             "\"uid\":\"%02X%02X%02X%02X%02X%02X\","
                             "\"battery_mv\":%u,"
                             "\"battery_pct\":%u}",
                             hb->uid[0], hb->uid[1], hb->uid[2],
                             hb->uid[3], hb->uid[4], hb->uid[5],
                             (unsigned)hb->battery_mv,
                             (unsigned)hb->battery_pct);
                    toms_usb_cdc_send(msg);
                }
                break;

            default:
                ESP_LOGD(TAG, "Unhandled ESP-NOW type: 0x%02X", event.packet.msg_type);
                break;
            }
        }
    }
}

/* ── ESP-NOW Board Command Dispatch ──────────────────────────────────── */

/**
 * Send a BOARD_COMMAND via ESP-NOW to a Slave that pressed its button.
 * The target_uid in the payload is extracted from the Slave's button-press
 * packet (which includes the first 6 bytes of the UID).
 */
static void dispatch_board_command_espnow(const uint8_t *slave_mac)
{
    toms_board_command_t cmd = {0};

    /* Use the Slave's MAC as the target_uid (first 6 bytes) */
    memcpy(cmd.target_uid, slave_mac, 6);
    cmd.timestamp     = (uint32_t)(esp_timer_get_time() / 1000000ULL);
    cmd.fare_centavos = s_fare_centavos;
    cmd.seat_number   = 0;   /* Unknown seat for button-press flow */
    cmd.route_id      = s_route_id;
    memcpy(cmd.vehicle_id, s_vehicle_id, 16);

    toms_packet_t pkt;
    toms_packet_build(&pkt, TOMS_MSG_BOARD_COMMAND, s_espnow_seq++,
                      (const uint8_t *)&cmd, sizeof(cmd));

    if (toms_espnow_send(slave_mac, &pkt)) {
        ESP_LOGI(TAG, "BOARD_COMMAND sent via ESP-NOW to " MACSTR,
                 MAC2STR(slave_mac));
    } else {
        ESP_LOGE(TAG, "Failed to send BOARD_COMMAND via ESP-NOW");
    }
}

/* ── ESP-NOW Alarm Command Dispatch ──────────────────────────────────── */

/**
 * Send a TOMS_MSG_ALARM_CMD via ESP-NOW to alert a specific Slave passenger
 * that they are approaching their destination and need to pay.
 *
 * @param slave_mac   6-byte MAC of the target slave.
 * @param alarm_type  0 = approaching (show minutes), 1 = final stop.
 * @param minutes_left Estimated minutes remaining (0 for final stop).
 */
static void dispatch_alarm_command_espnow(
        const uint8_t *slave_mac, uint8_t alarm_type, uint8_t minutes_left)
{
    toms_alarm_cmd_t alarm = {0};

    /* Use the Slave's MAC as the target_uid (first 6 bytes, zero-padded) */
    memcpy(alarm.target_uid, slave_mac, 6);
    alarm.alarm_type   = alarm_type;
    alarm.minutes_left = minutes_left;

    toms_packet_t pkt;
    toms_packet_build(&pkt, TOMS_MSG_ALARM_CMD, s_espnow_seq++,
                      (const uint8_t *)&alarm, sizeof(alarm));

    if (toms_espnow_send(slave_mac, &pkt)) {
        ESP_LOGI(TAG, "ALARM_CMD sent to " MACSTR " (type=%d, min=%d)",
                 MAC2STR(slave_mac), alarm_type, minutes_left);
    } else {
        ESP_LOGE(TAG, "Failed to send ALARM_CMD via ESP-NOW");
    }
}

/* ── Storage Flush Task ───────────────────────────────────────────────── */

static void storage_flush_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "Storage flush task started");

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(60000)); /* Every 60 seconds */
        toms_storage_cleanup();

        /* Log stats */
        size_t total = 0, used = 0;
        toms_storage_get_stats(&total, &used);
        ESP_LOGI(TAG, "Storage: %u/%u bytes (%d pending logs)",
                 (unsigned)used, (unsigned)total,
                 toms_storage_get_pending_count());
    }
}

/* ── Utility ──────────────────────────────────────────────────────────── */

static void forward_passenger_to_phone(const toms_passenger_event_t *event)
{
    if (!toms_usb_cdc_is_connected()) {
        ESP_LOGD(TAG, "USB not connected, skipping phone forward");
        return;
    }

    char json[384];
    snprintf(json, sizeof(json),
             "{\"evt\":\"passenger\","
             "\"timestamp\":%lu,"
             "\"boarding_type\":%d,"
             "\"fare_centavos\":%d,"
             "\"seat\":%d,"
             "\"route\":%d,"
             "\"passenger_id\":\"%02X%02X%02X%02X%02X%02X%02X%02X"
             "%02X%02X%02X%02X%02X%02X%02X%02X\"}",
             (unsigned long)event->timestamp,
             event->boarding_type,
             event->fare_centavos,
             event->seat_number,
             event->route_id,
             event->passenger_id[0],  event->passenger_id[1],
             event->passenger_id[2],  event->passenger_id[3],
             event->passenger_id[4],  event->passenger_id[5],
             event->passenger_id[6],  event->passenger_id[7],
             event->passenger_id[8],  event->passenger_id[9],
             event->passenger_id[10], event->passenger_id[11],
             event->passenger_id[12], event->passenger_id[13],
             event->passenger_id[14], event->passenger_id[15]);

    toms_usb_cdc_send(json);
}

/* ── Main Entry Point ─────────────────────────────────────────────────── */

void app_main(void)
{
    ESP_LOGI(TAG, "=== TOMS Master Firmware ===");
    ESP_LOGI(TAG, "Firmware version: 0.1.0");
    ESP_LOGI(TAG, "Free heap: %lu bytes", (unsigned long)esp_get_free_heap_size());

    /* Initialize Status LED */
    toms_led_init();

    /* ── Step 1: Storage (NVS + SPIFFS) ─────────────────────────────── */
    ESP_LOGI(TAG, "[1/7] Initializing storage...");
    int err = toms_storage_init();
    if (err != 0) {
        ESP_LOGE(TAG, "Storage init failed (%d) — halting", err);
        return;
    }

    /* ── Step 2: Crypto (AES-256 key from NVS) ──────────────────────── */
    ESP_LOGI(TAG, "[2/7] Initializing crypto...");
    err = toms_crypto_init();
    if (err != 0) {
        ESP_LOGE(TAG, "Crypto init failed (%d) — halting", err);
        return;
    }

    /* ── Step 3: ESP-NOW ────────────────────────────────────────────── */
    ESP_LOGI(TAG, "[3/7] Initializing ESP-NOW...");
    err = toms_espnow_init(NULL);  /* Use default PMK */
    if (err != 0) {
        ESP_LOGE(TAG, "ESP-NOW init failed (%d) — halting", err);
        return;
    }

    /* Add slave peer (broadcast by default until configured) */
    toms_espnow_add_peer(s_slave_mac, s_espnow_lmk, TOMS_ESPNOW_CHANNEL);

    /* ── Step 4: USB CDC ────────────────────────────────────────────── */
    ESP_LOGI(TAG, "[4/7] Initializing USB CDC...");
    err = toms_usb_cdc_init();
    if (err != 0) {
        ESP_LOGE(TAG, "USB CDC init failed (%d) — continuing without USB", err);
    }
    toms_usb_cdc_set_recv_cb(on_usb_message);

    /* ── Step 5: NFC Sync (PN532 Initiator) ──────────────────────────── */
    ESP_LOGI(TAG, "[5/8] Initializing NFC sync...");
    err = toms_nfc_sync_init();
    if (err != 0) {
        ESP_LOGE(TAG, "NFC sync init failed (%d) — continuing", err);
    }
    toms_nfc_sync_set_tap_cb(on_nfc_tap);
    toms_nfc_sync_set_connect_cb(on_nfc_connect);

    /* ── Step 6: Fare Dictionary ───────────────────────────────────── */
    ESP_LOGI(TAG, "[6/8] Loading fare dictionary...");
    toms_fare_dict_load(&s_fare_dict);
    ESP_LOGI(TAG, "Fare dictionary: %d entries loaded", s_fare_dict.count);

    if (toms_config_get_u32("max_capacity", &s_max_capacity)) {
        ESP_LOGI(TAG, "NVS: Loaded max_capacity = %ld", (long)s_max_capacity);
    } else {
        ESP_LOGI(TAG, "NVS: No max_capacity stored, using default 20");
    }

    char loaded_wp[512] = {0};
    if (toms_config_get_str("waypoints", loaded_wp, sizeof(loaded_wp))) {
        ESP_LOGI(TAG, "NVS: Loaded waypoints = %s", loaded_wp);
    } else {
        ESP_LOGI(TAG, "NVS: No waypoints stored");
    }

    /* Set active fare for NFC tap */
    toms_nfc_sync_set_fare(1, 0);  /* Default: fare_id=1, seat=0 */

    /* ── Step 7: Power monitoring ───────────────────────────────────── */
    ESP_LOGI(TAG, "[7/8] Initializing power monitoring...");
    toms_power_init();
    ESP_LOGI(TAG, "Battery: %lu mV (%d%%)",
             (unsigned long)toms_power_get_battery_mv(),
             toms_power_get_battery_pct());

    /* ── Step 8: Launch FreeRTOS tasks ──────────────────────────────── */
    ESP_LOGI(TAG, "[8/8] Launching tasks...");

    /* USB CDC task — Core 0, priority 5 */
    xTaskCreatePinnedToCore(toms_usb_cdc_task, "usb_cdc",
                            4096, NULL, 5, NULL, 0);

    /* ESP-NOW handler — Core 1, priority 6 (latency-sensitive) */
    xTaskCreatePinnedToCore(espnow_handler_task, "espnow_handler",
                            4096, NULL, 6, NULL, 1);

    /* NFC sync (Initiator) — Core 0, priority 4 */
    xTaskCreatePinnedToCore(toms_nfc_sync_task, "nfc_sync",
                            4096, NULL, 4, NULL, 0);

    /* Storage flush — Core 0, priority 3 */
    xTaskCreatePinnedToCore(storage_flush_task, "storage_flush",
                            2048, NULL, 3, NULL, 0);

    ESP_LOGI(TAG, "=== TOMS Master ready ===");
    ESP_LOGI(TAG, "Free heap after init: %lu bytes",
             (unsigned long)esp_get_free_heap_size());
}
