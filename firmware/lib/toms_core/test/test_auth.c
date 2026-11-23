/* Host tests for toms/pairing.h, toms/authz.h and toms/keyring.h: who may pair, who may send which diag
 * command, and the signing-key replace flow. Built by run_tests.sh and check_all.sh.
 *
 * The keys below are test-only values made up in this file. They are not, and never were, a machine's key. */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <toms/authz.h>
#include <toms/bondclr.h>
#include <toms/keyring.h>
#include <toms/pairing.h>
#include <toms/sig.h>

static int failures, checks;

#define CHECK(cond)                                                                              \
	do {                                                                                     \
		checks++;                                                                        \
		if (!(cond)) {                                                                   \
			failures++;                                                              \
			printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);                   \
		}                                                                                \
	} while (0)

static void make_key(uint8_t k[TOMS_SIG_KEY_LEN], uint8_t seed)
{
	for (size_t i = 0; i < TOMS_SIG_KEY_LEN; i++) {
		k[i] = (uint8_t)(seed + i * 7u);
	}
}

/* ================================================================== pairing window and the one bond */
static void test_pair_window(void)
{
	struct toms_pair_window w;

	toms_pair_window_init(&w);
	CHECK(!toms_pair_window_is_open(&w, 0));
	CHECK(toms_pair_window_left_ms(&w, 0) == 0);

	toms_pair_window_open(&w, 1000, TOMS_PAIR_WINDOW_DEFAULT_MS);
	CHECK(toms_pair_window_is_open(&w, 1000));
	CHECK(toms_pair_window_left_ms(&w, 1000) == 60000u);
	CHECK(toms_pair_window_is_open(&w, 60999));
	CHECK(toms_pair_window_left_ms(&w, 31000) == 30000u);
	CHECK(!toms_pair_window_is_open(&w, 61000)); /* times out after 60 s, exactly */
	CHECK(!toms_pair_window_is_open(&w, 61001)); /* and stays closed */
	CHECK(toms_pair_window_left_ms(&w, 61001) == 0);

	/* opening again restarts the time */
	toms_pair_window_open(&w, 70000, 60000);
	CHECK(toms_pair_window_is_open(&w, 100000));
	toms_pair_window_open(&w, 100000, 60000);
	CHECK(toms_pair_window_is_open(&w, 159999) && !toms_pair_window_is_open(&w, 160000));

	/* closing */
	toms_pair_window_open(&w, 0, 60000);
	toms_pair_window_close(&w);
	CHECK(!toms_pair_window_is_open(&w, 1));

	/* a zero duration never opens */
	toms_pair_window_open(&w, 5, 0);
	CHECK(!toms_pair_window_is_open(&w, 5));

	/* the 32-bit millisecond clock wraps after 49.7 days; a window that spans the wrap still works */
	toms_pair_window_open(&w, 0xFFFFFFF0u, 60000);
	CHECK(toms_pair_window_is_open(&w, 0xFFFFFFF0u));
	CHECK(toms_pair_window_is_open(&w, 20000));
	CHECK(toms_pair_window_left_ms(&w, 20000) == 0xFFFFFFF0u + 60000u - 20000u);
	CHECK(!toms_pair_window_is_open(&w, 59990)); /* 0xFFFFFFF0 + 60000 wraps to 59984 */
}

static void test_pair_decide(void)
{
	struct toms_pair_window w;

	toms_pair_window_init(&w);

	/* closed: nobody pairs, whatever they are */
	CHECK(toms_pair_decide(&w, 0, TOMS_IO_DISPLAY_YESNO, 0, false) == TOMS_PAIR_DENY_CLOSED);
	CHECK(toms_pair_decide(&w, 0, TOMS_IO_DISPLAY_YESNO, 1, true) == TOMS_PAIR_DENY_CLOSED);

	toms_pair_window_open(&w, 0, 60000);
	/* open, no bond yet: an authenticated-capable peer pairs */
	CHECK(toms_pair_decide(&w, 100, TOMS_IO_DISPLAY_YESNO, 0, false) == TOMS_PAIR_ALLOW);
	CHECK(toms_pair_decide(&w, 100, TOMS_IO_KEYBOARD_DISPLAY, 0, false) == TOMS_PAIR_ALLOW);
	CHECK(toms_pair_decide(&w, 100, TOMS_IO_KEYBOARD_ONLY, 0, false) == TOMS_PAIR_ALLOW);
	/* Just Works candidates are refused: they can only end in an unauthenticated link */
	CHECK(toms_pair_decide(&w, 100, TOMS_IO_NO_INPUT_NO_OUTPUT, 0, false) == TOMS_PAIR_DENY_IO);
	CHECK(toms_pair_decide(&w, 100, TOMS_IO_DISPLAY_ONLY, 0, false) == TOMS_PAIR_DENY_IO);
	CHECK(toms_pair_decide(&w, 100, 9, 0, false) == TOMS_PAIR_DENY_IO); /* unknown capability */
	/* one bond: another central is refused even with the window open, the same peer may pair again */
	CHECK(toms_pair_decide(&w, 100, TOMS_IO_DISPLAY_YESNO, 1, false) == TOMS_PAIR_DENY_FULL);
	CHECK(toms_pair_decide(&w, 100, TOMS_IO_DISPLAY_YESNO, 1, true) == TOMS_PAIR_ALLOW);
	CHECK(toms_pair_decide(&w, 100, TOMS_IO_DISPLAY_YESNO, 5, false) == TOMS_PAIR_DENY_FULL);
	/* order: window, then io, then bond slot */
	CHECK(toms_pair_decide(&w, 100, TOMS_IO_NO_INPUT_NO_OUTPUT, 1, false) == TOMS_PAIR_DENY_IO);
	/* the window ran out */
	CHECK(toms_pair_decide(&w, 60000, TOMS_IO_DISPLAY_YESNO, 0, false) == TOMS_PAIR_DENY_CLOSED);
	CHECK(TOMS_PAIR_MAX_BONDS == 1u);

	/* what the machine claims itself is what its own check allows */
	CHECK(toms_pair_io_ok(TOMS_IO_DISPLAY_YESNO));
}

