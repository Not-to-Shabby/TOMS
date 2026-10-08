/**
 * @file power.h
 * @brief TOMS power management — battery monitoring and low-power hints.
 */

#ifndef TOMS_POWER_H
#define TOMS_POWER_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/** ADC channel for battery voltage (via voltage divider) */
#define TOMS_BATTERY_ADC_CHANNEL    0   /* GPIO1 on ESP32-S3 = ADC1_CH0 */

/** Low battery threshold in millivolts (3.3V = ~20% for LiPo) */
#define TOMS_BATTERY_LOW_MV         3300

/** Critical battery threshold in millivolts */
#define TOMS_BATTERY_CRITICAL_MV    3100

/** Voltage divider ratio (R1/(R1+R2)), e.g., 100k/200k = 0.5 */
#define TOMS_BATTERY_DIVIDER_RATIO  0.5f

/**
 * @brief Initialize battery monitoring ADC.
 * @return ESP_OK on success.
 */
int toms_power_init(void);

/**
 * @brief Read battery voltage in millivolts.
 * @return Voltage in mV, or 0 on error.
 */
uint32_t toms_power_get_battery_mv(void);

/**
 * @brief Get battery level as percentage (0–100).
 */
uint8_t toms_power_get_battery_pct(void);

/**
 * @brief Check if battery is low.
 */
bool toms_power_is_low(void);

/**
 * @brief Check if battery is critically low.
 */
bool toms_power_is_critical(void);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_POWER_H */
