#ifndef TOMS_RESET_REASON_H
#define TOMS_RESET_REASON_H

#include <stdint.h>

/* Reason codes sent in the heartbeat (PROTOCOL.md):
 * 0 power-on, 1 brownout, 2 watchdog, 3 software, 4 pin/debug, 5 other. */
#define TOMS_RESET_POWER_ON 0u
#define TOMS_RESET_BROWNOUT 1u
#define TOMS_RESET_WATCHDOG 2u
#define TOMS_RESET_SOFTWARE 3u
#define TOMS_RESET_PIN_DEBUG 4u
#define TOMS_RESET_OTHER 5u

uint8_t toms_reset_reason(void);
const char *toms_reset_reason_name(uint8_t reason);

#endif /* TOMS_RESET_REASON_H */
