/* BLE peripheral service (firmware/PROTOCOL.md).
 *
 * M0 skeleton: advertising, connection tracking and all eight
 * characteristics exist; write handlers log and notify is available to the
 * app. Signatures, paid-check flow and NVS-backed buffering land with the
 * spike / M2 work. */
#ifndef TOMS_BLE_SVC_H
#define TOMS_BLE_SVC_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

int rm_ble_start(void);

bool rm_ble_connected(void);

/* Notify one encoded card_read frame (payload only, signature applied here
 * once key provisioning exists). Returns 0, -ENOTCONN when no central is
 * subscribed, or the bt_gatt_notify error. */
int rm_ble_notify_card_read(const uint8_t *data, size_t len);

#endif /* TOMS_BLE_SVC_H */
