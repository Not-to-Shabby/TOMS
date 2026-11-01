/* Host-side unit tests for the portable TOMS core (no Zephyr needed).
 * Build and run with test/run_tests.sh. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <toms/clock.h>
#include <toms/codec.h>
#include <toms/crc16.h>
#include <toms/guard.h>
#include <toms/reset.h>
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
static void test_api(void);
static void test_guard(void);
static void test_reset(void);
static const uint8_t *uid_a4(void);

int main(void)
{
	test_crc16();
	test_ring();
	test_state_table();
	test_codec();
	test_api();
	test_guard();
	test_reset();

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

static void test_api(void)
{
	uint8_t buf[64];

	/* ack encodes the exact layout the decoder reads. */
	CHECK(toms_codec_encode_ack(buf, sizeof(buf), TOMS_ACK_STATE, 0x01020304u) == 5);
	const uint8_t ack_expect[5] = { TOMS_ACK_STATE, 0x04, 0x03, 0x02, 0x01 };
	CHECK(memcmp(buf, ack_expect, 5) == 0);
	uint8_t kind;
	uint32_t value;
	CHECK(toms_codec_decode_ack(buf, 5, &kind, &value) == 5);
	CHECK(kind == TOMS_ACK_STATE && value == 0x01020304u);
	CHECK(toms_codec_encode_ack(buf, 4, TOMS_ACK_STATE, 1u) == -ENOSPC);

	/* time_sync: 6 bytes, sub-second remainder below 1000. */
	const uint8_t ts[6] = { 0x00, 0xF1, 0x53, 0x65, 0xE7, 0x03 }; /* 1700000000, 999 */
	uint32_t epoch;
	uint16_t rem;
	CHECK(toms_codec_decode_time_sync(ts, 6, &epoch, &rem) == 6);
	CHECK(epoch == 1700000000u && rem == 999u);
	const uint8_t ts_bad[6] = { 0x00, 0xF1, 0x53, 0x65, 0xE8, 0x03 }; /* remainder 1000 */
	CHECK(toms_codec_decode_time_sync(ts_bad, 6, &epoch, &rem) == -EINVAL);
	CHECK(toms_codec_decode_time_sync(ts, 5, &epoch, &rem) == -EINVAL);

	/* clock: unsynced reads 0, then follows uptime from the sync point. */
	struct toms_clock clk;
	toms_clock_init(&clk);
	CHECK(toms_clock_epoch(&clk, 5000) == 0);
	CHECK(toms_clock_sync(&clk, 1700000000u, 500u, 10000) == 0);
	CHECK(toms_clock_epoch(&clk, 10000) == 1700000000u);
	CHECK(toms_clock_epoch(&clk, 10499) == 1700000000u);
	CHECK(toms_clock_epoch(&clk, 10500) == 1700000001u);
	CHECK(toms_clock_epoch(&clk, 70500) == 1700000061u);
	CHECK(toms_clock_sync(&clk, 1u, 1000u, 0) == -EINVAL);

	/* config: defaults, a full valid message, and every range check. */
	struct toms_config_msg cfg;
	toms_config_defaults(&cfg);
	CHECK(cfg.tones_mask == 0xFF && cfg.paid_timeout_ms == 1500 &&
	      cfg.heartbeat_interval_s == 10 && cfg.vehicle_id_len == 0 &&
	      cfg.alarm_clear_policy == 0);

	uint8_t cm[64];
	size_t o = 0;
	cm[o++] = 3; /* vehicle id "ABC" */
	cm[o++] = 'A'; cm[o++] = 'B'; cm[o++] = 'C';
	cm[o++] = 0x3F;               /* tones mask */
	cm[o++] = 0xDC; cm[o++] = 0x05; /* 1500 ms */
	cm[o++] = 0x1E; cm[o++] = 0x00; /* 30 s */
	cm[o++] = 1;                  /* alarm clear policy */
	CHECK(toms_codec_decode_config(cm, o, &cfg) == (int)o);
	CHECK(cfg.vehicle_id_len == 3 && memcmp(cfg.vehicle_id, "ABC", 3) == 0);
	CHECK(cfg.tones_mask == 0x3F && cfg.paid_timeout_ms == 1500 &&
	      cfg.heartbeat_interval_s == 30 && cfg.alarm_clear_policy == 1);
	CHECK(toms_codec_decode_config(cm, o - 1, &cfg) == -EINVAL);

	cm[5] = 0xE7; cm[6] = 0x03; /* 999 ms is below the 1000 ms floor */
	CHECK(toms_codec_decode_config(cm, o, &cfg) == -EINVAL);
	cm[5] = 0xD1; cm[6] = 0x07; /* 2001 ms is above the 2000 ms ceiling */
	CHECK(toms_codec_decode_config(cm, o, &cfg) == -EINVAL);
	cm[5] = 0xDC; cm[6] = 0x05;
	cm[7] = 0x00; cm[8] = 0x00; /* heartbeat 0 s */
	CHECK(toms_codec_decode_config(cm, o, &cfg) == -EINVAL);
	cm[7] = 0x79; /* 121 s */
	CHECK(toms_codec_decode_config(cm, o, &cfg) == -EINVAL);
	cm[7] = 0x1E;
	cm[0] = 33; /* vehicle id longer than the maximum */
	CHECK(toms_codec_decode_config(cm, sizeof(cm), &cfg) == -EINVAL);

	/* device_info: exact bytes. */
	struct toms_device_info di = {
		.protocol_version = TOMS_PROTOCOL_VERSION,
		.fw_major = 0, .fw_minor = 1, .fw_patch = 2,
		.flags = TOMS_INFO_FLAG_DEV_MODE,
		.max_state_entries = TOMS_STATE_TABLE_MAX,
	};
	CHECK(toms_codec_encode_device_info(buf, sizeof(buf), &di) == 7);
	const uint8_t di_expect[7] = { 1, 0, 1, 2, 0x01, 0x80, 0x00 };
	CHECK(memcmp(buf, di_expect, 7) == 0);
	CHECK(toms_codec_encode_device_info(buf, 6, &di) == -ENOSPC);

	/* uid write for sim_card. */
	const uint8_t uw[5] = { 4, 0xDE, 0xAD, 0xBE, 0xEF };
	uint8_t uid[TOMS_UID_MAX];
	uint8_t uid_len;
	CHECK(toms_codec_decode_uid(uw, 5, uid, &uid_len) == 5);
	CHECK(uid_len == 4 && uid[0] == 0xDE && uid[3] == 0xEF);
	CHECK(toms_codec_decode_uid(uw, 4, uid, &uid_len) == -EINVAL);
	const uint8_t uw0[1] = { 0 };
	CHECK(toms_codec_decode_uid(uw0, 1, uid, &uid_len) == -EINVAL);
}

