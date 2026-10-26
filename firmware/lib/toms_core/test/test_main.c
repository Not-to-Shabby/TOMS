/* Host-side unit tests for the portable TOMS core (no Zephyr needed).
 * Build and run with test/run_tests.sh. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <toms/codec.h>
#include <toms/crc16.h>
#include <toms/ring.h>
#include <toms/state_table.h>

static int failures;
static int checks;

#define CHECK(cond)                                                                              \
	do {                                                                                     \
		checks++;                                                                        \
		if (!(cond)) {                                                                   \
			failures++;                                                              \
			printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);                   \
		}                                                                                \
	} while (0)

static void test_crc16(void);
static void test_ring(void);
static void test_state_table(void);
static void test_codec(void);
static const uint8_t *uid_a4(void);

int main(void)
{
	test_crc16();
	test_ring();
	test_state_table();
	test_codec();

	printf("%d checks, %d failures\n", checks, failures);
	return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

static void test_crc16(void)
{
	/* CRC-16/CCITT-FALSE check value for "123456789" is 0x29B1. */
	CHECK(toms_crc16((const uint8_t *)"123456789", 9) == 0x29B1u);
	CHECK(toms_crc16(NULL, 0) == 0xFFFFu);
}

static void test_ring(void)
{
	struct toms_ring r;
	toms_ring_init(&r, 4);
	CHECK(toms_ring_empty(&r));
	CHECK(toms_ring_pop_slot(&r) == TOMS_RING_NO_SLOT);
	CHECK(toms_ring_peek_slot(&r, 0) == TOMS_RING_NO_SLOT);

	uint32_t pushed[10];
	int n = 0;
	for (; n < 4; n++) {
		pushed[n] = toms_ring_push_slot(&r);
		CHECK(pushed[n] == (uint32_t)n);
	}
	CHECK(toms_ring_full(&r));
	CHECK(toms_ring_push_slot(&r) == TOMS_RING_NO_SLOT);

	/* Oldest-first peek order across the wrap boundary. */
	CHECK(toms_ring_peek_slot(&r, 0) == 0);
	CHECK(toms_ring_peek_slot(&r, 3) == 3);

	CHECK(toms_ring_pop_slot(&r) == 0);
	CHECK(toms_ring_pop_slot(&r) == 1);
	CHECK(toms_ring_count(&r) == 2);

	/* Wrap: next pushes land on slots 0 and 1, order stays oldest first. */
	CHECK(toms_ring_push_slot(&r) == 0);
	CHECK(toms_ring_push_slot(&r) == 1);
	CHECK(toms_ring_full(&r));
	CHECK(toms_ring_peek_slot(&r, 0) == 2);
	CHECK(toms_ring_peek_slot(&r, 3) == 1);

	/* Capacity 1 degenerates to a single slot. */
	toms_ring_init(&r, 1);
	CHECK(toms_ring_push_slot(&r) == 0);
	CHECK(toms_ring_full(&r));
	CHECK(toms_ring_push_slot(&r) == TOMS_RING_NO_SLOT);
	CHECK(toms_ring_pop_slot(&r) == 0);
	CHECK(toms_ring_empty(&r));
}

