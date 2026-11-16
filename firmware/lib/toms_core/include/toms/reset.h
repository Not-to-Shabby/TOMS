#ifndef TOMS_RESET_H
#define TOMS_RESET_H

#include <stdint.h>

/* Reset causes of the ESP32-S3, as the ROM reports them (soc_reset_reason_t in ESP-IDF
 * components/soc/esp32s3/include/soc/reset_reasons.h), mapped onto the heartbeat's codes
 * (PROTOCOL.md). Pure code so the table is covered by host tests. */

#define TOMS_RESET_POWER_ON 0u
#define TOMS_RESET_BROWNOUT 1u
#define TOMS_RESET_WATCHDOG 2u
#define TOMS_RESET_SOFTWARE 3u
#define TOMS_RESET_PIN_DEBUG 4u
#define TOMS_RESET_OTHER 5u

/* Heartbeat code for a raw cause. Unknown values give TOMS_RESET_OTHER. */
uint8_t toms_reset_classify(uint32_t raw);

/* Human-readable cause, or "unrecognised cause" for a value not in the table. Never NULL. */
const char *toms_reset_cause_name(uint32_t raw);

/* True when the raw cause is one of the watchdog resets (main, RTC or super watchdog). */
int toms_reset_is_watchdog(uint32_t raw);

#endif /* TOMS_RESET_H */
