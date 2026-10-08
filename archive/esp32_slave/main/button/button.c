/**
 * @file button.c
 * @brief TOMS button input with GPIO interrupt and debounce.
 */

#include "button.h"

#include "esp_log.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "esp_timer.h"

static const char *TAG = "toms_button";

/* ── Internal State ───────────────────────────────────────────────────── */

static toms_button_cb_t    s_callback    = NULL;
static volatile bool       s_irq_flag   = false;
static bool                s_initialized = false;

/* ── ISR ──────────────────────────────────────────────────────────────── */

static void IRAM_ATTR button_isr_handler(void *arg)
{
    (void)arg;
    s_irq_flag = true;
}

/* ── Public API ───────────────────────────────────────────────────────── */

int toms_button_init(void)
{
    if (s_initialized) return 0;

    gpio_config_t io_conf = {
        .pin_bit_mask = (1ULL << TOMS_BUTTON_PIN),
        .mode         = GPIO_MODE_INPUT,
        .pull_up_en   = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type    = GPIO_INTR_NEGEDGE,  /* Active low: press = falling edge */
    };

    esp_err_t err = gpio_config(&io_conf);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "GPIO config failed: %s", esp_err_to_name(err));
        return (int)err;
    }

    err = gpio_install_isr_service(0);
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        return (int)err;
    }

    err = gpio_isr_handler_add(TOMS_BUTTON_PIN, button_isr_handler, NULL);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "ISR handler add failed: %s", esp_err_to_name(err));
        return (int)err;
    }

    s_initialized = true;
    ESP_LOGI(TAG, "Button initialized on GPIO %d", TOMS_BUTTON_PIN);
    return 0;
}

void toms_button_set_callback(toms_button_cb_t cb)
{
    s_callback = cb;
}

bool toms_button_is_pressed(void)
{
    return (gpio_get_level(TOMS_BUTTON_PIN) == 0);  /* Active low */
}

/* ── Button Task ──────────────────────────────────────────────────────── */

void toms_button_task(void *arg)
{
    (void)arg;
    ESP_LOGI(TAG, "Button task started");

    bool     was_pressed = false;
    int64_t  press_time  = 0;
    bool     long_fired  = false;

    while (1) {
        if (s_irq_flag) {
            s_irq_flag = false;

            /* Debounce */
            vTaskDelay(pdMS_TO_TICKS(TOMS_BUTTON_DEBOUNCE_MS));

            if (toms_button_is_pressed() && !was_pressed) {
                was_pressed = true;
                long_fired  = false;
                press_time  = esp_timer_get_time();
                ESP_LOGI(TAG, "Button pressed");
            }
        }

        if (was_pressed) {
            if (!toms_button_is_pressed()) {
                /* Released */
                was_pressed = false;
                int64_t duration = (esp_timer_get_time() - press_time) / 1000;

                if (!long_fired) {
                    ESP_LOGI(TAG, "Short press (%lld ms)", (long long)duration);
                    if (s_callback) s_callback(TOMS_BTN_PRESS);
                }

                if (s_callback) s_callback(TOMS_BTN_RELEASE);

            } else if (!long_fired) {
                /* Still pressed — check for long press */
                int64_t duration = (esp_timer_get_time() - press_time) / 1000;
                if (duration >= TOMS_BUTTON_LONG_PRESS_MS) {
                    long_fired = true;
                    ESP_LOGI(TAG, "Long press detected");
                    if (s_callback) s_callback(TOMS_BTN_LONG_PRESS);
                }
            }
        }

        vTaskDelay(pdMS_TO_TICKS(20));
    }
}
