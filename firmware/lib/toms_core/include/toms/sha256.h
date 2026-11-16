/* SHA-256 (FIPS 180-4), portable and dependency free. Used to check an uploaded image against
 * the digest the phone announced. It proves the bytes arrived intact; it does NOT prove who
 * made them (that is MCUboot's signature check). */
#ifndef TOMS_SHA256_H
#define TOMS_SHA256_H

#include <stddef.h>
#include <stdint.h>

#define TOMS_SHA256_LEN 32

struct toms_sha256 {
	uint32_t h[8];
	uint8_t buf[64];
	uint64_t total; /* bytes fed so far */
	uint8_t fill;   /* bytes waiting in buf */
};

void toms_sha256_init(struct toms_sha256 *c);
void toms_sha256_update(struct toms_sha256 *c, const uint8_t *data, size_t len);
void toms_sha256_final(struct toms_sha256 *c, uint8_t out[TOMS_SHA256_LEN]);

#endif /* TOMS_SHA256_H */