/* ================================================================== diag authorization */
static struct toms_authz_in base(uint8_t cmd)
{
	struct toms_authz_in in = {.cmd = cmd, .link_level = 4, .required_level = 4,
				   .dev_mode = false, .key_present = false, .sig = TOMS_SIG_ABSENT,
				   .bonded = true, .session_open = false, .local_diag_mode = false};

	return in;
}

static void test_authz_link_level(void)
{
	const uint8_t gated[] = {0x00, 0x05, 0x06, 0x07, 0x08};

	for (size_t i = 0; i < sizeof(gated); i++) {
		struct toms_authz_in in = base(gated[i]);

		CHECK(toms_authz_needs_link(gated[i]));
		in.link_level = 4;
		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_ALLOW);
		in.link_level = 3; /* authenticated, but this build asks for 4 */
		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_LINK);
		in.link_level = 2; /* encrypted only: Just Works */
		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_LINK);
		in.link_level = 1;
		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_LINK);
		in.link_level = 0;
		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_LINK);
		/* a build that asks for 3 accepts 3 and 4, never 2 */
		in.required_level = 3;
		in.link_level = 3;
		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_ALLOW);
		in.link_level = 2;
		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_LINK);
		/* dev builds skip the link check */
		in.dev_mode = true;
		in.link_level = 0;
		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_ALLOW);
	}
	/* the bench commands are not link-gated; they need a session instead */
	for (uint8_t c = 1; c <= 4; c++) {
		CHECK(!toms_authz_needs_link(c));
	}
}

static void test_authz_bench(void)
{
	for (uint8_t c = 1; c <= 4; c++) {
		struct toms_authz_in in = base(c);

		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_NO_SESSION);
		in.session_open = true; /* opened by 00 */
		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_ALLOW);
		in.session_open = false;
		in.local_diag_mode = true; /* button held 3 s at the machine */
		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_ALLOW);
		in.local_diag_mode = false;
		in.dev_mode = true;
		CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_ALLOW);
	}
}

static void test_authz_alarm_clear(void)
{
	struct toms_authz_in in = base(TOMS_DIAG_ALARM_CLEAR);

	/* no key yet: nothing to sign with, an authenticated link is enough */
	in.key_present = false;
	CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_ALLOW);

	/* a key exists */
	in.key_present = true;
	in.sig = TOMS_SIG_OK;
	CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_ALLOW);
	in.sig = -EBADMSG; /* wrong signature: refused in every case */
	CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_BAD_SIG);
	in.sig = -EINVAL; /* malformed (partial trailer) */
	CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_BAD_SIG);
	in.dev_mode = true;
	in.sig = -EBADMSG; /* even a dev build refuses a WRONG signature */
	CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_BAD_SIG);
	in.dev_mode = false;

	/* unsigned 05 with a key: interim rule = bonded central AND an open session, logged */
	in.sig = TOMS_SIG_ABSENT;
	in.bonded = true;
	in.session_open = true;
	CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_ALLOW_UNSIGNED_INTERIM);
	in.session_open = false; /* no diagnostic session */
	CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_UNSIGNED);
	in.session_open = true;
	in.bonded = false; /* not bonded */
	CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_UNSIGNED);
	in.bonded = true;
	in.local_diag_mode = true; /* the machine's own diagnostic mode is not the session */
	in.session_open = false;
	CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_UNSIGNED);
	in.local_diag_mode = false;
	in.session_open = true;
	/* the link level still applies to an unsigned 05 */
	in.link_level = 2;
	CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_LINK);
	in.link_level = 4;
	/* DEV_MODE builds keep accepting an unsigned 05 */
	in.dev_mode = true;
	in.bonded = false;
	in.session_open = false;
	CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_ALLOW);
}

