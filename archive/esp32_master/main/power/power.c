/**
 * @file power.c
 * @brief TOMS battery monitoring via ADC with voltage divider.
 */

#include "power.h"

#include "esp_log.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"

static const char *TAG = "toms_power";

/* ── Internal State ───────────────────────────────────────────────────── */

static adc_oneshot_unit_handle_t s_adc_handle = NULL;
static adc_cali_handle_t         s_cali_handle = NULL;
static bool                      s_initialized = false;

/* ── Initialization ───────────────────────────────────────────────────── */

int toms_power_init(void)
{
    if (s_initialized) return 0;

    /* ADC unit init */
    adc_oneshot_unit_init_cfg_t unit_cfg = {
        .unit_id = ADC_UNIT_1,
    };
    esp_err_t err = adc_oneshot_new_unit(&unit_cfg, &s_adc_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "ADC unit init failed: %s", esp_err_to_name(err));
        return (int)err;
    }

    /* Channel config */
    adc_oneshot_chan_cfg_t chan_cfg = {
        .atten    = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_12,
    };
    err = adc_oneshot_config_channel(s_adc_handle, TOMS_BATTERY_ADC_CHANNEL, &chan_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "ADC channel config failed: %s", esp_err_to_name(err));
        return (int)err;
    }

    /* Calibration (curve fitting if available) */
#if ADC_CALI_SCHEME_CURVE_FITTING_SUPPORTED
    adc_cali_curve_fitting_config_t cali_cfg = {
        .unit_id  = ADC_UNIT_1,
        .chan     = TOMS_BATTERY_ADC_CHANNEL,
        .atten    = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_12,
    };
    err = adc_cali_create_scheme_curve_fitting(&cali_cfg, &s_cali_handle);
#elif ADC_CALI_SCHEME_LINE_FITTING_SUPPORTED
    adc_cali_line_fitting_config_t cali_cfg = {
        .unit_id  = ADC_UNIT_1,
        .atten    = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_12,
    };
    err = adc_cali_create_scheme_line_fitting(&cali_cfg, &s_cali_handle);
#else
    ESP_LOGW(TAG, "No ADC calibration scheme available");
    err = ESP_OK;
#endif

    if (err != ESP_OK) {
        ESP_LOGW(TAG, "ADC calibration init failed, readings may be less accurate");
        s_cali_handle = NULL;
    }

    s_initialized = true;
    ESP_LOGI(TAG, "Power monitoring initialized (ADC1 CH%d)", TOMS_BATTERY_ADC_CHANNEL);
    return 0;
}

/* ── Battery Reading ──────────────────────────────────────────────────── */

uint32_t toms_power_get_battery_mv(void)
{
    if (!s_initialized) return 0;

    int raw = 0;
    esp_err_t err = adc_oneshot_read(s_adc_handle, TOMS_BATTERY_ADC_CHANNEL, &raw);
    if (err != ESP_OK) return 0;

    int voltage_mv = 0;
    if (s_cali_handle) {
        adc_cali_raw_to_voltage(s_cali_handle, raw, &voltage_mv);
    } else {
        /* Rough estimate: 12-bit ADC, 0–3.3V range at 12dB attenuation */
        voltage_mv = (raw * 3300) / 4095;
    }

    /* Account for voltage divider */
    uint32_t battery_mv = (uint32_t)(voltage_mv / TOMS_BATTERY_DIVIDER_RATIO);
    return battery_mv;
}

uint8_t toms_power_get_battery_pct(void)
{
    uint32_t mv = toms_power_get_battery_mv();

    /* LiPo voltage curve approximation:
     *   4.2V = 100%, 3.7V = 50%, 3.3V = 20%, 3.0V = 0% */
    if (mv >= 4200) return 100;
    if (mv <= 3000) return 0;

    /* Linear interpolation (good enough for monitoring) */
    return (uint8_t)((mv - 3000) * 100 / 1200);
}

bool toms_power_is_low(void)
{
    return toms_power_get_battery_mv() <= TOMS_BATTERY_LOW_MV;
}

bool toms_power_is_critical(void)
{
    return toms_power_get_battery_mv() <= TOMS_BATTERY_CRITICAL_MV;
}
