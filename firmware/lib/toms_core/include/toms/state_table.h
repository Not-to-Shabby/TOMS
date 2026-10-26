#ifndef TOMS_STATE_TABLE_H
#define TOMS_STATE_TABLE_H

#include <stdbool.h>
#include <stdint.h>

/* Cached card state, mirrored from the phone (PROTOCOL.md state_update).
 * The phone owns the truth; this table only powers the offline fallback. */

#define TOMS_STATUS_AVAILABLE 0u
#define TOMS_STATUS_ASSIGNED_UNPAID 1u
#define TOMS_STATUS_ASSIGNED_PAID 2u
#define TOMS_STATUS_RETURNED 3u
#define TOMS_STATUS_RETURNED_UNPAID 4u
#define TOMS_STATUS_LOST 5u
#define TOMS_STATUS_UNKNOWN 0xFFu

#define TOMS_UID_MAX 10u
#define TOMS_STATE_TABLE_MAX 128u

struct toms_state_entry {
	uint8_t uid[TOMS_UID_MAX];
	uint8_t uid_len;
	uint8_t status;
};

struct toms_state_table {
	uint32_t version;
	uint16_t count;
	struct toms_state_entry entries[TOMS_STATE_TABLE_MAX];
};

void toms_state_table_init(struct toms_state_table *t);

/* Apply a delta or a full snapshot. Version must not go backwards.
 * Returns 0, -EINVAL on bad input or a stale version, -ERANGE when a
 * snapshot does not fit. */
int toms_state_table_apply(struct toms_state_table *t, uint32_t version, bool snapshot,
			   const struct toms_state_entry *entries, uint16_t count);

/* Status for a UID: one of TOMS_STATUS_* or TOMS_STATUS_UNKNOWN. */
uint8_t toms_state_table_lookup(const struct toms_state_table *t, const uint8_t *uid,
				uint8_t uid_len);

#endif /* TOMS_STATE_TABLE_H */
