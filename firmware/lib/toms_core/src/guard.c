#include <toms/guard.h>

#include <string.h>

void toms_selftest_init(struct toms_selftest *s, uint32_t need_good, uint32_t deadline_ms)
{
	s->need_good = need_good < 1u ? 1u : need_good;
	s->deadline_ms = deadline_ms;
	s->good = 0;
	s->state = TOMS_SELFTEST_PENDING;
}

enum toms_selftest_state toms_selftest_tick(struct toms_selftest *s, uint32_t uptime_ms,
					    bool healthy)
{
	if (s->state != TOMS_SELFTEST_PENDING) {
		return s->state;
	}

	s->good = healthy ? s->good + 1u : 0u;

	if (s->good >= s->need_good) {
		s->state = TOMS_SELFTEST_PASSED;
	} else if (uptime_ms >= s->deadline_ms) {
		s->state = TOMS_SELFTEST_FAILED;
	}
	return s->state;
}

void toms_liveness_init(struct toms_liveness *l)
{
	memset(l, 0, sizeof(*l));
}

bool toms_liveness_check(struct toms_liveness *l, const uint32_t *now, size_t n)
{
	if (l == NULL || now == NULL || n == 0u || n > TOMS_LIVENESS_MAX) {
		return false;
	}

	bool all_moved = true;

	if (l->primed) {
		for (size_t i = 0; i < n; i++) {
			if (now[i] == l->last[i]) {
				all_moved = false;
			}
		}
	}
	for (size_t i = 0; i < n; i++) {
		l->last[i] = now[i];
	}
	l->primed = true;
	return all_moved;
}
