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

uint8_t toms_reset_reason(void)
{
	switch (soc_reset_reason()) {
	case 1: /* POWERON */
	case 10: /* SW_CPU_RESET on some chip revisions */
		return TOMS_RESET_POWER_ON;
	case 2: /* SW_RESET */
	case 3: /* DEEPSLEEP_RESET */
		return TOMS_RESET_SOFTWARE;
	case 4: /* TG0WDT_SYS_RESET */
	case 5: /* TG1WDT_SYS_RESET */
	case 7: /* TG0WDT_CPU_RESET */
	case 8: /* RTCWDT_SYS_RESET */
	case 9: /* RTCWDT_CPU_RESET */
	case 16: /* TG_WDT / RTCWDT_BROWN_OUT variants live here on S3 */
		return TOMS_RESET_WATCHDOG;
	case 11: /* INTRUSION_RESET */
	case 12: /* TGWDT_CPU_RESET */
		return TOMS_RESET_WATCHDOG;
	case 13: /* BROWN_OUT_RESET */
		return TOMS_RESET_BROWNOUT;
	case 14: /* RTCWDT_BROWN_OUT_RESET */
		return TOMS_RESET_BROWNOUT;
	case 15: /* CLK_SWITCH / super WDT */
		return TOMS_RESET_WATCHDOG;
	default:
		return TOMS_RESET_OTHER;
	}
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
