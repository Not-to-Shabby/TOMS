#ifndef TOMS_CLOCK_H
#define TOMS_CLOCK_H

#include <stdbool.h>
#include <stdint.h>

/* UTC wall clock derived from the phone's time_sync and the local uptime.
 * The caller supplies the uptime, so this stays portable and host-testable. */
struct toms_clock {
	bool synced;
	int64_t offset_ms; /* epoch ms minus uptime ms at the moment of sync */
};

void toms_clock_init(struct toms_clock *c);

/* epoch is UTC seconds and remaining_ms the sub-second part (below 1000).
 * Returns 0, or -EINVAL when remaining_ms is out of range. */
int toms_clock_sync(struct toms_clock *c, uint32_t epoch, uint16_t remaining_ms,
		    int64_t uptime_ms);

/* UTC seconds now, or 0 when the clock was never synced. */
uint32_t toms_clock_epoch(const struct toms_clock *c, int64_t uptime_ms);

#endif /* TOMS_CLOCK_H */
