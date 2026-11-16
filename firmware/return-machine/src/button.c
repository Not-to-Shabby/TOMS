#include "button.h"

#include <zephyr/kernel.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(button, LOG_LEVEL_INF);

#include "toms_pins.h"

#define DEBOUNCE_MS 25

static const struct gpio_dt_spec btn = GPIO_DT_SPEC_GET(TOMS_BUTTON_NODE, gpios);
static struct gpio_callback btn_cb;
static toms_button_cb_t user_cb;

static void debounce_run(struct k_work *work);

static K_WORK_DELAYABLE_DEFINE(debounce_work, debounce_run);

static void debounce_run(struct k_work *work)
{
	ARG_UNUSED(work);

	/* Logical level accounts for GPIO_ACTIVE_LOW: 1 = pressed. */
	if (gpio_pin_get_dt(&btn) == 1 && user_cb != NULL) {
		user_cb();
	}
}

static void edge_fired(const struct device *dev, struct gpio_callback *cb, uint32_t pins)
{
	ARG_UNUSED(dev);
	ARG_UNUSED(cb);
	ARG_UNUSED(pins);
	k_work_reschedule(&debounce_work, K_MSEC(DEBOUNCE_MS));
}

int button_init(toms_button_cb_t cb)
{
	if (!gpio_is_ready_dt(&btn)) {
		LOG_ERR("button GPIO not ready");
		return -ENODEV;
	}
	user_cb = cb;

	int rc = gpio_pin_configure_dt(&btn, GPIO_INPUT);
	if (rc != 0) {
		return rc;
	}
	rc = gpio_pin_interrupt_configure_dt(&btn, GPIO_INT_EDGE_TO_ACTIVE);
	if (rc != 0) {
		return rc;
	}
	gpio_init_callback(&btn_cb, edge_fired, BIT(btn.pin));
	return gpio_add_callback(btn.port, &btn_cb);
}