/* helper so the state_update test compiles before uid_a's scope exists */
static const uint8_t *uid_a4(void)
{
	static const uint8_t uid[4] = { 0x01, 0x02, 0x03, 0x04 };
	return uid;
}

static void test_guard(void)
{
	struct toms_selftest st;

	/* Healthy for the whole streak: passes on the last good check, not before. */
	toms_selftest_init(&st, 3, 30000);
	CHECK(toms_selftest_tick(&st, 1000, true) == TOMS_SELFTEST_PENDING);
	CHECK(toms_selftest_tick(&st, 2000, true) == TOMS_SELFTEST_PENDING);
	CHECK(toms_selftest_tick(&st, 3000, true) == TOMS_SELFTEST_PASSED);

	/* A bad check resets the streak, so three goods in a row are needed again. */
	toms_selftest_init(&st, 3, 30000);
	toms_selftest_tick(&st, 1000, true);
	toms_selftest_tick(&st, 2000, true);
	CHECK(toms_selftest_tick(&st, 3000, false) == TOMS_SELFTEST_PENDING);
	CHECK(toms_selftest_tick(&st, 4000, true) == TOMS_SELFTEST_PENDING);
	CHECK(toms_selftest_tick(&st, 5000, true) == TOMS_SELFTEST_PENDING);
	CHECK(toms_selftest_tick(&st, 6000, true) == TOMS_SELFTEST_PASSED);

	/* Never healthy: fails at the deadline and not a tick earlier. */
	toms_selftest_init(&st, 3, 30000);
	CHECK(toms_selftest_tick(&st, 29999, false) == TOMS_SELFTEST_PENDING);
	CHECK(toms_selftest_tick(&st, 30000, false) == TOMS_SELFTEST_FAILED);

	/* Healthy too late: the streak completes after the deadline, so it fails. */
	toms_selftest_init(&st, 3, 30000);
	toms_selftest_tick(&st, 29000, true);
	toms_selftest_tick(&st, 29500, true);
	CHECK(toms_selftest_tick(&st, 30500, true) == TOMS_SELFTEST_PASSED); /* completes the streak */

	toms_selftest_init(&st, 3, 30000);
	toms_selftest_tick(&st, 29000, true);
	CHECK(toms_selftest_tick(&st, 30500, true) == TOMS_SELFTEST_FAILED); /* deadline hits first */

	/* Results are final. */
	toms_selftest_init(&st, 1, 1000);
	CHECK(toms_selftest_tick(&st, 10, true) == TOMS_SELFTEST_PASSED);
	CHECK(toms_selftest_tick(&st, 99999, false) == TOMS_SELFTEST_PASSED);
	toms_selftest_init(&st, 1, 1000);
	CHECK(toms_selftest_tick(&st, 1000, false) == TOMS_SELFTEST_FAILED);
	CHECK(toms_selftest_tick(&st, 1001, true) == TOMS_SELFTEST_FAILED);

	/* need_good of 0 is treated as 1: one healthy check is still required. */
	toms_selftest_init(&st, 0, 1000);
	CHECK(st.need_good == 1u);
	CHECK(toms_selftest_tick(&st, 10, false) == TOMS_SELFTEST_PENDING);

	/* Liveness: the first call primes and accepts. */
	struct toms_liveness lv;
	uint32_t c[3] = { 5, 7, 9 };
	toms_liveness_init(&lv);
	CHECK(toms_liveness_check(&lv, c, 3));
	/* Every counter moved. */
	c[0] = 6; c[1] = 8; c[2] = 10;
	CHECK(toms_liveness_check(&lv, c, 3));
	/* One stuck counter (the nfc task, say) refuses the feed even though the others move. */
	c[0] = 7; c[1] = 8; c[2] = 11;
	CHECK(!toms_liveness_check(&lv, c, 3));
	/* The refusal still records the snapshot, so recovery on the next check is seen. */
	c[0] = 8; c[1] = 9; c[2] = 12;
	CHECK(toms_liveness_check(&lv, c, 3));
	/* A counter that wraps around counts as moved. */
	c[0] = 0xFFFFFFFFu; c[1] = 10; c[2] = 13;
	CHECK(toms_liveness_check(&lv, c, 3));
	c[0] = 0u; c[1] = 11; c[2] = 14;
	CHECK(toms_liveness_check(&lv, c, 3));
	/* Bad arguments are refused, not trusted. */
	CHECK(!toms_liveness_check(&lv, c, 0));
	CHECK(!toms_liveness_check(&lv, c, TOMS_LIVENESS_MAX + 1u));
	CHECK(!toms_liveness_check(&lv, NULL, 3));
	CHECK(!toms_liveness_check(NULL, c, 3));
}