/* The signature itself: 05 00 || trailer under suffix 0x0E, made with the machine key. */
static void test_alarm_clear_signature(void)
{
	uint8_t key[TOMS_SIG_KEY_LEN], other[TOMS_SIG_KEY_LEN];
	struct toms_keyring ring;
	uint8_t msg[16] = {0x05, 0x00};
	enum toms_key_used used;
	bool committed;

	make_key(key, 0x10);
	make_key(other, 0x77);
	toms_keyring_init(&ring);
	CHECK(toms_keyring_provision(&ring, key) == 0);

	CHECK(toms_sig_append(key, sizeof(key), TOMS_DIAG_SUFFIX, msg, 2, sizeof(msg)) == 10);
	CHECK(toms_keyring_check(&ring, TOMS_DIAG_SUFFIX, msg, 10, 2, &used, &committed) == TOMS_SIG_OK);
	CHECK(used == TOMS_KEY_CURRENT && !committed);
	/* unsigned: reported as absent so the policy can decide */
	CHECK(toms_keyring_check(&ring, TOMS_DIAG_SUFFIX, msg, 2, 2, &used, &committed) == TOMS_SIG_ABSENT);
	CHECK(used == TOMS_KEY_NONE);
	/* a trailer for another characteristic does not verify (the suffix is signed) */
	uint8_t m2[16] = {0x05, 0x00};

	toms_sig_append(key, sizeof(key), 0x01, m2, 2, sizeof(m2));
	CHECK(toms_keyring_check(&ring, TOMS_DIAG_SUFFIX, m2, 10, 2, &used, &committed) == -EBADMSG);
	/* signed with another key, flipped argument, partial trailer */
	uint8_t m3[16] = {0x05, 0x00};

	toms_sig_append(other, sizeof(other), TOMS_DIAG_SUFFIX, m3, 2, sizeof(m3));
	CHECK(toms_keyring_check(&ring, TOMS_DIAG_SUFFIX, m3, 10, 2, &used, &committed) == -EBADMSG);
	msg[1] = 0x01; /* 05 01 is not what was signed */
	CHECK(toms_keyring_check(&ring, TOMS_DIAG_SUFFIX, msg, 10, 2, &used, &committed) == -EBADMSG);
	msg[1] = 0x00;
	CHECK(toms_keyring_check(&ring, TOMS_DIAG_SUFFIX, msg, 7, 2, &used, &committed) == -EINVAL);
	/* no key: nothing to check against */
	toms_keyring_init(&ring);
	CHECK(toms_keyring_check(&ring, TOMS_DIAG_SUFFIX, msg, 2, 2, &used, &committed) == TOMS_SIG_ABSENT);
	CHECK(toms_keyring_check(&ring, TOMS_DIAG_SUFFIX, msg, 10, 2, &used, &committed) == -EINVAL);
}

/* ================================================================== key replace */
static void make_replace(uint8_t out[TOMS_KEY_REPLACE_LEN], const uint8_t *new_key,
			 const uint8_t *signing_key)
{
	memset(out, 0, TOMS_KEY_REPLACE_LEN);
	out[0] = TOMS_DIAG_KEY_REPLACE;
	memcpy(&out[1], new_key, TOMS_SIG_KEY_LEN);
	toms_sig_compute(signing_key, TOMS_SIG_KEY_LEN, TOMS_DIAG_SUFFIX, out, 1u + TOMS_SIG_KEY_LEN,
			 &out[1 + TOMS_SIG_KEY_LEN]);
}

static void test_key_provision_once(void)
{
	struct toms_keyring r;
	uint8_t a[TOMS_SIG_KEY_LEN], b[TOMS_SIG_KEY_LEN], got[TOMS_SIG_KEY_LEN];

	make_key(a, 1);
	make_key(b, 2);
	toms_keyring_init(&r);
	CHECK(!toms_keyring_get(&r, got));
	CHECK(toms_keyring_provision(&r, a) == 0);
	CHECK(toms_keyring_get(&r, got) && memcmp(got, a, sizeof(a)) == 0);
	CHECK(toms_keyring_provision(&r, b) == -EEXIST); /* write-once: only replace changes it */
	CHECK(toms_keyring_get(&r, got) && memcmp(got, a, sizeof(a)) == 0);
	CHECK(toms_keyring_provision(NULL, b) == -EINVAL && toms_keyring_provision(&r, NULL) == -EINVAL);
}

