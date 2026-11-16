/* Host tests for toms/sig.h and toms/readlog.h, plus the byte vectors from the phone's
 * RmProtocolTest.kt (toms_android/.../ble/RmProtocolTest.kt). Built by run_tests.sh. */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <toms/codec.h>
#include <toms/readlog.h>
#include <toms/sig.h>
#include <toms/state_table.h>

static int failures, checks;

#define CHECK(cond)                                                                              \
	do {                                                                                     \
		checks++;                                                                        \
		if (!(cond)) {                                                                   \
			failures++;                                                              \
			printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);                   \
		}                                                                                \
	} while (0)

static void hexs(const uint8_t *d, size_t n, char *out)
{
	for (size_t i = 0; i < n; i++) {
		sprintf(out + 2 * i, "%02x", d[i]);
	}
}

/* ---- HMAC-SHA256: RFC 4231 test cases 1 and 2 ---- */
static void test_hmac(void)
{
	uint8_t out[32];
	char h[65];
	uint8_t k1[20];

	memset(k1, 0x0b, sizeof(k1));
	toms_hmac_sha256(k1, sizeof(k1), (const uint8_t *)"Hi There", 8, out);
	hexs(out, 32, h);
	CHECK(strcmp(h, "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7") == 0);

	toms_hmac_sha256((const uint8_t *)"Jefe", 4,
			 (const uint8_t *)"what do ya want for nothing?", 28, out);
	hexs(out, 32, h);
	CHECK(strcmp(h, "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843") == 0);

	/* RFC 4231 case 6: key longer than the block size is hashed first */
	uint8_t k6[131];

	memset(k6, 0xaa, sizeof(k6));
	toms_hmac_sha256(k6, sizeof(k6),
			 (const uint8_t *)"Test Using Larger Than Block-Size Key - Hash Key First", 54,
			 out);
	hexs(out, 32, h);
	CHECK(strcmp(h, "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54") == 0);
}

static void test_sig(void)
{
	/* Test-only key, written here so the vectors are reproducible. Not a machine key. */
	uint8_t key[TOMS_SIG_KEY_LEN];

	for (size_t i = 0; i < sizeof(key); i++) {
		key[i] = (uint8_t)(0xA0u + i);
	}
	uint8_t buf[40] = {0x2A, 0, 0, 0, 4, 0x6F, 0xF1, 0xAD, 0x39};
	int n = toms_sig_append(key, sizeof(key), 0x03, buf, 9, sizeof(buf));

	CHECK(n == 17);
	CHECK(toms_sig_check(key, sizeof(key), 0x03, buf, 17, 9) == TOMS_SIG_OK);
	CHECK(toms_sig_check(key, sizeof(key), 0x03, buf, 9, 9) == TOMS_SIG_ABSENT);
	/* another characteristic, a flipped payload bit, a flipped trailer bit, a wrong key */
	CHECK(toms_sig_check(key, sizeof(key), 0x01, buf, 17, 9) == -EBADMSG);
	buf[1] ^= 1;
	CHECK(toms_sig_check(key, sizeof(key), 0x03, buf, 17, 9) == -EBADMSG);
	buf[1] ^= 1;
	buf[16] ^= 0x80;
	CHECK(toms_sig_check(key, sizeof(key), 0x03, buf, 17, 9) == -EBADMSG);
	buf[16] ^= 0x80;
	key[0] ^= 1;
	CHECK(toms_sig_check(key, sizeof(key), 0x03, buf, 17, 9) == -EBADMSG);
	key[0] ^= 1;
	CHECK(toms_sig_check(key, sizeof(key), 0x03, buf, 12, 9) == -EINVAL); /* partial trailer */
	CHECK(toms_sig_append(key, sizeof(key), 0x03, buf, 9, 16) == -ENOSPC);
	CHECK(toms_sig_append(NULL, 0, 0x03, buf, 9, 40) == -EINVAL);
}

