#ifndef TOMS_CODEC_H
#define TOMS_CODEC_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <toms/state_table.h>

/* Message framing for the GATT characteristics (firmware/PROTOCOL.md).
 *
 * The codec covers the payload only: it does NOT append the 8-byte HMAC
 * signature — the BLE layer signs the whole value with the per-machine key
 * before sending (and verifies writes the same way). Little-endian
 * throughout. */

/* paid_reply verdicts */
#define TOMS_VERDICT_OK 0u
#define TOMS_VERDICT_UNPAID 1u
#define TOMS_VERDICT_UNKNOWN 2u
#define TOMS_VERDICT_ALREADY_RETURNED 3u

/* card_read atype codes (PROTOCOL.md) */
#define TOMS_ATYPE_OTHER 0u   /* unknown / other ISO 14443-A */
#define TOMS_ATYPE_TYPE2 1u   /* NTAG21x, MIFARE Ultralight */
#define TOMS_ATYPE_TYPE4 2u   /* DESFire, ISO 14443-4 */
#define TOMS_ATYPE_CLASSIC 3u /* MIFARE Classic */

/* ack kinds */
#define TOMS_ACK_STATE 1u
#define TOMS_ACK_READ 2u

struct toms_card_read_msg {
	uint32_t read_seq;
	uint32_t timestamp; /* UTC epoch from time_sync, 0 = never synced */
	uint32_t boot_ms;
	uint8_t uid[TOMS_UID_MAX];
	uint8_t uid_len;
	uint8_t atype;        /* PN532_ATYPE_* */
	uint8_t state_cached; /* TOMS_STATUS_*, 0xFF = unknown */
};

struct toms_heartbeat_msg {
	uint32_t uptime_s;
	uint8_t battery_pct; /* 0xFF = not measurable */
	uint16_t battery_mv; /* 0 = not measurable */
	uint8_t nfc_status;  /* 0 ok */
	uint32_t state_version;
	uint16_t buffered_reads;
	uint8_t reset_reason;
};

/* Wire protocol revision reported by device_info. Bump on any layout change. */
#define TOMS_PROTOCOL_VERSION 1u

/* device_info flags */
#define TOMS_INFO_FLAG_DEV_MODE 0x01u /* unbonded writes accepted, sim_card enabled */
#define TOMS_INFO_FLAG_SIGNED 0x02u   /* values carry the 8-byte HMAC trailer */

#define TOMS_VEHICLE_ID_MAX 32u
#define TOMS_PAID_TIMEOUT_MIN_MS 1000u
#define TOMS_PAID_TIMEOUT_MAX_MS 2000u
#define TOMS_HEARTBEAT_MIN_S 1u
#define TOMS_HEARTBEAT_MAX_S 120u

struct toms_config_msg {
	uint8_t vehicle_id[TOMS_VEHICLE_ID_MAX];
	uint8_t vehicle_id_len;
	uint8_t tones_mask; /* bit n enables enum toms_tone value n */
	uint16_t paid_timeout_ms;
	uint16_t heartbeat_interval_s;
	uint8_t alarm_clear_policy;
};

struct toms_device_info {
	uint8_t protocol_version;
	uint8_t fw_major;
	uint8_t fw_minor;
	uint8_t fw_patch;
	uint8_t flags; /* TOMS_INFO_FLAG_* */
	uint16_t max_state_entries;
};

/* Defaults from PROTOCOL.md: all tones, 1500 ms paid timeout, 10 s heartbeat. */
void toms_config_defaults(struct toms_config_msg *c);

/* All encoders return the encoded length, or -ENOSPC if cap is too small,
 * -EINVAL on bad input. Decoders return the consumed length, or -EINVAL on
 * a malformed message. */

int toms_codec_encode_ack(uint8_t *buf, size_t cap, uint8_t kind, uint32_t value);

/* remaining_ms must be below 1000. */
int toms_codec_decode_time_sync(const uint8_t *data, size_t len, uint32_t *epoch,
				uint16_t *remaining_ms);

/* Rejects a paid timeout outside 1000-2000 ms and a heartbeat outside 1-120 s. */
int toms_codec_decode_config(const uint8_t *data, size_t len, struct toms_config_msg *c);

int toms_codec_encode_device_info(uint8_t *buf, size_t cap, const struct toms_device_info *i);

/* uid_len u8 + uid, used by the dev-mode sim_card write. */
int toms_codec_decode_uid(const uint8_t *data, size_t len, uint8_t *uid, uint8_t *uid_len);

int toms_codec_encode_card_read(uint8_t *buf, size_t cap, const struct toms_card_read_msg *m);
int toms_codec_decode_card_read(const uint8_t *data, size_t len, struct toms_card_read_msg *m);

int toms_codec_encode_heartbeat(uint8_t *buf, size_t cap, const struct toms_heartbeat_msg *m);

int toms_codec_encode_paid_check(uint8_t *buf, size_t cap, uint32_t check_seq, const uint8_t *uid,
				 uint8_t uid_len);

int toms_codec_decode_paid_reply(const uint8_t *data, size_t len, uint32_t *check_seq,
				 uint8_t *verdict);

int toms_codec_decode_ack(const uint8_t *data, size_t len, uint8_t *kind, uint32_t *value);

/* state_update decode: `entries` receives up to `max` entries; *count is the
 * entry count actually decoded; *snapshot reflects the flags bit. Returns
 * the consumed length, or -EINVAL on a malformed message. */
int toms_codec_decode_state_update(const uint8_t *data, size_t len, uint32_t *version,
				   bool *snapshot, struct toms_state_entry *entries,
				   uint16_t max, uint16_t *count);

#endif /* TOMS_CODEC_H */