static void test_key_replace(void)
{
	struct toms_keyring r;
	uint8_t a[TOMS_SIG_KEY_LEN], b[TOMS_SIG_KEY_LEN], c[TOMS_SIG_KEY_LEN], x[TOMS_SIG_KEY_LEN];
	uint8_t msg[TOMS_KEY_REPLACE_LEN], got[TOMS_SIG_KEY_LEN];
	bool grace = true;

	make_key(a, 0x11);
	make_key(b, 0x22);
	make_key(c, 0x33);
	make_key(x, 0x99);

	/* no key yet: a replace is a provisioning job */
	toms_keyring_init(&r);
	make_replace(msg, b, a);
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_NO_KEY && !grace);

	toms_keyring_provision(&r, a);

	/* malformed: length, command byte, NULL */
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg) - 1, &grace) == TOMS_REPLACE_MALFORMED);
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg) + 1, &grace) == TOMS_REPLACE_MALFORMED);
	msg[0] = 0x06;
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_MALFORMED);
	CHECK(toms_keyring_replace(&r, NULL, 0, &grace) == TOMS_REPLACE_MALFORMED);

	/* bad signatures change nothing: wrong key, tampered new key, tampered trailer, unsigned */
	make_replace(msg, b, x);
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_BAD_SIG);
	make_replace(msg, b, a);
	msg[5] ^= 1; /* the new key changed after signing */
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_BAD_SIG);
	make_replace(msg, b, a);
	msg[40] ^= 1; /* last trailer byte */
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_BAD_SIG);
	make_replace(msg, b, a);
	memset(&msg[33], 0, 8); /* all-zero trailer */
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_BAD_SIG);
	CHECK(toms_keyring_get(&r, got) && memcmp(got, a, sizeof(a)) == 0 && !r.has_prev);

	/* a good replace: b is in force, a is kept as the grace key */
	make_replace(msg, b, a);
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_OK && grace);
	CHECK(toms_keyring_get(&r, got) && memcmp(got, b, sizeof(b)) == 0);
	CHECK(r.has_prev && memcmp(r.prev, a, sizeof(a)) == 0);

	/* one-step grace: the old key still verifies a value, until a value with the new key arrives */
	enum toms_key_used used;
	bool committed;
	uint8_t v[16] = {0x05, 0x00};

	toms_sig_append(a, sizeof(a), TOMS_DIAG_SUFFIX, v, 2, sizeof(v));
	CHECK(toms_keyring_check(&r, TOMS_DIAG_SUFFIX, v, 10, 2, &used, &committed) == TOMS_SIG_OK);
	CHECK(used == TOMS_KEY_GRACE && !committed && r.has_prev); /* using the old key does not end it */
	uint8_t w[16] = {0x05, 0x00};

	toms_sig_append(b, sizeof(b), TOMS_DIAG_SUFFIX, w, 2, sizeof(w));
	CHECK(toms_keyring_check(&r, TOMS_DIAG_SUFFIX, w, 10, 2, &used, &committed) == TOMS_SIG_OK);
	CHECK(used == TOMS_KEY_CURRENT && committed && !r.has_prev); /* the new key proved itself: grace over */
	CHECK(toms_keyring_check(&r, TOMS_DIAG_SUFFIX, v, 10, 2, &used, &committed) == -EBADMSG); /* a is gone */
	CHECK(used == TOMS_KEY_NONE);

	/* "a failed replace does not brick the link": the machine took the replace, the phone never saw the
	 * reply. The phone still has a. It retries with a: signed with the grace key, it must still work. */
	toms_keyring_init(&r);
	toms_keyring_provision(&r, a);
	make_replace(msg, b, a);
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_OK); /* reply lost */
	make_replace(msg, c, a); /* the phone, still on a, tries again (even with a different key) */
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_OK && grace);
	CHECK(toms_keyring_get(&r, got) && memcmp(got, c, sizeof(c)) == 0);
	CHECK(r.has_prev && memcmp(r.prev, a, sizeof(a)) == 0); /* a is still the grace key */
	/* and the phone can still reach the machine with a (signed 05) */
	toms_sig_append(a, sizeof(a), TOMS_DIAG_SUFFIX, v, 2, sizeof(v));
	CHECK(toms_keyring_check(&r, TOMS_DIAG_SUFFIX, v, 10, 2, &used, &committed) == TOMS_SIG_OK);
	/* a replace signed with the key that was replaced in the first try (b) no longer works: it is gone */
	make_replace(msg, x, b);
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_BAD_SIG);

	/* replace with the grace key and then a normal one chains correctly */
	make_replace(msg, a, c); /* c is in force; sign with it */
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_OK);
	CHECK(r.has_prev && memcmp(r.prev, c, sizeof(c)) == 0 && memcmp(r.cur, a, sizeof(a)) == 0);

	/* grace key replaced by the next replace: only one previous key is ever remembered */
	toms_keyring_init(&r);
	toms_keyring_provision(&r, a);
	make_replace(msg, b, a);
	toms_keyring_replace(&r, msg, sizeof(msg), &grace);
	make_replace(msg, c, b);
	CHECK(toms_keyring_replace(&r, msg, sizeof(msg), &grace) == TOMS_REPLACE_OK);
	CHECK(memcmp(r.prev, b, sizeof(b)) == 0 && memcmp(r.cur, c, sizeof(c)) == 0);
	toms_sig_append(a, sizeof(a), TOMS_DIAG_SUFFIX, v, 2, sizeof(v));
	CHECK(toms_keyring_check(&r, TOMS_DIAG_SUFFIX, v, 10, 2, &used, &committed) == -EBADMSG); /* a is gone */

	/* a replace never exposes a key through the reply: the status codes carry no key bytes */
	CHECK(TOMS_KEY_REPLACE_LEN == 41u);
}

