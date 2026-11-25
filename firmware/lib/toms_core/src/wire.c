#include <toms/wire.h>

void toms_wire_reset(struct toms_wire *w)
{
	w->version = TOMS_WIRE_V1;
	w->chosen = false;
}

uint8_t toms_wire_handle_select(struct toms_wire *w, const uint8_t *data, size_t len,
				uint8_t reply[4])
{
	uint8_t status;

	if (data == NULL || len != 2u || data[0] != TOMS_DIAG_CMD_WIRE) {
		status = TOMS_WIRE_MALFORMED;
	} else if (data[1] == TOMS_WIRE_V1 || data[1] == TOMS_WIRE_V2) {
		w->version = data[1];
		w->chosen = true;
		status = TOMS_WIRE_OK;
	} else {
		status = TOMS_WIRE_UNSUPPORTED;
	}
	reply[0] = TOMS_DIAG_WIRE_REPLY;
	reply[1] = status;
	reply[2] = w->version;
	reply[3] = (uint8_t)TOMS_WIRE_V2;
	return status;
}
