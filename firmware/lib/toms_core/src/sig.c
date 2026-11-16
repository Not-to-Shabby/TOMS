#include <toms/sig.h>

#include <errno.h>
#include <string.h>

#define BLOCK 64u

void toms_hmac_init(struct toms_hmac *h, const uint8_t *key, size_t key_len)
{
	uint8_t k[BLOCK];
	uint8_t pad[BLOCK];

	memset(k, 0, sizeof(k));
	if (key_len > BLOCK) {
		struct toms_sha256 c;

		toms_sha256_init(&c);
		toms_sha256_update(&c, key, key_len);
		toms_sha256_final(&c, k);
	} else if (key_len > 0) {
		memcpy(k, key, key_len);
	}

	for (size_t i = 0; i < BLOCK; i++) {
		pad[i] = (uint8_t)(k[i] ^ 0x36u);
	}
	toms_sha256_init(&h->inner);
	toms_sha256_update(&h->inner, pad, BLOCK);

	for (size_t i = 0; i < BLOCK; i++) {
		pad[i] = (uint8_t)(k[i] ^ 0x5Cu);
	}
	toms_sha256_init(&h->outer);
	toms_sha256_update(&h->outer, pad, BLOCK);

	memset(k, 0, sizeof(k));
	memset(pad, 0, sizeof(pad));
}

void toms_hmac_update(struct toms_hmac *h, const uint8_t *data, size_t len)
{
	toms_sha256_update(&h->inner, data, len);
}

void toms_hmac_final(struct toms_hmac *h, uint8_t out[TOMS_SHA256_LEN])
{
	uint8_t inner[TOMS_SHA256_LEN];

	toms_sha256_final(&h->inner, inner);
	toms_sha256_update(&h->outer, inner, sizeof(inner));
	toms_sha256_final(&h->outer, out);
	memset(inner, 0, sizeof(inner));
}

void toms_hmac_sha256(const uint8_t *key, size_t key_len, const uint8_t *msg, size_t msg_len,
		      uint8_t out[TOMS_SHA256_LEN])
{
	struct toms_hmac h;

	toms_hmac_init(&h, key, key_len);
	toms_hmac_update(&h, msg, msg_len);
	toms_hmac_final(&h, out);
}

void toms_sig_compute(const uint8_t *key, size_t key_len, uint8_t char_suffix,
		      const uint8_t *payload, size_t len, uint8_t out[TOMS_SIG_LEN])
{
	struct toms_hmac h;
	uint8_t full[TOMS_SHA256_LEN];

	toms_hmac_init(&h, key, key_len);
	toms_hmac_update(&h, &char_suffix, 1);
	toms_hmac_update(&h, payload, len);
	toms_hmac_final(&h, full);
	memcpy(out, full, TOMS_SIG_LEN);
	memset(full, 0, sizeof(full));
}

int toms_sig_append(const uint8_t *key, size_t key_len, uint8_t char_suffix, uint8_t *buf,
		    size_t len, size_t cap)
{
	if (key == NULL || key_len == 0 || buf == NULL) {
		return -EINVAL;
	}
	if (cap < len + TOMS_SIG_LEN) {
		return -ENOSPC;
	}
	toms_sig_compute(key, key_len, char_suffix, buf, len, &buf[len]);
	return (int)(len + TOMS_SIG_LEN);
}

int toms_sig_check(const uint8_t *key, size_t key_len, uint8_t char_suffix, const uint8_t *data,
		   size_t len, size_t consumed)
{
	if (data == NULL || consumed > len) {
		return -EINVAL;
	}
	if (len == consumed) {
		return TOMS_SIG_ABSENT;
	}
	if (len != consumed + TOMS_SIG_LEN || key == NULL || key_len == 0) {
		return -EINVAL;
	}

	uint8_t want[TOMS_SIG_LEN];
	uint8_t diff = 0;

	toms_sig_compute(key, key_len, char_suffix, data, consumed, want);
	for (size_t i = 0; i < TOMS_SIG_LEN; i++) {
		diff = (uint8_t)(diff | (want[i] ^ data[consumed + i])); /* constant time */
	}
	return diff == 0 ? TOMS_SIG_OK : -EBADMSG;
}
