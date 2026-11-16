#include <toms/clock.h>

#include <errno.h>
#include <stddef.h>

void toms_clock_init(struct toms_clock *c)
{
	c->synced = false;
	c->offset_ms = 0;
}

int toms_clock_sync(struct toms_clock *c, uint32_t epoch, uint16_t remaining_ms,
		    int64_t uptime_ms)
{
	if (c == NULL || remaining_ms >= 1000u) {
		return -EINVAL;
	}
	c->offset_ms = (int64_t)epoch * 1000 + remaining_ms - uptime_ms;
	c->synced = true;
	return 0;
}

uint32_t toms_clock_epoch(const struct toms_clock *c, int64_t uptime_ms)
{
	if (c == NULL || !c->synced) {
		return 0;
	}
	return (uint32_t)((uptime_ms + c->offset_ms) / 1000);
}