/* ---- the phone's byte vectors (RmProtocolTest.kt) ---- */
static void test_phone_vectors(void)
{
	uint8_t b[64];
	char h[130];

	/* paid_check seq 42, uid 6F:F1:AD:39 */
	int n = toms_codec_encode_paid_check(b, sizeof(b), 42, (const uint8_t[]){0x6F, 0xF1, 0xAD, 0x39}, 4);

	CHECK(n == 9);
	hexs(b, (size_t)n, h);
	CHECK(strcmp(h, "2a000000046ff1ad39") == 0);

	/* paid_check seq 513, 7-byte uid */
	const uint8_t u7[7] = {0x04, 0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC};

	n = toms_codec_encode_paid_check(b, sizeof(b), 513, u7, 7);
	CHECK(n == 12);
	hexs(b, (size_t)n, h);
	CHECK(strcmp(h, "010200000704123456789abc") == 0);

	/* paid_reply seq 100 OK, seq 999 UNPAID, as encoded by the phone */
	uint32_t seq;
	uint8_t verdict;
	const uint8_t r1[5] = {100, 0, 0, 0, 0};
	const uint8_t r2[5] = {0xE7, 0x03, 0, 0, 1};

	CHECK(toms_codec_decode_paid_reply(r1, 5, &seq, &verdict) == 5 && seq == 100 && verdict == 0);
	CHECK(toms_codec_decode_paid_reply(r2, 5, &seq, &verdict) == 5 && seq == 999 && verdict == 1);

	/* time_sync 1700000000 s + 500 ms */
	uint32_t epoch;
	uint16_t rem;
	const uint8_t ts[6] = {0x00, 0xF1, 0x53, 0x65, 0xF4, 0x01};

	CHECK(toms_codec_decode_time_sync(ts, 6, &epoch, &rem) == 6);
	CHECK(epoch == 1700000000u && rem == 500);

	/* state_update snapshot v10 with the two entries from the test */
	const uint8_t su[] = {10, 0, 0, 0, 1, 2, 4, 0x6F, 0xF1, 0xAD, 0x39, 2,
			      7, 0x04, 0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 1};
	struct toms_state_entry e[4];
	uint32_t ver;
	bool snap;
	uint16_t cnt;

	CHECK(toms_codec_decode_state_update(su, sizeof(su), &ver, &snap, e, 4, &cnt) ==
	      (int)sizeof(su));
	CHECK(ver == 10 && snap && cnt == 2);
	CHECK(e[0].uid_len == 4 && e[0].status == TOMS_STATUS_ASSIGNED_PAID);
	CHECK(e[1].uid_len == 7 && e[1].status == TOMS_STATUS_ASSIGNED_UNPAID);

	/* heartbeat: 60 s, 85 %, 4132 mV, nfc ok, state v5, 2 buffered, reset 0 */
	struct toms_heartbeat_msg hb = {.uptime_s = 60, .battery_pct = 85, .battery_mv = 4132,
					.nfc_status = 0, .state_version = 5, .buffered_reads = 2,
					.reset_reason = 0};
	const uint8_t want[15] = {0x3C, 0, 0, 0, 85, 0x24, 0x10, 0, 5, 0, 0, 0, 2, 0, 0};

	CHECK(toms_codec_encode_heartbeat(b, sizeof(b), &hb) == 15);
	CHECK(memcmp(b, want, 15) == 0);

	/* rm_ack kind 1 version 7 */
	const uint8_t ack[5] = {1, 7, 0, 0, 0};

	CHECK(toms_codec_encode_ack(b, sizeof(b), TOMS_ACK_STATE, 7) == 5);
	CHECK(memcmp(b, ack, 5) == 0);

	/* alarm clear is diag 05 00 (RmProtocol.encodeAlarmClear) */
	/* The phone decodes card_read with a trailer of any length after the optional atype and
	 * state_cached bytes; a signed frame is therefore still parsed: */
	struct toms_card_read_msg cr = {.read_seq = 3, .timestamp = 1700000000u, .boot_ms = 99,
					.uid_len = 4, .atype = 1, .state_cached = 2};

	memcpy(cr.uid, (uint8_t[]){0x6F, 0xF1, 0xAD, 0x39}, 4);
	n = toms_codec_encode_card_read(b, sizeof(b), &cr);
	CHECK(n == 19);
	CHECK(b[12] == 4 && b[17] == 1 && b[18] == 2); /* layout the phone reads: 13 + uidLen */
}

/* ---- readlog on a fake flash that can lose power mid-write ---- */
#define CAP 8u

struct fake {
	uint8_t slot[CAP][TOMS_READLOG_REC];
	bool used[CAP];
	uint32_t hwm;
	bool hwm_set;
	int writes_until_cut; /* -1 = never; the cut write stores only half the record */
};

static int f_read(void *c, uint32_t s, uint8_t r[TOMS_READLOG_REC])
{
	struct fake *f = c;

	if (!f->used[s]) {
		return -ENOENT;
	}
	memcpy(r, f->slot[s], TOMS_READLOG_REC);
	return 0;
}

static int f_write(void *c, uint32_t s, const uint8_t r[TOMS_READLOG_REC])
{
	struct fake *f = c;

	if (f->writes_until_cut == 0) {
		memcpy(f->slot[s], r, TOMS_READLOG_REC / 2); /* torn write */
		f->used[s] = true;
		f->writes_until_cut = -1;
		return -EIO;
	}
	if (f->writes_until_cut > 0) {
		f->writes_until_cut--;
	}
	memcpy(f->slot[s], r, TOMS_READLOG_REC);
	f->used[s] = true;
	return 0;
}