static void test_state_table(void)
{
	struct toms_state_table t;
	toms_state_table_init(&t);

	const uint8_t uid_a[] = { 0x01, 0x02, 0x03, 0x04 };
	const uint8_t uid_b[] = { 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6 };
	struct toms_state_entry e;

	CHECK(toms_state_table_lookup(&t, uid_a, 4) == TOMS_STATUS_UNKNOWN);

	memcpy(e.uid, uid_a, 4);
	e.uid_len = 4;
	e.status = TOMS_STATUS_ASSIGNED_UNPAID;
	CHECK(toms_state_table_apply(&t, 1, false, &e, 1) == 0);
	CHECK(toms_state_table_lookup(&t, uid_a, 4) == TOMS_STATUS_ASSIGNED_UNPAID);

	/* Delta on the same card replaces its status, version advances. */
	e.status = TOMS_STATUS_ASSIGNED_PAID;
	CHECK(toms_state_table_apply(&t, 2, false, &e, 1) == 0);
	CHECK(toms_state_table_lookup(&t, uid_a, 4) == TOMS_STATUS_ASSIGNED_PAID);
	CHECK(t.count == 1);

	/* Stale version is rejected. */
	e.status = TOMS_STATUS_AVAILABLE;
	CHECK(toms_state_table_apply(&t, 1, false, &e, 1) == -EINVAL);
	CHECK(toms_state_table_lookup(&t, uid_a, 4) == TOMS_STATUS_ASSIGNED_PAID);

	/* Delta adds a second card. */
	memcpy(e.uid, uid_b, 7);
	e.uid_len = 7;
	e.status = TOMS_STATUS_RETURNED_UNPAID;
	CHECK(toms_state_table_apply(&t, 3, false, &e, 1) == 0);
	CHECK(t.count == 2);
	CHECK(toms_state_table_lookup(&t, uid_b, 7) == TOMS_STATUS_RETURNED_UNPAID);

	/* Snapshot replaces the whole table. */
	struct toms_state_entry snap[2];
	memset(snap, 0, sizeof(snap));
	memcpy(snap[0].uid, uid_b, 7);
	snap[0].uid_len = 7;
	snap[0].status = TOMS_STATUS_AVAILABLE;
	memcpy(snap[1].uid, uid_a, 4);
	snap[1].uid_len = 4;
	snap[1].status = TOMS_STATUS_LOST;
	CHECK(toms_state_table_apply(&t, 4, true, snap, 2) == 0);
	CHECK(t.count == 2);
	CHECK(toms_state_table_lookup(&t, uid_a, 4) == TOMS_STATUS_LOST);
	CHECK(toms_state_table_lookup(&t, uid_b, 7) == TOMS_STATUS_AVAILABLE);

	/* Malformed entries are rejected without touching the table. */
	struct toms_state_entry bad = { .uid = { 0 }, .uid_len = 11, .status = 0 };
	CHECK(toms_state_table_apply(&t, 5, false, &bad, 1) == -EINVAL);

	/* UID length mismatch means a different card. */
	CHECK(toms_state_table_lookup(&t, uid_a, 7) == TOMS_STATUS_UNKNOWN);
}

