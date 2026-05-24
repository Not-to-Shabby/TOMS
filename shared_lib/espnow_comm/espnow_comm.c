/**
 * @file espnow_comm.c
 * @brief TOMS ESP-NOW communication implementation.
 */

#include "espnow_comm.h"
#include "crypto.h"
#include "protocol.h"

#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "esp_now.h"
#include "esp_mac.h"
#include "nvs_flash.h"

static const char *TAG = "toms_espnow";

/* ── Internal State ───────────────────────────────────────────────────── */

#define RECV_QUEUE_SIZE     16

static QueueHandle_t          s_recv_queue   = NULL;
static toms_espnow_send_cb_t  s_send_cb     = NULL;
static bool                   s_initialized = false;

/* ── Default PMK (override via toms_espnow_init) ──────────────────────── */

static const uint8_t s_default_pmk[TOMS_ESPNOW_PMK_LEN] = {
    0x54, 0x4F, 0x4D, 0x53, 0x5F, 0x50, 0x4D, 0x4B,  /* "TOMS_PMK" */
    0x5F, 0x44, 0x45, 0x46, 0x41, 0x55, 0x4C, 0x54   /* "_DEFAULT" */
};

/* ── ESP-NOW Callbacks ────────────────────────────────────────────────── */

static void on_send(const uint8_t *mac_addr, esp_now_send_status_t status)
{
    if (s_send_cb) {
        s_send_cb(mac_addr, status == ESP_NOW_SEND_SUCCESS);
    }
    ESP_LOGD(TAG, "Send to " MACSTR " %s",
             MAC2STR(mac_addr),
             status == ESP_NOW_SEND_SUCCESS ? "OK" : "FAIL");
}

static void on_recv(const esp_now_recv_info_t *info, const uint8_t *data, int data_len)
{
    if (!s_recv_queue || data_len <= 0) {
        return;
    }

    /* Decrypt (AES-256-CBC, IV-prefixed) */
    uint8_t decrypted[TOMS_MAX_PACKET_LEN];
    size_t  decrypted_len = 0;

    if (!toms_crypto_decrypt(data, (size_t)data_len, decrypted, &decrypted_len)) {
        ESP_LOGW(TAG, "Decrypt failed from " MACSTR, MAC2STR(info->src_addr));
        return;
    }

    /* Deserialize */
    toms_espnow_event_t event;
    memcpy(event.src_mac, info->src_addr, 6);

    if (!toms_packet_deserialize(decrypted, decrypted_len, &event.packet)) {
        ESP_LOGW(TAG, "Deserialize failed from " MACSTR, MAC2STR(info->src_addr));
        return;
    }

    /* Post to queue (non-blocking) */
    if (xQueueSend(s_recv_queue, &event, 0) != pdTRUE) {
        ESP_LOGW(TAG, "Receive queue full — dropping packet");
    }
}

/* ── Wi-Fi Init (minimal, for ESP-NOW) ────────────────────────────────── */

static esp_err_t wifi_init_minimal(void)
{
    esp_err_t err;

    err = esp_netif_init();
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        return err;
    }

    err = esp_event_loop_create_default();
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        return err;
    }

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    err = esp_wifi_init(&cfg);
    if (err != ESP_OK) return err;

    err = esp_wifi_set_storage(WIFI_STORAGE_RAM);
    if (err != ESP_OK) return err;

    err = esp_wifi_set_mode(WIFI_MODE_STA);
    if (err != ESP_OK) return err;

    err = esp_wifi_start();
    if (err != ESP_OK) return err;

    /* Set channel for ESP-NOW */
    err = esp_wifi_set_channel(TOMS_ESPNOW_CHANNEL, WIFI_SECOND_CHAN_NONE);
    return err;
}

/* ── Public API ───────────────────────────────────────────────────────── */

int toms_espnow_init(const uint8_t *pmk)
{
    if (s_initialized) {
        ESP_LOGW(TAG, "Already initialized");
        return ESP_OK;
    }

    /* Wi-Fi must be initialized for ESP-NOW */
    esp_err_t err = wifi_init_minimal();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Wi-Fi init failed: %s", esp_err_to_name(err));
        return (int)err;
    }

    /* Create receive queue */
    s_recv_queue = xQueueCreate(RECV_QUEUE_SIZE, sizeof(toms_espnow_event_t));
    if (!s_recv_queue) {
        ESP_LOGE(TAG, "Failed to create receive queue");
        return ESP_ERR_NO_MEM;
    }

    /* Initialize ESP-NOW */
    err = esp_now_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_now_init failed: %s", esp_err_to_name(err));
        return (int)err;
    }

    /* Set PMK */
    const uint8_t *key = pmk ? pmk : s_default_pmk;
    err = esp_now_set_pmk(key);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Set PMK failed: %s", esp_err_to_name(err));
        return (int)err;
    }

    /* Register callbacks */
    err = esp_now_register_send_cb((esp_now_send_cb_t)on_send);
    if (err != ESP_OK) return (int)err;

    err = esp_now_register_recv_cb(on_recv);
    if (err != ESP_OK) return (int)err;

    s_initialized = true;
    ESP_LOGI(TAG, "ESP-NOW initialized on channel %d", TOMS_ESPNOW_CHANNEL);
    return ESP_OK;
}

