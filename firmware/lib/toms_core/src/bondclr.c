#include <toms/bondclr.h>

#include <stddef.h>

void toms_bondclr_init(struct toms_bondclr *s, uint32_t now_ms, bool held)
{
	s->state = held ? TOMS_BONDCLR_HOLDING : TOMS_BONDCLR_IDLE;
	s->start_ms = now_ms;
	s->last_ms = now_ms;
	s->released_ms = 0;
	s->released = false;
}

bool toms_bondclr_active(const struct toms_bondclr *s)
{
	return s->state == TOMS_BONDCLR_HOLDING;
}

enum toms_bondclr_event toms_bondclr_tick(struct toms_bondclr *s, uint32_t now_ms, bool pressed)
{
	if (s->state != TOMS_BONDCLR_HOLDING) {
		return TOMS_BONDCLR_NONE;
	}

	/* A sample that arrives late means a release could have gone unseen. A sample that goes back in
	 * time makes the unsigned difference huge, so it is caught by the same test. */
	uint32_t since_last = now_ms - s->last_ms;

	if (since_last > TOMS_BONDCLR_MAX_GAP_MS) {
		s->state = TOMS_BONDCLR_GAVE_UP;
		return TOMS_BONDCLR_NONE;
	}
	s->last_ms = now_ms;

	if (!pressed) {
		if (!s->released) {
			s->released = true;
			s->released_ms = now_ms;
		}
		if (now_ms - s->released_ms >= TOMS_BONDCLR_RELEASE_MS) {
			s->state = TOMS_BONDCLR_GAVE_UP;
		}
		return TOMS_BONDCLR_NONE;
	}
	s->released = false; /* a short release (bounce) is forgiven */

	if (now_ms - s->start_ms >= TOMS_BONDCLR_HOLD_MS) {
		s->state = TOMS_BONDCLR_DONE;
		return TOMS_BONDCLR_FIRE;
	}
	return TOMS_BONDCLR_NONE;
}
