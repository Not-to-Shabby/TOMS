/* Randomized robustness run for every decoder that parses bytes from the phone.
 *
 * Each input sits directly before an inaccessible guard page, so a decoder that
 * reads even one byte past `len` faults and the run dies. That needs no
 * sanitizer runtime. Build with -fsanitize=undefined
 * -fsanitize-undefined-trap-on-error as well so undefined arithmetic aborts.
 * Run with "guardcheck" as the first argument to prove the guard fires: that
 * mode reads one byte past the end on purpose and is expected to crash. */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <toms/clock.h>
#include <toms/codec.h>
#include <toms/state_table.h>

#ifdef _WIN32
#include <windows.h>
#define PAGE 4096u
/* Returns a pointer so that buf[len] is the first byte of a no-access page. */
static uint8_t *guarded_alloc(size_t len, void **base)
{
	uint8_t *b = VirtualAlloc(NULL, 2u * PAGE, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
	DWORD old;

	if (b == NULL || !VirtualProtect(b + PAGE, PAGE, PAGE_NOACCESS, &old)) {
		abort();
	}
	*base = b;
	return b + PAGE - len;
}
static void guarded_free(void *base)
{
	VirtualFree(base, 0, MEM_RELEASE);
}
#else
#include <sys/mman.h>
#define PAGE 4096u
static uint8_t *guarded_alloc(size_t len, void **base)
{
	uint8_t *b = mmap(NULL, 2u * PAGE, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);

	if (b == MAP_FAILED || mprotect(b + PAGE, PAGE, PROT_NONE) != 0) {
		abort();
	}
	*base = b;
	return b + PAGE - len;
}
static void guarded_free(void *base)
{
	munmap(base, 2u * PAGE);
}
#endif

static uint32_t rng = 0x12345678u;
static uint32_t next(void)
{
	rng ^= rng << 13;
	rng ^= rng >> 17;
	rng ^= rng << 5;
	return rng;
}

int main(int argc, char **argv)
{
	if (argc > 1 && strcmp(argv[1], "guardcheck") == 0) {
		void *base;
		volatile uint8_t *p = guarded_alloc(4, &base);

		p[0] = 1;
		printf("guardcheck: reading one byte past the end, this must crash\n");
		fflush(stdout);
		return p[4]; /* first byte of the guard page */
	}

	long iters = argc > 1 ? atol(argv[1]) : 200000;
	long accepted = 0;
	static struct toms_state_entry entries[TOMS_STATE_TABLE_MAX];

	for (long i = 0; i < iters; i++) {
		size_t len = next() % 80u;
		void *base;
		uint8_t *buf = guarded_alloc(len, &base);
		for (size_t j = 0; j < len; j++) {
			buf[j] = (uint8_t)next();
		}
		/* Bias a few headers toward plausible values so deeper paths run. */
		if (len > 5 && (next() & 3u) == 0) {
			buf[4] &= 1u;                 /* state_update flags */
			buf[5] = (uint8_t)(next() % 6u); /* small entry count */
		}
		if (len > 12 && (next() & 3u) == 0) {
			buf[12] = (uint8_t)(next() % 12u); /* card_read uid_len */
		}
		if (len > 0 && (next() & 3u) == 0) {
			buf[0] = (uint8_t)(next() % 40u); /* config / uid length byte */
		}

		struct toms_card_read_msg cr;
		struct toms_config_msg cfg;
		uint32_t v32, epoch, seq;
		uint16_t v16, count;
		uint8_t u8, kind, uid[TOMS_UID_MAX], uid_len, verdict;
		bool snap;

		accepted += toms_codec_decode_card_read(buf, len, &cr) > 0;
		accepted += toms_codec_decode_paid_reply(buf, len, &seq, &verdict) > 0;
		accepted += toms_codec_decode_ack(buf, len, &kind, &v32) > 0;
		accepted += toms_codec_decode_time_sync(buf, len, &epoch, &v16) > 0;
		accepted += toms_codec_decode_config(buf, len, &cfg) > 0;
		accepted += toms_codec_decode_uid(buf, len, uid, &uid_len) > 0;
		accepted += toms_codec_decode_state_update(buf, len, &v32, &snap, entries,
							   TOMS_STATE_TABLE_MAX, &count) > 0;
		(void)u8;
		guarded_free(base);
	}

	/* Whatever the fuzz accepted, applying it to a table must hold the table's invariants. */
	struct toms_state_table t;
	toms_state_table_init(&t);
	for (long i = 0; i < iters / 10; i++) {
		struct toms_state_entry e[4];
		uint16_t n = (uint16_t)(next() % 5u);
		for (uint16_t k = 0; k < n; k++) {
			e[k].uid_len = (uint8_t)(next() % 13u);
			for (unsigned int b = 0; b < TOMS_UID_MAX; b++) {
				e[k].uid[b] = (uint8_t)(next() % 4u);
			}
			e[k].status = (uint8_t)next();
		}
		(void)toms_state_table_apply(&t, (uint32_t)(i / 7), (next() & 7u) == 0, e, n);
		if (t.count > TOMS_STATE_TABLE_MAX) {
			printf("INVARIANT BROKEN: count %u\n", t.count);
			return 1;
		}
	}
	printf("fuzz: %ld inputs, %ld accepted, table count %u, no trap\n", iters, accepted, t.count);
	return 0;
}
