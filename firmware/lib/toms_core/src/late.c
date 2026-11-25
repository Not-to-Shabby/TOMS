#include <toms/late.h>

#include <stddef.h>

void toms_late_init(struct toms_late *t)
{
	for (uint8_t i = 0; i < TOMS_LATE_SLOTS; i++) {
		t->seq[i] = 0;
	}
	t->head = 0;
	t->count = 0;
}

void toms_late_note_timeout(struct toms_late *t, uint32_t check_seq)
{
	if (t == NULL || check_seq == 0) {
		return;
	}
	t->seq[t->head] = check_seq;
	t->head = (uint8_t)((t->head + 1u) % TOMS_LATE_SLOTS);
}

bool toms_late_on_reply(struct toms_late *t, uint32_t check_seq)
{
	if (t == NULL || check_seq == 0) {
		return false;
	}
	for (uint8_t i = 0; i < TOMS_LATE_SLOTS; i++) {
		if (t->seq[i] == check_seq) {
			t->seq[i] = 0;
			if (t->count != 0xFFFFu) {
				t->count++;
			}
			return true;
		}
	}
	return false;
}

uint16_t toms_late_count(const struct toms_late *t)
{
	return t->count;
}
