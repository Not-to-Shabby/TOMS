#include "battery.h"

/* GPIO1 is reserved for the battery sense divider, but the gauge choice and the divider are
 * still open (docs/return-machine-v2-parts-list), so there is nothing to measure yet. The
 * heartbeat already carries the two fields; this is the one place to fill in. */
void battery_read(uint8_t *pct, uint16_t *mv)
{
	*pct = BATTERY_PCT_UNKNOWN;
	*mv = 0;
}
