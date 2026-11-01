#ifndef TOMS_RESET_REASON_H
#define TOMS_RESET_REASON_H

#include <stdint.h>

/* Reason codes sent in the heartbeat (PROTOCOL.md):
 * 0 power-on, 1 brownout, 2 watchdog, 3 software, 4 pin/debug, 5 other. */
#include <toms/reset.h> /* TOMS_RESET_* codes and the cause table */

/* The ROM's raw reset cause (ESP32-S3 soc_reset_reason_t). */
uint32_t toms_reset_raw(void);

uint8_t toms_reset_reason(void);
const char *toms_reset_reason_name(uint8_t reason);

#endif /* TOMS_RESET_REASON_H */