static void test_codec(void)
{
	const uint8_t uid7[] = { 0x04, 0xA2, 0x03, 0x11, 0x22, 0x33, 0x44 };
	uint8_t buf[64];

	/* card_read round-trip with a 7-byte UID. */
	struct toms_card_read_msg m = {
		.read_seq = 0x11223344u,
		.timestamp = 1700000000u,
		.boot_ms = 98765u,
		.uid_len = 7,
		.atype = TOMS_ATYPE_TYPE2,
		.state_cached = TOMS_STATUS_ASSIGNED_PAID,
	};
	memcpy(m.uid, uid7, 7);
	int len = toms_codec_encode_card_read(buf, sizeof(buf), &m);
	CHECK(len == 15 + 7);

	struct toms_card_read_msg out;
	CHECK(toms_codec_decode_card_read(buf, (size_t)len, &out) == len);
	CHECK(out.read_seq == m.read_seq);
	CHECK(out.timestamp == m.timestamp);
	CHECK(out.boot_ms == m.boot_ms);
	CHECK(out.uid_len == 7);
	CHECK(memcmp(out.uid, uid7, 7) == 0);
	CHECK(out.atype == TOMS_ATYPE_TYPE2);
	CHECK(out.state_cached == TOMS_STATUS_ASSIGNED_PAID);

	/* Trailing signature bytes after the payload are tolerated by the
	 * decoder (the BLE layer appends them); a truncated payload is not. */
	CHECK(toms_codec_decode_card_read(buf, (size_t)len + 8, &out) == len);
	CHECK(toms_codec_decode_card_read(buf, 10, &out) == -EINVAL);

	/* Encoders reject over-long or empty UIDs. */
	m.uid_len = 11;
	CHECK(toms_codec_encode_card_read(buf, sizeof(buf), &m) == -EINVAL);
	m.uid_len = 0;
	CHECK(toms_codec_encode_card_read(buf, sizeof(buf), &m) == -EINVAL);
	m.uid_len = 7;

	/* heartbeat: exact little-endian layout */
	struct toms_heartbeat_msg h = {
		.uptime_s = 3600u,
		.battery_pct = 0xFF,
		.battery_mv = 3900u,
		.nfc_status = 0,
		.state_version = 42u,
		.buffered_reads = 3u,
		.reset_reason = 2u,
	};
	len = toms_codec_encode_heartbeat(buf, sizeof(buf), &h);
	CHECK(len == 15);
	static const uint8_t expect[15] = { 0x10, 0x0E, 0x00, 0x00, 0xFF, 0x3C, 0x0F, 0x00,
					    0x2A, 0x00, 0x00, 0x00, 0x03, 0x00, 0x02 };
	CHECK(memcmp(buf, expect, 15) == 0);

	/* paid_check / paid_reply / ack framing */
	len = toms_codec_encode_paid_check(buf, sizeof(buf), 7u, uid7, 7);
	CHECK(len == 12);
	CHECK(buf[4] == 7);

	uint32_t seq;
	uint8_t verdict;
	const uint8_t reply[5] = { 0x07, 0x00, 0x00, 0x00, TOMS_VERDICT_UNPAID };
	CHECK(toms_codec_decode_paid_reply(reply, 5, &seq, &verdict) == 5);
	CHECK(seq == 7u && verdict == TOMS_VERDICT_UNPAID);

	uint8_t kind;
	uint32_t value;
	const uint8_t ack[5] = { TOMS_ACK_READ, 0x0A, 0x00, 0x00, 0x00 };
	CHECK(toms_codec_decode_ack(ack, 5, &kind, &value) == 5);
	CHECK(kind == TOMS_ACK_READ && value == 10u);

	/* state_update decode, delta with two entries */
	uint8_t su[64];
	size_t off = 0;
	su[off++] = 0x05; su[off++] = 0x00; su[off++] = 0x00; su[off++] = 0x00; /* version 5 */
	su[off++] = 0x00; /* flags: delta (bit0 set would mean a snapshot) */
	su[off++] = 0x02; /* count */
	su[off++] = 4;
	memcpy(&su[off], uid_a4(), 4); off += 4;
	su[off++] = TOMS_STATUS_ASSIGNED_PAID;
	su[off++] = 7;
	memcpy(&su[off], uid7, 7); off += 7;
	su[off++] = TOMS_STATUS_RETURNED_UNPAID;

	uint32_t version;
	bool snapshot;
	struct toms_state_entry entries[4];
	uint16_t count = 0;
	CHECK(toms_codec_decode_state_update(su, off, &version, &snapshot, entries, 4, &count) ==
	      (int)off);
	CHECK(version == 5u && !snapshot && count == 2);
	CHECK(entries[0].uid_len == 4 && entries[0].status == TOMS_STATUS_ASSIGNED_PAID);
	CHECK(entries[1].uid_len == 7 && entries[1].status == TOMS_STATUS_RETURNED_UNPAID);

	/* Truncated state_update is rejected. */
	CHECK(toms_codec_decode_state_update(su, off - 1, &version, &snapshot, entries, 4,
					     &count) == -EINVAL);

	/* Too many entries for the caller's buffer: a valid 3-entry message
	 * decoded with room for 2 must fail with -EMSGSIZE, not a parse error. */
	uint8_t su3[64];
	off = 0;
	su3[off++] = 0x05; su3[off++] = 0x00; su3[off++] = 0x00; su3[off++] = 0x00;
	su3[off++] = 0x00; /* delta */
	su3[off++] = 0x03; /* count */
	for (int i = 0; i < 3; i++) {
		su3[off++] = 4;
		memcpy(&su3[off], uid_a4(), 4);
		off += 4;
		su3[off++] = TOMS_STATUS_AVAILABLE;
	}
	CHECK(toms_codec_decode_state_update(su3, off, &version, &snapshot, entries, 2, &count) ==
	      -EMSGSIZE);
	CHECK(toms_codec_decode_state_update(su3, off, &version, &snapshot, entries, 4, &count) ==
	      (int)off);
	CHECK(count == 3);
}

/* helper so the state_update test compiles before uid_a's scope exists */
static const uint8_t *uid_a4(void)
{
	static const uint8_t uid[4] = { 0x01, 0x02, 0x03, 0x04 };
	return uid;
}