static void test_reset(void)
{
	/* Watchdog causes, by the ESP32-S3 values in ESP-IDF's reset_reasons.h. */
	static const uint32_t wdt[] = { 0x07, 0x08, 0x09, 0x0B, 0x0D, 0x10, 0x11, 0x12 };
	for (size_t i = 0; i < sizeof(wdt) / sizeof(wdt[0]); i++) {
		CHECK(toms_reset_classify(wdt[i]) == TOMS_RESET_WATCHDOG);
		CHECK(toms_reset_is_watchdog(wdt[i]));
	}

	/* The values the old table got wrong. */
	CHECK(toms_reset_classify(0x0C) == TOMS_RESET_SOFTWARE); /* CPU software reset, NOT a watchdog */
	CHECK(!toms_reset_is_watchdog(0x0C));
	CHECK(toms_reset_classify(0x15) == TOMS_RESET_PIN_DEBUG); /* USB UART reset */
	CHECK(toms_reset_classify(0x16) == TOMS_RESET_PIN_DEBUG); /* USB JTAG reset */
	CHECK(toms_reset_classify(0x11) == TOMS_RESET_WATCHDOG);  /* used to fall through to "other" */
	CHECK(toms_reset_classify(0x12) == TOMS_RESET_WATCHDOG);

	CHECK(toms_reset_classify(0x01) == TOMS_RESET_POWER_ON);
	CHECK(toms_reset_classify(0x03) == TOMS_RESET_SOFTWARE);
	CHECK(toms_reset_classify(0x0F) == TOMS_RESET_BROWNOUT);
	CHECK(toms_reset_classify(0x17) == TOMS_RESET_BROWNOUT);

	/* Unknown values are "other" and never claim to be a watchdog. */
	CHECK(toms_reset_classify(0) == TOMS_RESET_OTHER);
	CHECK(toms_reset_classify(0x02) == TOMS_RESET_OTHER);
	CHECK(toms_reset_classify(0xFFFFFFFFu) == TOMS_RESET_OTHER);
	CHECK(!toms_reset_is_watchdog(0x04));
	CHECK(!toms_reset_is_watchdog(0xFFFFFFFFu));

	/* Names exist for every known value and for unknown ones. */
	CHECK(strstr(toms_reset_cause_name(0x07), "main watchdog") != NULL);
	CHECK(strstr(toms_reset_cause_name(0x15), "USB UART") != NULL);
	CHECK(strcmp(toms_reset_cause_name(0x99), "unrecognised cause") == 0);
	for (uint32_t v = 0; v < 64; v++) {
		CHECK(toms_reset_cause_name(v) != NULL && toms_reset_cause_name(v)[0] != '\0');
	}
}
