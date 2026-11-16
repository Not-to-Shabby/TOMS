#include "reset_reason.h"

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(reset_reason, LOG_LEVEL_INF);

#include "toms_pins.h"

#if defined(CONFIG_SOC_SERIES_ESP32S3)
/* ESP32-S3 reset causes live in the ROM registers of the SoC. There is no
 * generic Zephyr reset-reason API (as of Zephyr 4.x), so read the SoC value
 * and map it onto the heartbeat's codes. esp_rom_get_reset_reason is
 * declared in the ROM layer's esp_rom_sys.h (hal_espressif). */
#include <esp_rom_sys.h>

static uint32_t soc_reset_reason(void)
{
	return esp_rom_get_reset_reason(0);
}
#else
static uint32_t soc_reset_reason(void)
{
	return 0;
}
#endif

uint32_t toms_reset_raw(void)
{
	return soc_reset_reason();
}

uint8_t toms_reset_reason(void)
{
	return toms_reset_classify(soc_reset_reason());
}

const char *toms_reset_reason_name(uint8_t reason)
{
	switch (reason) {
	case TOMS_RESET_POWER_ON:
		return "power-on";
	case TOMS_RESET_BROWNOUT:
		return "brownout";
	case TOMS_RESET_WATCHDOG:
		return "watchdog";
	case TOMS_RESET_SOFTWARE:
		return "software";
	case TOMS_RESET_PIN_DEBUG:
		return "pin/debug";
	default:
		return "other";
	}
}
