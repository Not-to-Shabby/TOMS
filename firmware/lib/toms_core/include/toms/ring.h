#ifndef TOMS_RING_H
#define TOMS_RING_H

#include <stdbool.h>
#include <stdint.h>

/* Index math for the flash-backed card-read ring (PROTOCOL.md: reads while
 * disconnected are buffered in flash with sequence numbers and sent on
 * reconnect). Storage lives behind the caller: a slot index maps to a fixed
 * record slot; each record also carries its own read_seq so ordering
 * survives a corrupted head. The NVS wrapper lives in the Zephyr app. */

#define TOMS_RING_NO_SLOT UINT32_MAX

struct toms_ring {
	uint32_t capacity;
	uint32_t head;  /* oldest entry's slot */
	uint32_t count; /* entries stored */
};

void toms_ring_init(struct toms_ring *r, uint32_t capacity);

bool toms_ring_empty(const struct toms_ring *r);
bool toms_ring_full(const struct toms_ring *r);
uint32_t toms_ring_count(const struct toms_ring *r);

/* Reserve the next write slot and grow the ring, or TOMS_RING_NO_SLOT when
 * full (caller decides: drop the oldest or the newest read). */
uint32_t toms_ring_push_slot(struct toms_ring *r);

/* Slot of the n-th oldest entry (0 = oldest), or TOMS_RING_NO_SLOT. */
uint32_t toms_ring_peek_slot(const struct toms_ring *r, uint32_t n);

/* Release the oldest entry and return its slot, or TOMS_RING_NO_SLOT. */
uint32_t toms_ring_pop_slot(struct toms_ring *r);

#endif /* TOMS_RING_H */
