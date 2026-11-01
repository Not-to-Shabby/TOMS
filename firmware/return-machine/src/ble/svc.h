/* BLE peripheral service: the Return Machine API a phone talks to
 * (firmware/PROTOCOL.md is the byte-level contract).
 *
 * The phone is the central. It writes state_update, paid_reply, time_sync,
 * config, ack and sim_card, and subscribes to card_read, paid_check,
 * heartbeat and rm_ack. Signatures and flash buffering land with the spike /
 * M2 work: until then values carry no HMAC trailer. */
#ifndef TOMS_BLE_SVC_H
#define TOMS_BLE_SVC_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <toms/codec.h>

struct rm_ble_status {
	char name[24];
	bool advertising;
	bool connected;
	bool card_notify;  /* phone subscribed to card_read */
	bool check_notify; /* phone subscribed to paid_check */
	bool hb_notify;    /* phone subscribed to heartbeat */
	bool ack_notify;   /* phone subscribed to rm_ack */
	bool time_synced;
	uint16_t mtu;
	uint32_t rx_writes;
	uint32_t rx_rejected;
	uint8_t last_write_char; /* suffix of the last characteristic written, 0 = none */
	uint32_t state_version;
	uint16_t state_count;
	uint32_t state_acked; /* last table version the phone acked */
	uint32_t read_acked;  /* highest read_seq the phone acked */
	uint32_t tx_card;
	uint32_t tx_heartbeat;
	uint32_t paid_sent;
	uint32_t paid_replied;
	uint32_t paid_timeouts;
};

/* Called on the Bluetooth RX thread when the phone writes sim_card (dev mode
 * only). Do not block in it: queue the UID for another thread. */
typedef void (*rm_ble_sim_card_cb_t)(const uint8_t *uid, uint8_t uid_len);

int rm_ble_start(void);

bool rm_ble_connected(void);

void rm_ble_get_status(struct rm_ble_status *out);

void rm_ble_set_sim_card_cb(rm_ble_sim_card_cb_t cb);

/* Cached status for a UID from the phone's state_update writes:
 * TOMS_STATUS_* or TOMS_STATUS_UNKNOWN. */
uint8_t rm_ble_state_lookup(const uint8_t *uid, uint8_t uid_len);

/* Settings the phone wrote with the config characteristic (defaults until
 * then). Not persisted yet. */
void rm_ble_get_config(struct toms_config_msg *out);

/* UTC seconds from the phone's time_sync, 0 until the first sync. */
uint32_t rm_ble_epoch(void);

/* Notify one encoded value. Each returns 0, -ENOTCONN when no phone is
 * subscribed to that characteristic, -EMSGSIZE when the value does not fit
 * the negotiated MTU, or the bt_gatt_notify error. */
int rm_ble_notify_card_read(const uint8_t *data, size_t len);
int rm_ble_notify_heartbeat(const uint8_t *data, size_t len);

/* Ask the phone whether a card is paid: notifies paid_check, then blocks up
 * to timeout_ms for the matching paid_reply write. Must not be called from
 * the Bluetooth RX thread. Returns 0 with the TOMS_VERDICT_* in *verdict,
 * -ENOTCONN when the phone is not subscribed to paid_check, -ETIMEDOUT when
 * no reply arrived in time, or the notify error. */
int rm_ble_paid_check(const uint8_t *uid, uint8_t uid_len, uint32_t timeout_ms,
		      uint8_t *verdict);

#endif /* TOMS_BLE_SVC_H */
