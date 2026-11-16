#include "buzzer.h"

#include <zephyr/kernel.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(buzzer, LOG_LEVEL_INF);

#include "toms_pins.h"

static const struct gpio_dt_spec bz = GPIO_DT_SPEC_GET(TOMS_BUZZER_NODE, gpios);

struct tone_step {
	uint16_t on_ms;
	uint16_t off_ms;
};

/* {on, off} pairs, terminated by {0, 0} */
static const struct tone_step patterns[][8] = {
	[TOMS_TONE_BOOT] = { { 30, 60 }, { 30, 60 }, { 30, 0 }, { 0, 0 } },
	[TOMS_TONE_OK] = { { 80, 0 }, { 0, 0 } },
	[TOMS_TONE_UNPAID] = { { 200, 150 }, { 200, 150 }, { 200, 0 }, { 0, 0 } },
	[TOMS_TONE_UNKNOWN] = { { 300, 100 }, { 300, 0 }, { 0, 0 } },
	[TOMS_TONE_UNVERIFIED] = { { 60, 120 }, { 60, 0 }, { 0, 0 } },
	[TOMS_TONE_DUPLICATE] = { { 400, 0 }, { 0, 0 } },
};

static const struct tone_step *current;
static uint8_t step;
static bool sounding;
static bool alarm_active;
static int64_t alarm_deadline_ms;

static void step_run(struct k_work *work);

static K_WORK_DELAYABLE_DEFINE(step_work, step_run);

static void step_run(struct k_work *work)
{
	ARG_UNUSED(work);

	if (current == NULL) {
		return;
	}

	if (sounding) {
		/* On phase elapsed: silence, then move to the next step. */
		gpio_pin_set_dt(&bz, 0);
		sounding = false;

		if (alarm_active) {
			if (k_uptime_get() >= alarm_deadline_ms) {
				LOG_INF("buzzer: unpaid alarm auto-timeout after 30s");
				alarm_active = false;
				current = NULL;
				return;
			}
			/* Unpaid continuous alarm pattern: 200ms ON, 150ms OFF */
			k_work_reschedule(&step_work, K_MSEC(150));
			return;
		}

		if (current[step + 1].on_ms == 0) {
			current = NULL;
			return;
		}
		uint16_t off = current[step].off_ms;
		step++;
		if (off == 0) {
			step_run(work); /* immediate next beep */
		} else {
			k_work_reschedule(&step_work, K_MSEC(off));
		}
		return;
	}

	gpio_pin_set_dt(&bz, 1);
	sounding = true;
	uint16_t on_time = alarm_active ? 200 : current[step].on_ms;
	k_work_reschedule(&step_work, K_MSEC(on_time));
}

void buzzer_play(enum toms_tone tone)
{
	if (tone >= ARRAY_SIZE(patterns)) {
		return;
	}
	k_work_cancel_delayable(&step_work);
	alarm_active = false;
	gpio_pin_set_dt(&bz, 0);
	sounding = false;
	current = patterns[tone];
	step = 0;
	step_run(&step_work.work);
}

void buzzer_alarm_start(uint32_t timeout_ms)
{
	k_work_cancel_delayable(&step_work);
	gpio_pin_set_dt(&bz, 0);
	sounding = false;
	alarm_active = true;
	alarm_deadline_ms = k_uptime_get() + (timeout_ms > 0 ? timeout_ms : 30000u);
	current = patterns[TOMS_TONE_UNPAID];
	step = 0;
	LOG_INF("buzzer: continuous unpaid alarm started (timeout %u ms)", (unsigned int)timeout_ms);
	step_run(&step_work.work);
}

void buzzer_alarm_stop(void)
{
	if (alarm_active || sounding) {
		LOG_INF("buzzer: alarm stopped / silenced");
	}
	alarm_active = false;
	k_work_cancel_delayable(&step_work);
	gpio_pin_set_dt(&bz, 0);
	sounding = false;
	current = NULL;
}

bool buzzer_is_alarming(void)
{
	return alarm_active;
}

void buzzer_init(void)
{
	if (!gpio_is_ready_dt(&bz)) {
		LOG_ERR("buzzer GPIO not ready");
		return;
	}
	gpio_pin_configure_dt(&bz, GPIO_OUTPUT_INACTIVE);
}
