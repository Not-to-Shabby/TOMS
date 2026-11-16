#ifndef TOMS_GUARD_H
#define TOMS_GUARD_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/* Decision logic for the OTA trial boot (firmware/OTA_PLAN.md, phase 2). Pure code: the
 * caller supplies the clock and the observations, so it runs on the host under test. */

/* Self-test: a new image on trial must be healthy for `need_good` consecutive checks before
 * the deadline, otherwise it must be given up so the bootloader restores the old image. */
enum toms_selftest_state {
	TOMS_SELFTEST_PENDING = 0,
	TOMS_SELFTEST_PASSED = 1,
	TOMS_SELFTEST_FAILED = 2,
};

struct toms_selftest {
	uint32_t need_good;
	uint32_t deadline_ms;
	uint32_t good;
	enum toms_selftest_state state;
};

/* need_good below 1 is raised to 1. */
void toms_selftest_init(struct toms_selftest *s, uint32_t need_good, uint32_t deadline_ms);

/* Call once per check. A healthy check adds to the streak, an unhealthy one resets it. A pass
 * wins over the deadline when both happen on the same check. PASSED and FAILED are final. */
enum toms_selftest_state toms_selftest_tick(struct toms_selftest *s, uint32_t uptime_ms,
					    bool healthy);

/* Liveness: the watchdog may only be fed while every monitored task has made progress since
 * the previous check. Each task increments its own counter; this compares snapshots. */
#define TOMS_LIVENESS_MAX 4u

struct toms_liveness {
	uint32_t last[TOMS_LIVENESS_MAX];
	bool primed;
};

void toms_liveness_init(struct toms_liveness *l);

/* True when all `n` counters differ from the last call. The first call only records them and
 * returns true. Returns false for n == 0, n above TOMS_LIVENESS_MAX or a NULL argument. */
bool toms_liveness_check(struct toms_liveness *l, const uint32_t *now, size_t n);

#endif /* TOMS_GUARD_H */
