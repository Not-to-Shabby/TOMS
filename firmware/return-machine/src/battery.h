/* Battery measurement hook (GPIO1 reserved). Not implemented: reports "not measurable". */
#ifndef TOMS_BATTERY_H
#define TOMS_BATTERY_H

#include <stdint.h>

#define BATTERY_PCT_UNKNOWN 0xFFu

void battery_read(uint8_t *pct, uint16_t *mv);

#endif /* TOMS_BATTERY_H */
