/* Host tests for toms/sha256.h and toms/ota.h (built by run_tests.sh and check_all.sh). */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <toms/ota.h>
#include <toms/sha256.h>

static int failures, checks;

#define CHECK(cond)                                                                              \
	do {                                                                                     \
		checks++;                                                                        \
		if (!(cond)) {                                                                   \
			failures++;                                                              \
			printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);                   \
		}                                                                                \
	} while (0)

static void hex(const uint8_t *d, char out[65])
{
	for (int i = 0; i < 32; i++) {
		sprintf(out + 2 * i, "%02x", d[i]);
	}
}

static void sha_of(size_t n, size_t step, char out[65])
{
	static uint8_t data[2048];
	struct toms_sha256 c;
	uint8_t d[32];

	for (size_t i = 0; i < n; i++) {
		data[i] = (uint8_t)(i * 7 + 3);
	}
	toms_sha256_init(&c);
	for (size_t i = 0; i < n; i += step) {
		toms_sha256_update(&c, data + i, (n - i < step) ? n - i : step);
	}
	toms_sha256_final(&c, d);
	hex(d, out);
}

static void test_sha(void)
{
	char h[65];
	struct toms_sha256 c;
	uint8_t d[32];

	toms_sha256_init(&c);
	toms_sha256_final(&c, d);
	hex(d, h);
	CHECK(strcmp(h, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") == 0);

	toms_sha256_init(&c);
	toms_sha256_update(&c, (const uint8_t *)"abc", 3);
	toms_sha256_final(&c, d);
	hex(d, h);
	CHECK(strcmp(h, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == 0);

	/* values from Python hashlib; lengths around the 55/56/64 byte padding edges */
	static const struct {
		size_t n;
		const char *want;
	} v[] = {
		{55, "e7313d333c272e639f790978283f9eb392e843d0f29b7016828bb1daa4aac70b"},
		{56, "4324d65f3c103567f5589c710bc08f8523f929a9272e3af36fc968e52abc6c27"},
		{63, "81c80242132f230c3bd41b3e63bbcff16107339549214a99614ff26664625055"},
		{64, "39e3d7b6b5d075d37d053ad89b24b41bef4f3c29760c84447cab3f3be1882241"},
		{65, "aacca6ff74fdbb296d165a45cecfa04e5127bc008770fbbdd48006f2d2fae95e"},
		{1000, "1e9bc38cbf860b9ec31918b065f9b52476c549a782e0e7990bed8ce3868d2371"},
	};
	static const size_t steps[] = {1, 7, 58, 64, 4096};

	for (size_t i = 0; i < sizeof(v) / sizeof(v[0]); i++) {
		for (size_t j = 0; j < sizeof(steps) / sizeof(steps[0]); j++) {
			sha_of(v[i].n, steps[j], h);
			CHECK(strcmp(h, v[i].want) == 0); /* same digest however it is chunked */
		}
	}
}

static void test_codec(void)
{
	uint8_t m[TOMS_OTA_START_LEN] = {TOMS_OTA_OP_START, 0x78, 0x56, 0x34, 0x12};
	uint8_t sha[32];
	uint32_t size, off;
	const uint8_t *p;
	size_t pl;

	for (int i = 0; i < 32; i++) {
		m[5 + i] = (uint8_t)i;
	}
	CHECK(toms_ota_decode_start(m, sizeof(m), &size, sha) == 0);
	CHECK(size == 0x12345678u && sha[0] == 0 && sha[31] == 31);
	CHECK(toms_ota_decode_start(m, sizeof(m) - 1, &size, sha) < 0);
	CHECK(toms_ota_decode_start(m, sizeof(m) + 1, &size, sha) < 0);
	m[0] = TOMS_OTA_OP_FINISH;
	CHECK(toms_ota_decode_start(m, sizeof(m), &size, sha) < 0);
	CHECK(toms_ota_decode_start(NULL, 37, &size, sha) < 0);

	uint8_t c[TOMS_OTA_CHUNK_HDR + TOMS_OTA_CHUNK_MAX + 1] = {0x10, 0x27, 0, 0, 0xAA};
	CHECK(toms_ota_decode_chunk(c, 5, &off, &p, &pl) == 0 && off == 10000 && pl == 1 &&
	      p[0] == 0xAA);
	CHECK(toms_ota_decode_chunk(c, TOMS_OTA_CHUNK_HDR + TOMS_OTA_CHUNK_MAX, &off, &p, &pl) == 0 &&
	      pl == TOMS_OTA_CHUNK_MAX);
	CHECK(toms_ota_decode_chunk(c, sizeof(c), &off, &p, &pl) < 0); /* too long */
	CHECK(toms_ota_decode_chunk(c, TOMS_OTA_CHUNK_HDR, &off, &p, &pl) < 0); /* no payload */
	CHECK(toms_ota_decode_chunk(c, 0, &off, &p, &pl) < 0);
	CHECK(toms_ota_decode_chunk(NULL, 8, &off, &p, &pl) < 0);

	struct toms_image_version v = {1, 2, 0x0304, 0};
	uint8_t r[TOMS_OTA_REPLY_LEN];

	CHECK(toms_ota_encode_reply(r, sizeof(r), 0x81, TOMS_OTA_OFFSET, 0x01020304u, TOMS_OTA_RECEIVING,
				    &v, TOMS_OTA_FLAG_CONFIRMED) == TOMS_OTA_REPLY_LEN);
	CHECK(r[0] == 0x81 && r[1] == TOMS_OTA_OFFSET && r[2] == 4 && r[5] == 1 && r[6] == 1);
	CHECK(r[7] == 1 && r[8] == 2 && r[9] == 4 && r[10] == 3 && r[11] == 1);
	CHECK(toms_ota_encode_reply(r, sizeof(r) - 1, 0, 0, 0, 0, &v, 0) < 0);
	CHECK(toms_ota_encode_reply(r, sizeof(r), 0, 0, 0, 0, NULL, 0) == TOMS_OTA_REPLY_LEN && r[7] == 0);
}

static void test_session(void)
{
	struct toms_ota_session s;
	uint8_t sha[32] = {1}, sha2[32] = {2};
	uint32_t exp;
	bool resumed;
	const uint32_t slot = 1344u * 1024u;

	toms_ota_init(&s, slot);
	CHECK(s.state == TOMS_OTA_IDLE);

	/* nothing works before START */
	CHECK(toms_ota_chunk(&s, 0, 10, &exp) == TOMS_OTA_STATE);
	CHECK(toms_ota_finish(&s, &exp) == TOMS_OTA_STATE);

	/* size limits */
	CHECK(toms_ota_start(&s, 0, sha, &resumed) == TOMS_OTA_SIZE);
	CHECK(toms_ota_start(&s, 32, sha, &resumed) == TOMS_OTA_SIZE);
	CHECK(toms_ota_start(&s, slot, sha, &resumed) == TOMS_OTA_SIZE);
	CHECK(toms_ota_start(&s, slot - TOMS_OTA_TRAILER_RESERVE + 1, sha, &resumed) == TOMS_OTA_SIZE);
	CHECK(s.state == TOMS_OTA_IDLE); /* a refused start changes nothing */
	CHECK(toms_ota_start(&s, slot - TOMS_OTA_TRAILER_RESERVE, sha, &resumed) == TOMS_OTA_OK);
	toms_ota_abort(&s);

	/* normal upload of 200 bytes */
	CHECK(toms_ota_start(&s, 200, sha, &resumed) == TOMS_OTA_OK && !resumed);
	CHECK(toms_ota_chunk(&s, 0, 58, &exp) == TOMS_OTA_OK && s.rx_next == 58);
	CHECK(toms_ota_chunk(&s, 58, 58, &exp) == TOMS_OTA_OK);

	/* out-of-order chunk is refused and says where to resume; nothing advances */
	CHECK(toms_ota_chunk(&s, 200, 10, &exp) == TOMS_OTA_OFFSET && exp == 116);
	CHECK(toms_ota_chunk(&s, 58, 58, &exp) == TOMS_OTA_OFFSET && exp == 116); /* a repeat */
	CHECK(toms_ota_chunk(&s, 0xFFFFFFFFu, 58, &exp) == TOMS_OTA_OFFSET && s.rx_next == 116);
	CHECK(toms_ota_chunk(&s, 116, 0, &exp) == TOMS_OTA_BAD_MSG);
	CHECK(toms_ota_chunk(&s, 116, TOMS_OTA_CHUNK_MAX + 1, &exp) == TOMS_OTA_BAD_MSG);
	/* early FINISH */
	CHECK(toms_ota_finish(&s, &exp) == TOMS_OTA_OFFSET && exp == 116 && s.state == TOMS_OTA_RECEIVING);
	/* data past the announced size */
	CHECK(toms_ota_chunk(&s, 116, 58, &exp) == TOMS_OTA_OK && s.rx_next == 174);
	CHECK(toms_ota_chunk(&s, 174, 58, &exp) == TOMS_OTA_SIZE && s.rx_next == 174);
	CHECK(toms_ota_chunk(&s, 174, 26, &exp) == TOMS_OTA_OK && s.rx_next == 200);
	CHECK(toms_ota_chunk(&s, 200, 1, &exp) == TOMS_OTA_SIZE);

	/* a drop mid-upload: the same START again resumes where it was; a different one restarts */
	toms_ota_abort(&s);
	CHECK(toms_ota_start(&s, 200, sha, &resumed) == TOMS_OTA_OK && !resumed);
	CHECK(toms_ota_chunk(&s, 0, 58, &exp) == TOMS_OTA_OK);
	CHECK(toms_ota_start(&s, 200, sha, &resumed) == TOMS_OTA_OK && resumed && s.rx_next == 58);
	CHECK(toms_ota_start(&s, 200, sha2, &resumed) == TOMS_OTA_OK && !resumed && s.rx_next == 0);
	CHECK(toms_ota_chunk(&s, 0, 58, &exp) == TOMS_OTA_OK);
	CHECK(toms_ota_start(&s, 201, sha2, &resumed) == TOMS_OTA_OK && !resumed && s.rx_next == 0);

	/* finish, verify */
	toms_ota_abort(&s);
	CHECK(toms_ota_start(&s, 58, sha, &resumed) == TOMS_OTA_OK);
	CHECK(toms_ota_chunk(&s, 0, 58, &exp) == TOMS_OTA_OK);
	CHECK(toms_ota_finish(&s, &exp) == TOMS_OTA_OK && s.state == TOMS_OTA_VERIFYING);
	CHECK(toms_ota_chunk(&s, 58, 1, &exp) == TOMS_OTA_STATE); /* no data while verifying */
	CHECK(toms_ota_start(&s, 58, sha, &resumed) == TOMS_OTA_BUSY);
	toms_ota_verified(&s, false);
	CHECK(s.state == TOMS_OTA_FAILED);
	CHECK(toms_ota_start(&s, 58, sha, &resumed) == TOMS_OTA_OK && !resumed); /* retry allowed */
	CHECK(toms_ota_chunk(&s, 0, 58, &exp) == TOMS_OTA_OK);
	CHECK(toms_ota_finish(&s, &exp) == TOMS_OTA_OK);
	toms_ota_verified(&s, true);
	CHECK(s.state == TOMS_OTA_READY);
	CHECK(toms_ota_start(&s, 58, sha, &resumed) == TOMS_OTA_BUSY); /* about to reboot */
	toms_ota_verified(&s, false); /* ignored outside VERIFYING */
	CHECK(s.state == TOMS_OTA_READY);
	toms_ota_abort(&s);
	CHECK(s.state == TOMS_OTA_IDLE);
}

static void put32(uint8_t *p, uint32_t v)
{
	p[0] = (uint8_t)v;
	p[1] = (uint8_t)(v >> 8);
	p[2] = (uint8_t)(v >> 16);
	p[3] = (uint8_t)(v >> 24);
}

static void test_header(void)
{
	uint8_t h[64] = {0};
	struct toms_image_version v;

	put32(h, 0x96f3b83du);
	h[8] = 0x20; /* hdr_size 32 */
	put32(h + 12, 1000);
	h[20] = 2;
	h[21] = 5;
	h[22] = 7;
	put32(h + 24, 99);

	CHECK(toms_ota_parse_header(h, sizeof(h), 1200, &v) == TOMS_OTA_OK);
	CHECK(v.major == 2 && v.minor == 5 && v.revision == 7 && v.build == 99);
	CHECK(toms_ota_parse_header(h, 31, 1200, &v) == TOMS_OTA_HEADER);
	CHECK(toms_ota_parse_header(NULL, 64, 1200, &v) == TOMS_OTA_HEADER);
	CHECK(toms_ota_parse_header(h, sizeof(h), 1032, &v) == TOMS_OTA_HEADER); /* no room for TLVs */
	CHECK(toms_ota_parse_header(h, sizeof(h), 1040, &v) == TOMS_OTA_OK);
	CHECK(toms_ota_parse_header(h, sizeof(h), 900, &v) == TOMS_OTA_HEADER); /* body > stored */
	h[0] ^= 1;
	CHECK(toms_ota_parse_header(h, sizeof(h), 1200, &v) == TOMS_OTA_HEADER); /* bad magic */
	h[0] ^= 1;
	put32(h + 12, 0);
	CHECK(toms_ota_parse_header(h, sizeof(h), 1200, &v) == TOMS_OTA_HEADER);
	put32(h + 12, 0xFFFFFFFFu); /* size overflow tries */
	CHECK(toms_ota_parse_header(h, sizeof(h), 1200, &v) == TOMS_OTA_HEADER);
	put32(h + 12, 1000);
	h[8] = 0x10; /* hdr_size 16 < 32 */
	CHECK(toms_ota_parse_header(h, sizeof(h), 1200, &v) == TOMS_OTA_HEADER);
	h[8] = 0xFF;
	h[9] = 0xFF; /* hdr_size 65535 */
	CHECK(toms_ota_parse_header(h, sizeof(h), 1200, &v) == TOMS_OTA_HEADER);

	struct toms_image_version a = {1, 0, 0, 0}, b = {1, 0, 0, 1}, c = {1, 0, 1, 0}, d = {1, 1, 0, 0},
				  e = {2, 0, 0, 0};
	CHECK(toms_ota_version_cmp(&a, &a) == 0);
	CHECK(toms_ota_version_cmp(&b, &a) > 0 && toms_ota_version_cmp(&a, &b) < 0);
	CHECK(toms_ota_version_cmp(&c, &b) > 0 && toms_ota_version_cmp(&d, &c) > 0);
	CHECK(toms_ota_version_cmp(&e, &d) > 0 && toms_ota_version_cmp(&a, &e) < 0);
}

int main(void)
{
	test_sha();
	test_codec();
	test_session();
	test_header();
	printf("ota/sha256: %d checks, %d failures\n", checks, failures);
	return failures ? 1 : 0;
}
