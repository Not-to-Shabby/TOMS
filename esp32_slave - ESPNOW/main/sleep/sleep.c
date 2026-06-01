/**
 * @file sleep.c
 * @brief TOMS deep sleep management implementation.
 */

#include "sleep.h"

#include "esp_log.h"
#include "esp_sleep.h"
#include "driver/rtc_io.h"
#include "esp_timer.h"
#include "button.h"   /* For TOMS_BUTTON_PIN */

static const char *TAG = "toms_sleep";

static int64_t s_last_activity_us = 0;

/* ── Public API ───────────────────────────────────────────────────────── */

void toms_sleep_init(void)
{
    s_last_activity_us = esp_timer_get_time();
    ESP_LOGI(TAG, "Sleep manager initialized (idle timeout: %ds, timer: %ds)",
             TOMS_SLEEP_IDLE_TIMEOUT_S, TOMS_SLEEP_TIMER_INTERVAL_S);
}

toms_wake_cause_t toms_sleep_get_wake_cause(void)
{
    esp_sleep_wakeup_cause_t cause = esp_sleep_get_wakeup_cause();

    switch (cause) {
    case ESP_SLEEP_WAKEUP_EXT0:
        ESP_LOGI(TAG, "Wake cause: BUTTON (ext0)");
        return TOMS_WAKE_BUTTON;

    case ESP_SLEEP_WAKEUP_TIMER:
        ESP_LOGI(TAG, "Wake cause: TIMER");
        return TOMS_WAKE_TIMER;

    case ESP_SLEEP_WAKEUP_UNDEFINED:
        ESP_LOGI(TAG, "Wake cause: POWER ON / RESET");
        return TOMS_WAKE_POWER_ON;

    default:
        ESP_LOGI(TAG, "Wake cause: OTHER (%d)", (int)cause);
        return TOMS_WAKE_OTHER;
    }
}

void toms_sleep_reset_idle(void)
{
    s_last_activity_us = esp_timer_get_time();
}

bool toms_sleep_should_sleep(void)
{
    int64_t elapsed_us = esp_timer_get_time() - s_last_activity_us;
    int64_t timeout_us = (int64_t)TOMS_SLEEP_IDLE_TIMEOUT_S * 1000000LL;
    return (elapsed_us >= timeout_us);
}

void toms_sleep_enter(void)
{
    ESP_LOGI(TAG, "Entering deep sleep...");

    /* Configure ext0 wake on button pin (active LOW = wake on level 0) */
    esp_sleep_enable_ext0_wakeup(TOMS_BUTTON_PIN, 0);

    /* Configure RTC GPIO */
    rtc_gpio_init(TOMS_BUTTON_PIN);
    rtc_gpio_set_direction(TOMS_BUTTON_PIN, RTC_GPIO_MODE_INPUT_ONLY);
    rtc_gpio_pullup_en(TOMS_BUTTON_PIN);

    /* Configure timer wake-up */
    esp_sleep_enable_timer_wakeup((uint64_t)TOMS_SLEEP_TIMER_INTERVAL_S * 1000000ULL);

    /* Enter deep sleep — device will reset on wake */
    ESP_LOGI(TAG, "Good night! Sleeping for up to %d seconds...",
             TOMS_SLEEP_TIMER_INTERVAL_S);

    esp_deep_sleep_start();
    /* Execution never reaches here */
}