void toms_espnow_deinit(void)
{
    if (!s_initialized) return;

    esp_now_deinit();
    if (s_recv_queue) {
        vQueueDelete(s_recv_queue);
        s_recv_queue = NULL;
    }
    s_initialized = false;
    ESP_LOGI(TAG, "ESP-NOW deinitialized");
}

bool toms_espnow_add_peer(const uint8_t *mac, const uint8_t *lmk, uint8_t channel)
{
    if (!s_initialized || !mac) return false;

    esp_now_peer_info_t peer = {0};
    memcpy(peer.peer_addr, mac, 6);
    peer.channel = channel;
    peer.ifidx   = WIFI_IF_STA;

    if (lmk) {
        /* Check if MAC is broadcast (FF:FF:FF:FF:FF:FF) */
        bool is_broadcast = true;
        for (int i = 0; i < 6; i++) {
            if (mac[i] != 0xFF) {
                is_broadcast = false;
                break;
            }
        }

        if (is_broadcast) {
            ESP_LOGW(TAG, "Native encryption disabled for broadcast MAC");
            peer.encrypt = false;
        } else {
            peer.encrypt = true;
            memcpy(peer.lmk, lmk, 16);
        }
    }

    esp_err_t err = esp_now_add_peer(&peer);
    if (err == ESP_ERR_ESPNOW_EXIST) {
        ESP_LOGW(TAG, "Peer " MACSTR " already exists, updating", MAC2STR(mac));
        err = esp_now_mod_peer(&peer);
    }

    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Add peer failed: %s", esp_err_to_name(err));
        return false;
    }

    ESP_LOGI(TAG, "Peer added: " MACSTR " (encrypted=%d)", MAC2STR(mac), peer.encrypt);
    return true;
}

bool toms_espnow_remove_peer(const uint8_t *mac)
{
    if (!s_initialized || !mac) return false;
    return esp_now_del_peer(mac) == ESP_OK;
}

bool toms_espnow_send(const uint8_t *dst_mac, const toms_packet_t *pkt)
{
    if (!s_initialized || !pkt) return false;

    /* Serialize the packet */
    uint8_t raw[TOMS_MAX_PACKET_LEN];
    size_t  raw_len = 0;
    if (!toms_packet_serialize(pkt, raw, &raw_len)) {
        ESP_LOGE(TAG, "Serialize failed");
        return false;
    }

    /* Encrypt with AES-256 */
    uint8_t encrypted[TOMS_ESPNOW_MAX_DATA];
    size_t  enc_len = 0;
    if (!toms_crypto_encrypt(raw, raw_len, encrypted, &enc_len)) {
        ESP_LOGE(TAG, "Encrypt failed");
        return false;
    }

    if (enc_len > TOMS_ESPNOW_MAX_DATA) {
        ESP_LOGE(TAG, "Encrypted payload too large: %u > %d", (unsigned)enc_len, TOMS_ESPNOW_MAX_DATA);
        return false;
    }

    /* Send via ESP-NOW */
    esp_err_t err = esp_now_send(dst_mac, encrypted, (size_t)enc_len);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_now_send failed: %s", esp_err_to_name(err));
        return false;
    }

    const uint8_t bcast[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
    ESP_LOGD(TAG, "Sent %u bytes (encrypted) to " MACSTR,
             (unsigned)enc_len, MAC2STR(dst_mac ? dst_mac : bcast));
    return true;
}

bool toms_espnow_receive(toms_espnow_event_t *event, uint32_t timeout_ms)
{
    if (!s_recv_queue || !event) return false;

    TickType_t ticks = (timeout_ms == UINT32_MAX) ? portMAX_DELAY
                     : pdMS_TO_TICKS(timeout_ms);

    return xQueueReceive(s_recv_queue, event, ticks) == pdTRUE;
}

void toms_espnow_set_send_cb(toms_espnow_send_cb_t cb)
{
    s_send_cb = cb;
}

void toms_espnow_get_mac(uint8_t *mac_out)
{
    if (mac_out) {
        esp_read_mac(mac_out, ESP_MAC_WIFI_STA);
    }
}