static void test_keyring_blob(void)
{
	struct toms_keyring r, back;
	uint8_t a[TOMS_SIG_KEY_LEN], b[TOMS_SIG_KEY_LEN], blob[TOMS_KEYRING_BLOB_LEN];

	make_key(a, 5);
	make_key(b, 6);
	toms_keyring_init(&r);
	CHECK(toms_keyring_encode(&r, blob) == (int)TOMS_KEYRING_BLOB_LEN);
	CHECK(toms_keyring_decode(&back, blob, sizeof(blob)) == 0 && !back.has_cur && !back.has_prev);

	toms_keyring_provision(&r, a);
	toms_keyring_encode(&r, blob);
	CHECK(toms_keyring_decode(&back, blob, sizeof(blob)) == 0 && back.has_cur && !back.has_prev);
	CHECK(memcmp(back.cur, a, sizeof(a)) == 0);

	uint8_t msg[TOMS_KEY_REPLACE_LEN];
	bool grace;

	make_replace(msg, b, a);
	toms_keyring_replace(&r, msg, sizeof(msg), &grace);
	toms_keyring_encode(&r, blob);
	CHECK(toms_keyring_decode(&back, blob, sizeof(blob)) == 0 && back.has_cur && back.has_prev);
	CHECK(memcmp(back.cur, b, sizeof(b)) == 0 && memcmp(back.prev, a, sizeof(a)) == 0);

	/* a power cycle in the middle of a replace: either the old ring or the new one is on flash, both
	 * decode, and the phone can reach the machine with a key from either */
	struct toms_keyring before;

	toms_keyring_init(&before);
	toms_keyring_provision(&before, a);
	enum toms_key_used used;
	bool committed;
	uint8_t v[16] = {0x05, 0x00};

	toms_sig_append(a, sizeof(a), TOMS_DIAG_SUFFIX, v, 2, sizeof(v));
	CHECK(toms_keyring_check(&before, TOMS_DIAG_SUFFIX, v, 10, 2, &used, &committed) == TOMS_SIG_OK);
	CHECK(toms_keyring_check(&back, TOMS_DIAG_SUFFIX, v, 10, 2, &used, &committed) == TOMS_SIG_OK);

	/* damage: wrong length, flipped bit, wrong version, unknown flag, grace key without a current one */
	toms_keyring_encode(&r, blob);
	CHECK(toms_keyring_decode(&back, blob, sizeof(blob) - 1) == -EINVAL);
	CHECK(!back.has_cur); /* a failed decode leaves no key behind */
	blob[10] ^= 1;
	CHECK(toms_keyring_decode(&back, blob, sizeof(blob)) == -EINVAL && !back.has_cur);
	toms_keyring_encode(&r, blob);
	blob[0] &= 0x7F; /* version bit */
	CHECK(toms_keyring_decode(&back, blob, sizeof(blob)) == -EINVAL);
	toms_keyring_encode(&r, blob);
	blob[0] |= 0x40;
	CHECK(toms_keyring_decode(&back, blob, sizeof(blob)) == -EINVAL);
	CHECK(toms_keyring_decode(NULL, blob, sizeof(blob)) == -EINVAL);
	CHECK(toms_keyring_decode(&back, NULL, 0) == -EINVAL);
	struct toms_keyring odd;

	toms_keyring_init(&odd);
	odd.has_prev = true;
	toms_keyring_encode(&odd, blob);
	CHECK(toms_keyring_decode(&back, blob, sizeof(blob)) == -EINVAL);
}

/* ================================================================== the diagnostic app */
/* Every command the diagnostic app (toms_android/ota, branch v2/return-machine-fw) sends on the diag
 * characteristic today, from OtaViewModel.kt and BleTransport.kt:
 *   00 + "TOMS!"   writeDiagUnlock()       on connect          (BleTransport.kt:260, OtaViewModel.kt:323)
 *   01 <tone>      writeDiag(0x01, tone)   buzzer test         (OtaViewModel.kt:462)
 *   02             writeDiag(0x02)         next screen         (OtaViewModel.kt:470)
 *   03             writeDiag(0x03)         backlight           (OtaViewModel.kt:478)
 *   04             writeDiag(0x04)         PN532 check         (OtaViewModel.kt:490)
 *   05 00          writeClearAlarm()       silence the alarm   (BleTransport.kt:275, OtaViewModel.kt:439)
 * It pairs with createBond() (numeric comparison, BleTransport.kt:169-172). It never sends 06, 07 or 08.
 *
 * What it sends is run through the rules below, in the order it sends them, for the case the diagnostic
 * app is in after "hold the button at power-up, then pair": an authenticated, bonded link. */
struct app_cmd {
	const char *what;
	uint8_t cmd;
	size_t consumed;
	bool needs_unlock_first;
};