static int f_erase(void *c, uint32_t s)
{
	struct fake *f = c;

	f->used[s] = false;
	return 0;
}

static int f_load(void *c, uint32_t *h)
{
	struct fake *f = c;

	if (!f->hwm_set) {
		return -ENOENT;
	}
	*h = f->hwm;
	return 0;
}

static int f_save(void *c, uint32_t h)
{
	struct fake *f = c;

	f->hwm = h;
	f->hwm_set = true;
	return 0;
}

static struct toms_readlog_io io_for(struct fake *f)
{
	struct toms_readlog_io io = {f, f_read, f_write, f_erase, f_load, f_save};

	return io;
}

static struct toms_card_read_msg msg_for(uint8_t id)
{
	struct toms_card_read_msg m = {.timestamp = 1700000000u + id, .boot_ms = id, .uid_len = 4,
				       .atype = 1, .state_cached = 0xFF};

	m.uid[0] = id;
	m.uid[1] = 0xAD;
	m.uid[2] = 0xBE;
	m.uid[3] = 0xEF;
	return m;
}

static void test_readlog_basic(void)
{
	struct fake f;
	struct toms_readlog l;

	memset(&f, 0, sizeof(f));
	f.writes_until_cut = -1;
	struct toms_readlog_io io = io_for(&f);

	CHECK(toms_readlog_open(&l, &io, CAP) == 0);
	CHECK(toms_readlog_pending(&l) == 0);
	for (uint8_t i = 1; i <= 5; i++) {
		struct toms_card_read_msg m = msg_for(i);

		CHECK(toms_readlog_append(&l, &m) == i); /* seq starts at 1 and counts up */
	}
	CHECK(toms_readlog_pending(&l) == 5);

	/* sent in order */
	uint8_t fr[32];
	uint32_t seq, from = 0;

	for (uint32_t want = 1; want <= 5; want++) {
		int n = toms_readlog_get_from(&l, from, fr, sizeof(fr), &seq);

		CHECK(n > 0 && seq == want && fr[13] == want);
		from = seq + 1u;
	}
	CHECK(toms_readlog_get_from(&l, from, fr, sizeof(fr), &seq) == -ENOENT);

	/* an ack frees up to and including seq 3; 4 and 5 stay */
	CHECK(toms_readlog_ack(&l, 3) == 0);
	CHECK(toms_readlog_pending(&l) == 2);
	CHECK(toms_readlog_get_from(&l, 0, fr, sizeof(fr), &seq) > 0 && seq == 4);
	/* a stale or repeated ack changes nothing */
	CHECK(toms_readlog_ack(&l, 2) == 0 && toms_readlog_pending(&l) == 2);
}

static void test_readlog_power_cycles(void)
{
	struct fake f;
	struct toms_readlog l;

	memset(&f, 0, sizeof(f));
	f.writes_until_cut = -1;
	struct toms_readlog_io io = io_for(&f);
	uint32_t last = 0;

	toms_readlog_open(&l, &io, CAP);
	for (int cycle = 0; cycle < 20; cycle++) {
		uint32_t before = last;

		/* three reads per cycle, the second cycle onwards the phone has acked the older ones */
		for (int k = 0; k < 3; k++) {
			struct toms_card_read_msg m = msg_for((uint8_t)(cycle * 3 + k));
			int32_t s = toms_readlog_append(&l, &m);

			CHECK(s > 0 && (uint32_t)s > last); /* never reused, never goes back */
			last = (uint32_t)s;
		}
		/* power cycle: only what is in the fake flash survives */
		memset(&l, 0, sizeof(l));
		CHECK(toms_readlog_open(&l, &io, CAP) == 0);
		CHECK(toms_readlog_pending(&l) == (cycle ? 6u : 3u)); /* all of it survived the cycle */

		uint8_t fr[32];
		uint32_t seq;
		CHECK(toms_readlog_get_from(&l, 0, fr, sizeof(fr), &seq) > 0);
		CHECK(toms_readlog_ack(&l, before) == 0); /* older cycles acked, this cycle's three stay */
		CHECK(toms_readlog_pending(&l) == 3);
		CHECK(toms_readlog_get_from(&l, 0, fr, sizeof(fr), &seq) > 0 && seq > before);
	}
}

