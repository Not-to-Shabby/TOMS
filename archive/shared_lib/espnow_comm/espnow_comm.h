/**
 * @file espnow_comm.h
 * @brief TOMS ESP-NOW communication layer.
 *
 * Manages ESP-NOW initialization, peer registration, encrypted
 * send/receive with application-level AES-256, and FreeRTOS event queuing.
 */

#ifndef TOMS_ESPNOW_COMM_H
#define TOMS_ESPNOW_COMM_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "protocol.h"

#ifdef __cplusplus
extern "C" {
#endif

/** ESP-NOW max payload (250 bytes) */
#define TOMS_ESPNOW_MAX_DATA   250

/** Default Wi-Fi channel for ESP-NOW */
#define TOMS_ESPNOW_CHANNEL    1

/** PMK for ESP-NOW native encryption (16 bytes) */
#define TOMS_ESPNOW_PMK_LEN   16

/**
 * @brief Received packet event (posted to the event queue).
 */
typedef struct {
    uint8_t        src_mac[6];  /**< Sender MAC address */
    toms_packet_t  packet;      /**< Deserialized + decrypted packet */
} toms_espnow_event_t;

/**
 * @brief Send result callback type.
 */
typedef void (*toms_espnow_send_cb_t)(const uint8_t *mac, bool success);

/**
 * @brief Initialize ESP-NOW communication.
 *
 * Initializes Wi-Fi in station mode (no AP connection), then sets up
 * ESP-NOW with the given PMK. Creates the internal receive queue.
 *
 * @param pmk   16-byte Primary Master Key (NULL for default).
 * @return ESP_OK on success.
 */
int toms_espnow_init(const uint8_t *pmk);

/**
 * @brief De-initialize ESP-NOW and free resources.
 */
void toms_espnow_deinit(void);

/**
 * @brief Register a peer device.
 *
 * @param mac       6-byte MAC address of the peer.
 * @param lmk       16-byte Local Master Key for native encryption (NULL to skip).
 * @param channel   Wi-Fi channel (0 = current).
 * @return true on success.
 */
bool toms_espnow_add_peer(const uint8_t *mac, const uint8_t *lmk, uint8_t channel);

/**
 * @brief Remove a peer device.
 */
bool toms_espnow_remove_peer(const uint8_t *mac);

/**
 * @brief Send a TOMS packet to a peer (encrypted with AES-256).
 *
 * The packet is serialized, encrypted, and transmitted via esp_now_send().
 *
 * @param dst_mac   Destination MAC (NULL for broadcast).
 * @param pkt       Packet to send.
 * @return true if send was queued successfully.
 */
bool toms_espnow_send(const uint8_t *dst_mac, const toms_packet_t *pkt);

/**
 * @brief Receive a packet from the internal queue (blocking).
 *
 * @param event     [out] Received event.
 * @param timeout_ms Wait timeout in milliseconds (portMAX_DELAY for infinite).
 * @return true if a packet was received within the timeout.
 */
bool toms_espnow_receive(toms_espnow_event_t *event, uint32_t timeout_ms);

/**
 * @brief Register an optional send-complete callback.
 */
void toms_espnow_set_send_cb(toms_espnow_send_cb_t cb);

/**
 * @brief Get this device's MAC address.
 */
void toms_espnow_get_mac(uint8_t *mac_out);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_ESPNOW_COMM_H */
