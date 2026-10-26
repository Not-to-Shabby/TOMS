#include <toms/state_table.h>

#include <errno.h>
#include <string.h>

void toms_state_table_init(struct toms_state_table *t)
{
	memset(t, 0, sizeof(*t));
}

static bool entry_eq(const struct toms_state_entry *e, const uint8_t *uid, uint8_t uid_len)
{
	return e->uid_len == uid_len && memcmp(e->uid, uid, uid_len) == 0;
}

int toms_state_table_apply(struct toms_state_table *t, uint32_t version, bool snapshot,
			   const struct toms_state_entry *entries, uint16_t count)
{
	if (t == NULL || (count > 0 && entries == NULL)) {
		return -EINVAL;
	}
	if (version < t->version) {
		return -EINVAL; /* the phone never rolls the table back */
	}

	if (snapshot) {
		if (count > TOMS_STATE_TABLE_MAX) {
			return -ERANGE;
		}
		memcpy(t->entries, entries, (size_t)count * sizeof(*entries));
		t->count = count;
		t->version = version;
		return 0;
	}

	for (uint16_t i = 0; i < count; i++) {
		const struct toms_state_entry *in = &entries[i];
		if (in->uid_len == 0 || in->uid_len > TOMS_UID_MAX) {
			return -EINVAL;
		}

		struct toms_state_entry *slot = NULL;
		for (uint16_t j = 0; j < t->count; j++) {
			if (entry_eq(&t->entries[j], in->uid, in->uid_len)) {
				slot = &t->entries[j];
				break;
			}
		}
		if (slot == NULL) {
			if (t->count >= TOMS_STATE_TABLE_MAX) {
				return -ERANGE;
			}
			slot = &t->entries[t->count++];
		}
		*slot = *in;
	}
	t->version = version;
	return 0;
}

uint8_t toms_state_table_lookup(const struct toms_state_table *t, const uint8_t *uid,
				uint8_t uid_len)
{
	if (t == NULL || uid == NULL || uid_len == 0 || uid_len > TOMS_UID_MAX) {
		return TOMS_STATUS_UNKNOWN;
	}
	for (uint16_t j = 0; j < t->count; j++) {
		if (entry_eq(&t->entries[j], uid, uid_len)) {
			return t->entries[j].status;
		}
	}
	return TOMS_STATUS_UNKNOWN;
}
