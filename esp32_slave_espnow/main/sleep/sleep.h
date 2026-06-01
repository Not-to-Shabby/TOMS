/**
 * @file sleep.h
 * @brief TOMS deep sleep management for the slave node.
 */

#ifndef TOMS_SLEEP_H
#define TOMS_SLEEP_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Idle timeout before entering deep sleep (seconds) */
#define TOMS_SLEEP_IDLE_TIMEOUT_S   30

/** Timer wake-up interval for heartbeat (seconds) */
#define TOMS_SLEEP_TIMER_INTERVAL_S 300   /* 5 minutes */

/**
 * @brief Wake-up cause from the last sleep.
 */
typedef enum {
    TOMS_WAKE_POWER_ON,     /**< Fresh power-on / reset */
    TOMS_WAKE_BUTTON,       /**< External GPIO interrupt (button) */
    TOMS_WAKE_TIMER,        /**< Timer-based wake-up */
    TOMS_WAKE_OTHER,        /**< Unknown cause */
} toms_wake_cause_t;

/**
 * @brief Initialize sleep management.
 *
 * Configures ext0 wake-up on the button GPIO and timer wake-up.
 */
void toms_sleep_init(void);

/**
 * @brief Enter deep sleep.
 *
 * The display should be turned off before calling this.
 * The device will reset on wake-up.
 */
void toms_sleep_enter(void);

/**
 * @brief Get the cause of the last wake-up.
 */
toms_wake_cause_t toms_sleep_get_wake_cause(void);

/**
 * @brief Reset the idle timer (call on any activity).
 */
void toms_sleep_reset_idle(void);

/**
 * @brief Check if idle timeout has elapsed.
 * @return true if device should enter sleep.
 */
bool toms_sleep_should_sleep(void);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_SLEEP_H */