static void test_diagnostic_app(void)
{
	static const struct app_cmd cmds[] = {
		{"unlock 00 TOMS!", 0x00, 1, false}, {"buzzer 01", 0x01, 2, true},
		{"screen 02", 0x02, 1, true},        {"backlight 03", 0x03, 1, true},
		{"nfc 04", 0x04, 1, true},           {"silence 05 00", 0x05, 2, true},
	};

	for (int keyed = 0; keyed < 2; keyed++) {
		for (int level = 3; level <= 4; level++) {
			/* a build whose bar equals the level the stack can give the app's pairing */
			bool session = false;

			for (size_t i = 0; i < sizeof(cmds) / sizeof(cmds[0]); i++) {
				struct toms_authz_in in = base(cmds[i].cmd);

				in.link_level = (uint8_t)level;
				in.required_level = (uint8_t)level;
				in.bonded = true;
				in.key_present = keyed != 0;
				in.session_open = session;
				/* the app never signs 05 (the Android PR does that later) */
				in.sig = TOMS_SIG_ABSENT;
				enum toms_authz_result res = toms_authz_diag(&in);

				if (cmds[i].cmd == 0x05 && keyed) {
					/* interim rule: bonded + session, unsigned, logged */
					CHECK(res == TOMS_AUTHZ_ALLOW_UNSIGNED_INTERIM);
				} else {
					CHECK(res == TOMS_AUTHZ_ALLOW);
				}
				if (cmds[i].cmd == 0x00 && res == TOMS_AUTHZ_ALLOW) {
					session = true;
				}
			}
			/* and none of them work without the unlock first (except 00 itself) */
			for (size_t i = 1; i <= 4; i++) {
				struct toms_authz_in in = base(cmds[i].cmd);

				in.link_level = (uint8_t)level;
				in.required_level = (uint8_t)level;
				in.session_open = false;
				if (cmds[i].cmd <= 4) {
					CHECK(toms_authz_diag(&in) == TOMS_AUTHZ_DENY_NO_SESSION);
				}
			}
		}
	}

	/* the app's pairing before the unlock: a 'Just Works' pairing is level 2 and does not pass */
	struct toms_authz_in jw = base(0x00);

	jw.link_level = 2;
	CHECK(toms_authz_diag(&jw) == TOMS_AUTHZ_DENY_LINK);
	/* the app's own pairing capability (what Android announces for numeric comparison) is accepted */
	struct toms_pair_window w;

	toms_pair_window_init(&w);
	toms_pair_window_open(&w, 0, 60000);
	CHECK(toms_pair_decide(&w, 10, TOMS_IO_DISPLAY_YESNO, 0, false) == TOMS_PAIR_ALLOW);
	/* Android with a screen and no keyboard asks as DisplayYesNo; with a keyboard as KeyboardDisplay */
	CHECK(toms_pair_decide(&w, 10, TOMS_IO_KEYBOARD_DISPLAY, 0, false) == TOMS_PAIR_ALLOW);
}

/* ================================================================== forgetting the bond (hold 5 s) */
/* Samples the button every TOMS_BONDCLR_POLL_MS from t0. `down_until`: pressed while t < down_until.
 * Returns the time of the FIRE event, or 0 when none came within `limit_ms`. */
static uint32_t run_hold(uint32_t t0, bool held_at_powerup, uint32_t down_until, uint32_t limit_ms,
			 struct toms_bondclr *s)
{
	toms_bondclr_init(s, t0, held_at_powerup);
	for (uint32_t t = t0 + TOMS_BONDCLR_POLL_MS; t - t0 <= limit_ms; t += TOMS_BONDCLR_POLL_MS) {
		if (toms_bondclr_tick(s, t, t < down_until) == TOMS_BONDCLR_FIRE) {
			return t;
		}
	}
	return 0;
}