static void test_readlog_live_seq(void)
{
	struct fake f;
	struct toms_readlog l;

	memset(&f, 0, sizeof(f));
	f.writes_until_cut = -1;
	struct toms_readlog_io io = io_for(&f);

	toms_readlog_open(&l, &io, CAP);
	/* three reads delivered live take numbers but leave no record */
	CHECK(toms_readlog_take_seq(&l) == 1);
	CHECK(toms_readlog_take_seq(&l) == 2);
	CHECK(toms_readlog_take_seq(&l) == 3);
	CHECK(toms_readlog_pending(&l) == 0);
	/* the fourth was made with the link down: stored under its own number */
	struct toms_card_read_msg m = msg_for(4);

	m.read_seq = (uint32_t)toms_readlog_take_seq(&l);
	CHECK(m.read_seq == 4 && toms_readlog_store(&l, &m) == 0);
	/* power cycle: numbering resumes above everything handed out, even the unstored 1..3 */
	memset(&l, 0, sizeof(l));
	toms_readlog_open(&l, &io, CAP);
	CHECK(toms_readlog_pending(&l) == 1);
	m = msg_for(5);
	CHECK(toms_readlog_append(&l, &m) >= 5); /* resumes at the saved mark, above 4 */
	/* with nothing stored at all, a power cycle still never goes back */
	toms_readlog_ack(&l, 100);
	memset(&l, 0, sizeof(l));
	toms_readlog_open(&l, &io, CAP);
	CHECK(toms_readlog_take_seq(&l) >= 6);
	CHECK(toms_readlog_store(&l, &(struct toms_card_read_msg){.read_seq = 0, .uid_len = 4}) == -EINVAL);
}

static void test_readlog_torn_write(void)
{
	struct fake f;
	struct toms_readlog l;

	memset(&f, 0, sizeof(f));
	f.writes_until_cut = -1;
	struct toms_readlog_io io = io_for(&f);

	toms_readlog_open(&l, &io, CAP);
	for (uint8_t i = 1; i <= 2; i++) {
		struct toms_card_read_msg m = msg_for(i);

		toms_readlog_append(&l, &m);
	}
	f.writes_until_cut = 0; /* power fails during the third write */
	struct toms_card_read_msg m3 = msg_for(3);

	CHECK(toms_readlog_append(&l, &m3) < 0);

	memset(&l, 0, sizeof(l));
	CHECK(toms_readlog_open(&l, &io, CAP) == 0);
	CHECK(toms_readlog_pending(&l) == 2); /* the torn record is not counted or sent */

	uint8_t fr[32];
	uint32_t seq = 0, from = 0;
	int got = 0;

	while (toms_readlog_get_from(&l, from, fr, sizeof(fr), &seq) > 0) {
		got++;
		from = seq + 1u;
		CHECK(seq <= 2);
	}
	CHECK(got == 2);

	/* the next read never reuses seq 3 (it was reserved before the cut) or earlier */
	struct toms_card_read_msg m4 = msg_for(4);
	int32_t s = toms_readlog_append(&l, &m4);

	CHECK(s >= 3);
}

static void test_readlog_overflow(void)
{
	struct fake f;
	struct toms_readlog l;

	memset(&f, 0, sizeof(f));
	f.writes_until_cut = -1;
	struct toms_readlog_io io = io_for(&f);

	toms_readlog_open(&l, &io, CAP);
	for (uint8_t i = 1; i <= CAP + 3u; i++) {
		struct toms_card_read_msg m = msg_for(i);

		CHECK(toms_readlog_append(&l, &m) == i);
	}
	CHECK(toms_readlog_pending(&l) == CAP); /* full */
	CHECK(l.dropped == 3);                  /* the three oldest unsent reads were overwritten */

	uint8_t fr[32];
	uint32_t seq;

	CHECK(toms_readlog_get_from(&l, 0, fr, sizeof(fr), &seq) > 0 && seq == 4);

	/* sent reads may be reused without counting a loss */
	toms_readlog_mark_sent(&l, CAP + 3u);
	CHECK(toms_readlog_unsent(&l) == 0);
	struct toms_card_read_msg m = msg_for(99);

	CHECK(toms_readlog_append(&l, &m) == (int32_t)(CAP + 4u));
	CHECK(l.dropped == 3);
	CHECK(toms_readlog_unsent(&l) == 1);

	/* a link drop rewinds: the last two reads are offered again, nothing older */
	toms_readlog_rewind(&l, CAP + 3u);
	CHECK(toms_readlog_unsent(&l) == 2);
	toms_readlog_rewind(&l, 1); /* clamped to what is still stored */
	CHECK(toms_readlog_unsent(&l) == CAP);
	toms_readlog_mark_sent(&l, CAP + 4u);

	/* after a reboot nothing counts as sent: everything still stored goes out again */
	memset(&l, 0, sizeof(l));
	CHECK(toms_readlog_open(&l, &io, CAP) == 0);
	CHECK(toms_readlog_unsent(&l) == CAP);
}

int main(void)
{
	test_hmac();
	test_sig();
	test_phone_vectors();
	test_readlog_basic();
	test_readlog_power_cycles();
	test_readlog_live_seq();
	test_readlog_torn_write();
	test_readlog_overflow();
	printf("%d checks, %d failures\n", checks, failures);
	return failures ? 1 : 0;
}
