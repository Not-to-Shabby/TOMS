#include <toms/ring.h>

void toms_ring_init(struct toms_ring *r, uint32_t capacity)
{
	r->capacity = capacity;
	r->head = 0;
	r->count = 0;
}

bool toms_ring_empty(const struct toms_ring *r)
{
	return r->count == 0;
}

bool toms_ring_full(const struct toms_ring *r)
{
	return r->count == r->capacity;
}

uint32_t toms_ring_count(const struct toms_ring *r)
{
	return r->count;
}

uint32_t toms_ring_push_slot(struct toms_ring *r)
{
	if (r->count >= r->capacity) {
		return TOMS_RING_NO_SLOT;
	}
	uint32_t slot = (r->head + r->count) % r->capacity;
	r->count++;
	return slot;
}

uint32_t toms_ring_peek_slot(const struct toms_ring *r, uint32_t n)
{
	if (n >= r->count) {
		return TOMS_RING_NO_SLOT;
	}
	return (r->head + n) % r->capacity;
}

uint32_t toms_ring_pop_slot(struct toms_ring *r)
{
	if (r->count == 0) {
		return TOMS_RING_NO_SLOT;
	}
	uint32_t slot = r->head;
	r->head = (r->head + 1u) % r->capacity;
	r->count--;
	return slot;
}
