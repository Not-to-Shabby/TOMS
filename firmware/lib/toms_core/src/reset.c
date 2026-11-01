#include <toms/reset.h>

#include <stddef.h>

struct cause {
	uint32_t raw;
	uint8_t cls;
	const char *name;
};

/* Values and meanings from ESP-IDF's soc/esp32s3/include/soc/reset_reasons.h. Several names
 * share one value there (0x01 is power-on, brown-out and super watchdog at chip level); one
 * entry per value, with the ambiguity stated in the name. */
static const struct cause causes[] = {
	{0x01, TOMS_RESET_POWER_ON, "chip reset: power-on, brown-out or super watchdog (the ROM cannot tell)"},
	{0x03, TOMS_RESET_SOFTWARE, "software reset of the digital core"},
	{0x05, TOMS_RESET_OTHER, "wake from deep sleep"},
	{0x07, TOMS_RESET_WATCHDOG, "main watchdog 0 reset the digital core"},
	{0x08, TOMS_RESET_WATCHDOG, "main watchdog 1 reset the digital core"},
	{0x09, TOMS_RESET_WATCHDOG, "RTC watchdog reset the digital core"},
	{0x0B, TOMS_RESET_WATCHDOG, "main watchdog 0 reset CPU 0"},
	{0x0C, TOMS_RESET_SOFTWARE, "software reset of CPU 0"},
	{0x0D, TOMS_RESET_WATCHDOG, "RTC watchdog reset CPU 0"},
	{0x0F, TOMS_RESET_BROWNOUT, "brown-out reset of the digital core"},
	{0x10, TOMS_RESET_WATCHDOG, "RTC watchdog reset the digital core and RTC"},
	{0x11, TOMS_RESET_WATCHDOG, "main watchdog 1 reset CPU 0"},
	{0x12, TOMS_RESET_WATCHDOG, "super watchdog reset the digital core and RTC"},
	{0x13, TOMS_RESET_OTHER, "clock glitch"},
	{0x14, TOMS_RESET_OTHER, "eFuse CRC error"},
	{0x15, TOMS_RESET_PIN_DEBUG, "USB UART reset (the host reset the chip through the serial lines)"},
	{0x16, TOMS_RESET_PIN_DEBUG, "USB JTAG reset"},
	{0x17, TOMS_RESET_BROWNOUT, "power glitch"},
};

static const struct cause *find(uint32_t raw)
{
	for (size_t i = 0; i < sizeof(causes) / sizeof(causes[0]); i++) {
		if (causes[i].raw == raw) {
			return &causes[i];
		}
	}
	return NULL;
}

uint8_t toms_reset_classify(uint32_t raw)
{
	const struct cause *c = find(raw);

	return c != NULL ? c->cls : (uint8_t)TOMS_RESET_OTHER;
}

const char *toms_reset_cause_name(uint32_t raw)
{
	const struct cause *c = find(raw);

	return c != NULL ? c->name : "unrecognised cause";
}

int toms_reset_is_watchdog(uint32_t raw)
{
	return toms_reset_classify(raw) == TOMS_RESET_WATCHDOG;
}
