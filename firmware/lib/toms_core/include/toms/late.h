/* Paid replies that arrive after the machine gave up waiting and played the unverified tone.
 * The alarm is never touched by a late reply; it is only counted, so the phone can see in the
 * heartbeat how often its answers were too slow. Pure code: runs on the host under test. */
#ifndef TOMS_LATE_H
#define TOMS_LATE_H

#include <stdbool.h>
#include <stdint.h>

#define TOMS_LATE_SLOTS 8u

struct toms_late {
	uint32_t seq[TOMS_LATE_SLOTS]; /* check_seq of checks that timed out; 0 = empty (seqs start at 1) */
	uint8_t head;
	uint16_t count; /* saturating */
};

void toms_late_init(struct toms_late *t);

/* A check timed out. The oldest remembered one is forgotten when all slots are in use. */
void toms_late_note_timeout(struct toms_late *t, uint32_t check_seq);

/* A reply that matched no waiting check. True (and counted once) when it answers a check that
 * timed out; false for a reply to a check this machine never sent, or one already counted. */
bool toms_late_on_reply(struct toms_late *t, uint32_t check_seq);

uint16_t toms_late_count(const struct toms_late *t);

#endif /* TOMS_LATE_H */
