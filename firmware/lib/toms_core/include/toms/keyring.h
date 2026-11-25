/* The Return Machine's signing keys: the one in force, and, for one step after a replace, the one
 * before it. Pure code (no storage, no clock): the firmware loads and saves the blob. Runs on the
 * host under test. PROTOCOL.md section 8 has the byte layouts.
 *
 * Replace (diag 0x08): 08 || new_key[32] || trailer[8], where the trailer is the usual 8-byte
 * signature (toms/sig.h) over suffix 0x0E and the first 33 bytes, made with the key in force.
 * A replace that checks out makes the new key the one in force and keeps the old one as the grace
 * key. Grace exists so a replace the phone never confirmed cannot lock it out:
 *   - a value signed with the grace key still verifies, and so does a retried replace signed with it;
 *   - the first value that verifies with the NEW key ends the grace (the old key is forgotten);
 *   - another replace overwrites the grace key with the one it replaced.
 * The machine always signs what it sends with the key in force. */
#ifndef TOMS_KEYRING_H
#define TOMS_KEYRING_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <toms/sig.h>

#define TOMS_KEY_REPLACE_LEN (1u + TOMS_SIG_KEY_LEN + TOMS_SIG_LEN) /* 41 */

/* Status byte of the reply [0x88, status, grace, 0] */
#define TOMS_REPLACE_OK 0u         /* the new key is in force */
#define TOMS_REPLACE_NO_KEY 1u     /* no key yet: provision one first (diag 0x06) */
#define TOMS_REPLACE_BAD_SIG 2u    /* not signed under the key in force or the grace key */
#define TOMS_REPLACE_MALFORMED 3u  /* wrong length or wrong command byte */
#define TOMS_REPLACE_STORAGE 4u    /* could not be saved; nothing changed */
#define TOMS_REPLACE_REFUSED 0xFFu /* the link was not allowed to ask (set by the caller) */

/* Blob stored in flash: [flags][current 32][previous 32][crc16 LE], 67 bytes. */
#define TOMS_KEYRING_BLOB_LEN (1u + 2u * TOMS_SIG_KEY_LEN + 2u)

struct toms_keyring {
	uint8_t cur[TOMS_SIG_KEY_LEN];
	uint8_t prev[TOMS_SIG_KEY_LEN];
	bool has_cur;
	bool has_prev;
};

/* What a check found. */
enum toms_key_used {
	TOMS_KEY_NONE = 0,   /* no key matched (or no trailer to check) */
	TOMS_KEY_CURRENT,    /* the key in force */
	TOMS_KEY_GRACE,      /* the previous key, during grace */
};

void toms_keyring_init(struct toms_keyring *k);

/* First key. -EEXIST when one is already in force: the key can then only be replaced. */
int toms_keyring_provision(struct toms_keyring *k, const uint8_t key[TOMS_SIG_KEY_LEN]);

bool toms_keyring_get(const struct toms_keyring *k, uint8_t key[TOMS_SIG_KEY_LEN]);

/* Like toms_sig_check(), against the key in force and then the grace key. Returns the toms_sig_check
 * result (TOMS_SIG_OK, TOMS_SIG_ABSENT, -EBADMSG, -EINVAL); *used says which key matched. A match on
 * the key in force while a grace key is held ends the grace and sets *committed (the caller saves). */
int toms_keyring_check(struct toms_keyring *k, uint8_t suffix, const uint8_t *data, size_t len,
		       size_t consumed, enum toms_key_used *used, bool *committed);

/* Handles diag 0x08. `data` is the whole value written (command byte included). On
 * TOMS_REPLACE_OK the ring has changed and the caller must save it; on any other status it has not.
 * *grace is set to whether a grace key is held afterwards. */
uint8_t toms_keyring_replace(struct toms_keyring *k, const uint8_t *data, size_t len, bool *grace);

/* Flash blob. Decode returns 0, or -EINVAL on a wrong length, version or crc (the ring is cleared). */
int toms_keyring_encode(const struct toms_keyring *k, uint8_t out[TOMS_KEYRING_BLOB_LEN]);
int toms_keyring_decode(struct toms_keyring *k, const uint8_t *blob, size_t len);

#endif /* TOMS_KEYRING_H */
