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

/* All encoders return the encoded length, or -ENOSPC if cap is too small,
 * -EINVAL on bad input. Decoders return the consumed length, or -EINVAL on
 * a malformed message. */

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
