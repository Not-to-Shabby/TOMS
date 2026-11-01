#include <toms/sha256.h>

#include <string.h>

static const uint32_t K[64] = {
	0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u,
	0xab1c5ed5u, 0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu,
	0x9bdc06a7u, 0xc19bf174u, 0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu,
	0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau, 0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
	0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu,
	0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u, 0xa2bfe8a1u, 0xa81a664bu,
	0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u, 0x19a4c116u,
	0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
	0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u,
	0xc67178f2u,
};

static uint32_t rotr(uint32_t x, unsigned n)
{
	return (x >> n) | (x << (32u - n));
}

static void block(struct toms_sha256 *c, const uint8_t *p)
{
	uint32_t w[64];
	uint32_t a, b, cc, d, e, f, g, h;

	for (unsigned i = 0; i < 16; i++) {
		w[i] = ((uint32_t)p[4 * i] << 24) | ((uint32_t)p[4 * i + 1] << 16) |
		       ((uint32_t)p[4 * i + 2] << 8) | (uint32_t)p[4 * i + 3];
	}
	for (unsigned i = 16; i < 64; i++) {
		uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
		uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);

		w[i] = w[i - 16] + s0 + w[i - 7] + s1;
	}

	a = c->h[0];
	b = c->h[1];
	cc = c->h[2];
	d = c->h[3];
	e = c->h[4];
	f = c->h[5];
	g = c->h[6];
	h = c->h[7];
	for (unsigned i = 0; i < 64; i++) {
		uint32_t t1 = h + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) +
			      K[i] + w[i];
		uint32_t t2 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) +
			      ((a & b) ^ (a & cc) ^ (b & cc));

		h = g;
		g = f;
		f = e;
		e = d + t1;
		d = cc;
		cc = b;
		b = a;
		a = t1 + t2;
	}
	c->h[0] += a;
	c->h[1] += b;
	c->h[2] += cc;
	c->h[3] += d;
	c->h[4] += e;
	c->h[5] += f;
	c->h[6] += g;
	c->h[7] += h;
}

void toms_sha256_init(struct toms_sha256 *c)
{
	static const uint32_t iv[8] = {0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
				       0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u};

	memcpy(c->h, iv, sizeof(iv));
	memset(c->buf, 0, sizeof(c->buf));
	c->total = 0;
	c->fill = 0;
}

void toms_sha256_update(struct toms_sha256 *c, const uint8_t *data, size_t len)
{
	c->total += len;
	while (len > 0) {
		size_t room = 64u - c->fill;
		size_t n = len < room ? len : room;

		memcpy(c->buf + c->fill, data, n);
		c->fill = (uint8_t)(c->fill + n);
		data += n;
		len -= n;
		if (c->fill == 64) {
			block(c, c->buf);
			c->fill = 0;
		}
	}
}

void toms_sha256_final(struct toms_sha256 *c, uint8_t out[TOMS_SHA256_LEN])
{
	uint64_t bits = c->total * 8u;
	uint8_t pad = 0x80;
	uint8_t zero = 0;

	toms_sha256_update(c, &pad, 1);
	while (c->fill != 56) {
		toms_sha256_update(c, &zero, 1);
	}
	uint8_t len_be[8];

	for (unsigned i = 0; i < 8; i++) {
		len_be[i] = (uint8_t)(bits >> (56 - 8 * i));
	}
	toms_sha256_update(c, len_be, 8);
	for (unsigned i = 0; i < 8; i++) {
		out[4 * i] = (uint8_t)(c->h[i] >> 24);
		out[4 * i + 1] = (uint8_t)(c->h[i] >> 16);
		out[4 * i + 2] = (uint8_t)(c->h[i] >> 8);
		out[4 * i + 3] = (uint8_t)c->h[i];
	}
}
