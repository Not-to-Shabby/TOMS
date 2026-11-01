/* Over-the-air update protocol, portable part (firmware/OTA_PLAN.md phase 4).
 *
 * Two characteristics carry it (PROTOCOL.md): ota_control (0x0C, write + notify) and ota_data
 * (0x0D, write without response). This file holds the byte layouts and the session rules, with
 * no Zephyr dependency, so they are unit tested on the host. The flash writing, hashing of the
 * slot and the reboot live in return-machine/src/ble/ota.c.
 *
 * All integers are little-endian.
 *
 *   control write   START  : op=1, size u32, sha256[32]        (37 bytes)
 *                   FINISH : op=2        ABORT : op=3        STATUS : op=4
 *   data write      offset u32, payload (1..TOMS_OTA_CHUNK_MAX bytes)
 *   control notify  reply  : op|0x80, status, offset u32, state, ver major, ver minor,
 *                            ver revision u16, flags                     (12 bytes)
 *                   events : op 0xA0 progress (bytes written), 0xB0 resync (a chunk arrived at
 *                            the wrong offset: send again from `offset`), 0xF0 failed
 *
 * Chunks must arrive in order. The machine never reorders: a chunk at any other offset is
 * dropped and answered once with a resync event carrying the offset it wants. That one rule
 * covers lost writes, a full queue, and resuming after a dropped link.
 */
#ifndef TOMS_OTA_H
#define TOMS_OTA_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define TOMS_OTA_OP_START 1
#define TOMS_OTA_OP_FINISH 2
#define TOMS_OTA_OP_ABORT 3
#define TOMS_OTA_OP_STATUS 4
#define TOMS_OTA_REPLY_FLAG 0x80
#define TOMS_OTA_EVENT_PROGRESS 0xA0
#define TOMS_OTA_EVENT_RESYNC 0xB0
#define TOMS_OTA_EVENT_FAILED 0xF0

#define TOMS_OTA_START_LEN 37
#define TOMS_OTA_REPLY_LEN 12
#define TOMS_OTA_CHUNK_HDR 4
/* One write holds at most 62 bytes (MTU 65 - 3 byte ATT header), 4 of them are the offset. */
#define TOMS_OTA_CHUNK_MAX 58
/* The MCUboot trailer lives at the end of the slot, so the image may not reach it. */
#define TOMS_OTA_TRAILER_RESERVE (16u * 1024u)

#define TOMS_OTA_FLAG_CONFIRMED 0x01 /* running image is confirmed */

enum toms_ota_status {
	TOMS_OTA_OK = 0,
	TOMS_OTA_BAD_MSG = 1,  /* malformed or unknown message */
	TOMS_OTA_BUSY = 2,     /* running image is still on trial, or a check is running */
	TOMS_OTA_STATE = 3,    /* no upload in progress, or wrong time for this message */
	TOMS_OTA_SIZE = 4,     /* image does not fit the slot, or data runs past the announced size */
	TOMS_OTA_OFFSET = 5,   /* wrong offset; the reply carries the offset wanted */
	TOMS_OTA_HASH = 6,     /* SHA-256 of what was stored differs from the announced one */
	TOMS_OTA_HEADER = 7,   /* stored data is not an MCUboot image */
	TOMS_OTA_VERSION = 8,  /* image is not newer than the running one */
	TOMS_OTA_SECURITY = 9, /* link not encrypted/bonded */
	TOMS_OTA_FLASH = 10,   /* flash write or erase failed */
	TOMS_OTA_BATTERY = 11, /* reserved: battery too low (phase 7) */
};

enum toms_ota_state {
	TOMS_OTA_IDLE = 0,
	TOMS_OTA_RECEIVING = 1,
	TOMS_OTA_VERIFYING = 2,
	TOMS_OTA_READY = 3, /* upgrade requested, the machine is about to reboot */
	TOMS_OTA_FAILED = 4,
};

struct toms_image_version {
	uint8_t major;
	uint8_t minor;
	uint16_t revision;
	uint32_t build;
};

struct toms_ota_session {
	uint8_t state;
	uint32_t size;      /* announced image size */
	uint32_t rx_next;   /* next offset accepted */
	uint32_t slot_size; /* size of the update slot */
	uint8_t sha[32];    /* announced digest */
};

/* --- byte layouts --- */
int toms_ota_decode_start(const uint8_t *data, size_t len, uint32_t *size, uint8_t sha[32]);
int toms_ota_decode_chunk(const uint8_t *data, size_t len, uint32_t *offset,
			  const uint8_t **payload, size_t *payload_len);
int toms_ota_encode_reply(uint8_t *out, size_t cap, uint8_t op, uint8_t status, uint32_t offset,
			  uint8_t state, const struct toms_image_version *running, uint8_t flags);

/* --- session rules --- */
void toms_ota_init(struct toms_ota_session *s, uint32_t slot_size);
/* Begin, or resume when the same size and digest are announced while receiving. Returns a
 * toms_ota_status; on TOMS_OTA_OK `*resumed` says whether data already received is kept. */
int toms_ota_start(struct toms_ota_session *s, uint32_t size, const uint8_t sha[32],
		   bool *resumed);
/* Accept the next chunk. On TOMS_OTA_OFFSET `*expected` is the offset wanted. */
int toms_ota_chunk(struct toms_ota_session *s, uint32_t offset, size_t len, uint32_t *expected);
/* All bytes received: go to VERIFYING. TOMS_OTA_OFFSET (with *expected) when short. */
int toms_ota_finish(struct toms_ota_session *s, uint32_t *expected);
void toms_ota_verified(struct toms_ota_session *s, bool ok);
void toms_ota_abort(struct toms_ota_session *s);

/* --- MCUboot image header (the 32 bytes at the start of the stored image) --- */
int toms_ota_parse_header(const uint8_t *hdr, size_t len, uint32_t stored_size,
			  struct toms_image_version *out);
/* <0 a older than b, 0 equal, >0 newer. The build number counts, so the bench can upload
 * "1.0.0+1" over "1.0.0". */
int toms_ota_version_cmp(const struct toms_image_version *a, const struct toms_image_version *b);

#endif /* TOMS_OTA_H */
