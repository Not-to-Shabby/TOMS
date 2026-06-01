/**
 * @file button.h
 * @brief TOMS physical push button input with debounce.
 */

#ifndef TOMS_BUTTON_H
#define TOMS_BUTTON_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Button GPIO pin (RTC-capable for deep sleep wake) */
#define TOMS_BUTTON_PIN     4   /* GPIO4 = RTC GPIO 4 on ESP32-S3 */

/** Debounce time in milliseconds */
#define TOMS_BUTTON_DEBOUNCE_MS  50

/** Long press threshold in milliseconds */
#define TOMS_BUTTON_LONG_PRESS_MS  500

/**
 * @brief Button event types.
 */
typedef enum {
    TOMS_BTN_PRESS,         /**< Short press detected */
    TOMS_BTN_LONG_PRESS,    /**< Long press detected */
    TOMS_BTN_RELEASE,       /**< Button released */
    TOMS_BTN_HOLD_1S,       /**< Held for 1 second */
    TOMS_BTN_HOLD_2S,       /**< Held for 2 seconds */
    TOMS_BTN_HOLD_3S,       /**< Held for 3 seconds */
} toms_button_event_t;

/**
 * @brief Button event callback.
 */
typedef void (*toms_button_cb_t)(toms_button_event_t event);

/**
 * @brief Initialize button GPIO with interrupt.
 * @return ESP_OK on success.
 */
int toms_button_init(void);

/**
 * @brief Register button event callback.
 */
void toms_button_set_callback(toms_button_cb_t cb);

/**
 * @brief Check if button is currently pressed.
 */
bool toms_button_is_pressed(void);

/**
 * @brief Button processing task (FreeRTOS).
 *
 * Handles debouncing and long-press detection.
 * This function never returns.
 */
void toms_button_task(void *arg);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_BUTTON_H */
