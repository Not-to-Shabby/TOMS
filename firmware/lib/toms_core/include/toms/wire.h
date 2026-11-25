/* Which message layout this connection uses. A connection starts on wire v1, the layout the
 * shipped phone parses (its framing is strict: a longer card_read or heartbeat is dropped as
 * "bad size"). The phone opts into v2 with diag command 0x07 and gets the new fields from then
 * on; the machine never sends v2 unasked. Pure code: runs on the host under test.
 *
 *   phone -> machine (diag 0x0E)   [0x07, version]            version 1 or 2
 *   machine -> phone (diag notify) [0x87, status, version, max]
 *       status  0 ok, 1 unsupported version (unchanged), 2 malformed (unchanged)
 *       version the layout in force after this command
 *       max     highest layout this firmware speaks (2)
 */
#ifndef TOMS_WIRE_H
#define TOMS_WIRE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <toms/codec.h>

#define TOMS_DIAG_CMD_WIRE 0x07u
#define TOMS_DIAG_WIRE_REPLY 0x87u

#define TOMS_WIRE_OK 0u
#define TOMS_WIRE_UNSUPPORTED 1u
#define TOMS_WIRE_MALFORMED 2u

struct toms_wire {
	uint8_t version; /* TOMS_WIRE_V1 or V2 */
	bool chosen;     /* the phone has said which one it wants on this connection */
};

/* New connection: back to v1, nothing chosen. */
void toms_wire_reset(struct toms_wire *w);

/* Handles the diag write `data` (command byte included). Fills `reply` and returns the status. */
uint8_t toms_wire_handle_select(struct toms_wire *w, const uint8_t *data, size_t len,
				uint8_t reply[4]);

#endif /* TOMS_WIRE_H */