static void test_bond_clear(void)
{
	struct toms_bondclr s;
	uint32_t fired;

	/* constants the document states */
	CHECK(TOMS_BONDCLR_HOLD_MS == 5000u && TOMS_BONDCLR_SHOW_MS == 2000u);

	/* held from power-up through 5 s: fires once, at the 5 s sample, not before */
	fired = run_hold(1000, true, 0xFFFFFFFFu, 30000, &s);
	CHECK(fired == 1000u + 5000u);
	CHECK(s.state == TOMS_BONDCLR_DONE && !toms_bondclr_active(&s));
	CHECK(toms_bondclr_tick(&s, fired + 50, true) == TOMS_BONDCLR_NONE); /* only once */
	CHECK(toms_bondclr_tick(&s, fired + 100, true) == TOMS_BONDCLR_NONE);

	/* 4.95 s is not enough, whatever follows it */
	toms_bondclr_init(&s, 0, true);
	for (uint32_t t = 50; t <= 4950; t += 50) {
		CHECK(toms_bondclr_tick(&s, t, true) == TOMS_BONDCLR_NONE);
	}
	CHECK(toms_bondclr_active(&s));
	CHECK(toms_bondclr_tick(&s, 5000, true) == TOMS_BONDCLR_FIRE);

	/* not held at power-up: a button press later never starts a count */
	toms_bondclr_init(&s, 0, false);
	CHECK(s.state == TOMS_BONDCLR_IDLE && !toms_bondclr_active(&s));
	for (uint32_t t = 50; t <= 20000; t += 50) {
		CHECK(toms_bondclr_tick(&s, t, true) == TOMS_BONDCLR_NONE);
	}
	CHECK(s.state == TOMS_BONDCLR_IDLE);

	/* released before 5 s: gives up for good, even if pressed again straight away */
	fired = run_hold(0, true, 3000, 30000, &s);
	CHECK(fired == 0 && s.state == TOMS_BONDCLR_GAVE_UP);
	CHECK(toms_bondclr_tick(&s, 3500, true) == TOMS_BONDCLR_NONE);
	CHECK(toms_bondclr_tick(&s, 9000, true) == TOMS_BONDCLR_NONE);

	/* released at 4.9 s: still short */
	fired = run_hold(0, true, 4900, 30000, &s);
	CHECK(fired == 0 && s.state == TOMS_BONDCLR_GAVE_UP);

	/* contact bounce (a release shorter than TOMS_BONDCLR_RELEASE_MS) does not reset the count */
	toms_bondclr_init(&s, 0, true);
	enum toms_bondclr_event ev = TOMS_BONDCLR_NONE;

	for (uint32_t t = 50; t <= 5000; t += 50) {
		bool pressed = !(t == 2000 || t == 2050); /* two samples = 100 ms up */
		ev = toms_bondclr_tick(&s, t, pressed);
		if (t < 5000) {
			CHECK(ev == TOMS_BONDCLR_NONE);
		}
	}
	CHECK(ev == TOMS_BONDCLR_FIRE);
	/* two separate bounces, far apart: each is measured on its own, the first is not remembered */
	toms_bondclr_init(&s, 0, true);
	ev = TOMS_BONDCLR_NONE;
	for (uint32_t t = 50; t <= 5000; t += 50) {
		bool up = (t == 1000 || t == 1050) || (t == 3000 || t == 3050) || (t == 4800 || t == 4850);
		ev = toms_bondclr_tick(&s, t, !up);
		if (t < 5000) {
			CHECK(ev == TOMS_BONDCLR_NONE);
		}
	}
	CHECK(ev == TOMS_BONDCLR_FIRE);
	/* ... but a release of TOMS_BONDCLR_RELEASE_MS or longer ends it */
	toms_bondclr_init(&s, 0, true);
	for (uint32_t t = 50; t <= 4000; t += 50) {
		bool pressed = !(t >= 2000 && t < 2000 + TOMS_BONDCLR_RELEASE_MS + 50);
		toms_bondclr_tick(&s, t, pressed);
	}
	CHECK(s.state == TOMS_BONDCLR_GAVE_UP);
	CHECK(toms_bondclr_tick(&s, 5000, true) == TOMS_BONDCLR_NONE);

	/* a gap in the samples (the firmware was busy) is treated as a possible release: give up */
	toms_bondclr_init(&s, 0, true);
	CHECK(toms_bondclr_tick(&s, 50, true) == TOMS_BONDCLR_NONE);
	CHECK(toms_bondclr_tick(&s, 50 + TOMS_BONDCLR_MAX_GAP_MS + 1, true) == TOMS_BONDCLR_NONE);
	CHECK(s.state == TOMS_BONDCLR_GAVE_UP);
	CHECK(toms_bondclr_tick(&s, 5600, true) == TOMS_BONDCLR_NONE); /* even though it is past 5 s */
	/* a gap exactly at the limit is still fine */
	toms_bondclr_init(&s, 0, true);
	CHECK(toms_bondclr_tick(&s, TOMS_BONDCLR_MAX_GAP_MS, true) == TOMS_BONDCLR_NONE);
	CHECK(toms_bondclr_active(&s));
	/* a sample that goes back in time cannot be trusted either */
	toms_bondclr_init(&s, 1000, true);
	toms_bondclr_tick(&s, 1100, true);
	CHECK(toms_bondclr_tick(&s, 1050, true) == TOMS_BONDCLR_NONE && s.state == TOMS_BONDCLR_GAVE_UP);

	/* the single sample that crosses 5 s fires, even if samples are 400 ms apart */
	toms_bondclr_init(&s, 0, true);
	for (uint32_t t = 400; t < 5000; t += 400) {
		CHECK(toms_bondclr_tick(&s, t, true) == TOMS_BONDCLR_NONE);
	}
	CHECK(toms_bondclr_tick(&s, 5200, true) == TOMS_BONDCLR_FIRE);

	/* the 32-bit millisecond clock wraps; the hold across the wrap still counts 5 s, not more or less */
	fired = run_hold(0xFFFFFFFFu - 2000u, true, 0xFFFFFFFFu, 30000, &s);
	CHECK(fired == (uint32_t)(0xFFFFFFFFu - 2000u + 5000u));
	/* the same, starting 16 ms before the wrap, in 50 ms steps: nothing before 5 s, FIRE at 5 s */
	toms_bondclr_init(&s, 0xFFFFFFF0u, true);
	for (uint32_t d = 50; d < 5000; d += 50) {
		CHECK(toms_bondclr_tick(&s, 0xFFFFFFF0u + d, true) == TOMS_BONDCLR_NONE);
	}
	CHECK(toms_bondclr_tick(&s, 0xFFFFFFF0u + 5000u, true) == TOMS_BONDCLR_FIRE);
	CHECK(0xFFFFFFF0u + 5000u < 5000u); /* the sample really is on the other side of the wrap */

	/* a released button at power-up (a 'held' reading that was really noise) never fires */
	toms_bondclr_init(&s, 0, true);
	for (uint32_t t = 50; t <= 10000; t += 50) {
		toms_bondclr_tick(&s, t, false);
	}
	CHECK(s.state == TOMS_BONDCLR_GAVE_UP);
}

