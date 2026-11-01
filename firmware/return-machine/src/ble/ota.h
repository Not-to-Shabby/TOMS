/* Bluetooth firmware update (OTA_PLAN.md phase 4).
 *
 * Wire format and session rules are in lib/toms_core/include/toms/ota.h (host tested). This
 * module does the parts that need the board: queue the chunks off the Bluetooth RX thread,
 * erase and write the update slot, hash what was stored, ask MCUboot for a trial swap and
 * reboot. A phone talks to it through ota_control (0x0C) and ota_data (0x0D) in svc.c. */
#ifndef TOMS_BLE_OTA_H
#define TOMS_BLE_OTA_H

#include <stddef.h>
#include <stdint.h>

struct bt_conn;

/* Start the worker thread and read the running image's version. Call once, before advertising. */
void rm_ota_init(void);

/* Called on the Bluetooth RX thread. Return 0, or a negative errno that svc.c turns into an
 * ATT error. They only parse and queue; flash work happens on the worker thread. */
int rm_ota_control_write(struct bt_conn *conn, const uint8_t *data, uint16_t len);
int rm_ota_data_write(struct bt_conn *conn, const uint8_t *data, uint16_t len);

/* Implemented in svc.c: notify one value on ota_control. */
int rm_ble_notify_ota(const uint8_t *data, size_t len);

#endif /* TOMS_BLE_OTA_H */
