/* Message signing for the Return Machine -> phone values: HMAC-SHA256 (RFC 2104) truncated to
 * an 8-byte trailer. The key is per machine and comes from storage; it is never part of the
 * source. Pure code: runs on the host under test. */
#ifndef TOMS_SIG_H
#define TOMS_SIG_H

#include <stddef.h>
#include <stdint.h>

#include <toms/sha256.h>

#define TOMS_SIG_LEN 8u
#define TOMS_SIG_KEY_LEN 32u

struct toms_hmac {
	struct toms_sha256 inner;
	struct toms_sha256 outer;
};

void toms_hmac_init(struct toms_hmac *h, const uint8_t *key, size_t key_len);
void toms_hmac_update(struct toms_hmac *h, const uint8_t *data, size_t len);
void toms_hmac_final(struct toms_hmac *h, uint8_t out[TOMS_SHA256_LEN]);

/* One-shot HMAC-SHA256. */
void toms_hmac_sha256(const uint8_t *key, size_t key_len, const uint8_t *msg, size_t msg_len,
		      uint8_t out[TOMS_SHA256_LEN]);

/* The trailer covers the characteristic suffix byte (so a value signed for one characteristic
 * is not valid on another) followed by the payload. */
void toms_sig_compute(const uint8_t *key, size_t key_len, uint8_t char_suffix,
		      const uint8_t *payload, size_t len, uint8_t out[TOMS_SIG_LEN]);

/* Appends the trailer after buf[0..len). Returns len + 8, or -ENOSPC / -EINVAL. */
int toms_sig_append(const uint8_t *key, size_t key_len, uint8_t char_suffix, uint8_t *buf,
		    size_t len, size_t cap);

/* Result of looking for a trailer behind a payload the codec already parsed. */
#define TOMS_SIG_OK 0       /* trailer present and valid */
#define TOMS_SIG_ABSENT 1   /* no trailer: len == consumed */
/* negative: -EBADMSG trailer present but wrong, -EINVAL bad argument or unexpected length */

/* `consumed` is what the decoder reported for data[0..len). */
int toms_sig_check(const uint8_t *key, size_t key_len, uint8_t char_suffix, const uint8_t *data,
		   size_t len, size_t consumed);

#endif /* TOMS_SIG_H */