/* The order of things after FIRE, as the firmware does them (main.c): the bond goes, the key does not,
 * and the window opens only after BOND CLEARED has been on the screen. Modelled here with the real
 * pairing rules and the real key ring, so the two cannot drift apart from what the doc says. */
static void test_bond_clear_effects(void)
{
	struct toms_pair_window w;
	struct toms_keyring ring;
	uint8_t key[TOMS_SIG_KEY_LEN], got[TOMS_SIG_KEY_LEN];
	uint8_t bonds = 1;     /* the phone's bond */
	bool peer_bonded = true;

	make_key(key, 0x31);
	toms_keyring_init(&ring);
	toms_keyring_provision(&ring, key);
	toms_pair_window_init(&w);

	/* before: a bond exists, so a second phone is refused even with a window; the window is closed */
	CHECK(toms_pair_decide(&w, 100, TOMS_IO_DISPLAY_YESNO, bonds, false) == TOMS_PAIR_DENY_CLOSED);
	toms_pair_window_open(&w, 100, 60000);
	CHECK(toms_pair_decide(&w, 200, TOMS_IO_DISPLAY_YESNO, bonds, false) == TOMS_PAIR_DENY_FULL);
	toms_pair_window_close(&w);

	/* FIRE: the bond is cleared; the key ring is not touched */
	bonds = 0;
	peer_bonded = false;
	CHECK(toms_keyring_get(&ring, got) && memcmp(got, key, sizeof(key)) == 0);
	CHECK(!ring.has_prev);
	/* BOND CLEARED shows for TOMS_BONDCLR_SHOW_MS with the window still closed: nobody can pair yet */
	uint32_t t_clear = 5000;

	CHECK(toms_pair_decide(&w, t_clear + 10, TOMS_IO_DISPLAY_YESNO, bonds, peer_bonded) == TOMS_PAIR_DENY_CLOSED);
	CHECK(toms_pair_decide(&w, t_clear + TOMS_BONDCLR_SHOW_MS - 1, TOMS_IO_DISPLAY_YESNO, bonds, false) ==
	      TOMS_PAIR_DENY_CLOSED);
	/* then the window opens, for the usual 60 s, counted from then */
	toms_pair_window_open(&w, t_clear + TOMS_BONDCLR_SHOW_MS, TOMS_PAIR_WINDOW_DEFAULT_MS);
	CHECK(toms_pair_window_left_ms(&w, t_clear + TOMS_BONDCLR_SHOW_MS) == 60000u);
	/* any authenticated-capable central may now take the slot, the old one included */
	CHECK(toms_pair_decide(&w, t_clear + 3000, TOMS_IO_DISPLAY_YESNO, bonds, false) == TOMS_PAIR_ALLOW);
	CHECK(toms_pair_decide(&w, t_clear + 3000, TOMS_IO_NO_INPUT_NO_OUTPUT, bonds, false) == TOMS_PAIR_DENY_IO);
	/* once one bond is made the window closes and the slot is taken again */
	bonds = 1;
	toms_pair_window_close(&w);
	CHECK(toms_pair_decide(&w, t_clear + 4000, TOMS_IO_DISPLAY_YESNO, bonds, false) == TOMS_PAIR_DENY_CLOSED);

	/* the key still verifies what the old phone signed: forgetting the bond forgot nothing else */
	uint8_t v[16] = {0x05, 0x00};
	enum toms_key_used used;
	bool committed;

	toms_sig_append(key, sizeof(key), TOMS_DIAG_SUFFIX, v, 2, sizeof(v));
	CHECK(toms_keyring_check(&ring, TOMS_DIAG_SUFFIX, v, 10, 2, &used, &committed) == TOMS_SIG_OK);
	CHECK(used == TOMS_KEY_CURRENT);
	/* and the blob that goes to flash is the same as before the clear (nothing rewrote it) */
	uint8_t blob_a[TOMS_KEYRING_BLOB_LEN], blob_b[TOMS_KEYRING_BLOB_LEN];

	toms_keyring_encode(&ring, blob_a);
	toms_keyring_encode(&ring, blob_b);
	CHECK(memcmp(blob_a, blob_b, sizeof(blob_a)) == 0);
}

int main(void)
{
	test_pair_window();
	test_pair_decide();
	test_authz_link_level();
	test_authz_bench();
	test_authz_alarm_clear();
	test_alarm_clear_signature();
	test_key_provision_once();
	test_key_replace();
	test_keyring_blob();
	test_diagnostic_app();
	test_bond_clear();
	test_bond_clear_effects();
	printf("auth: %d checks, %d failures\n", checks, failures);
	return failures ? 1 : 0;
}
