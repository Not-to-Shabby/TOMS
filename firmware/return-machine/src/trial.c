#include "trial.h"

#include <errno.h>

#include <zephyr/device.h>
#include <zephyr/drivers/watchdog.h>
#include <zephyr/dfu/mcuboot.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/printk.h> /* snprintk */
#include <zephyr/sys/reboot.h>
#include <zephyr/fatal.h>
LOG_MODULE_REGISTER(trial, LOG_LEVEL_INF);

#include <toms/guard.h>

#define CHECK_PERIOD_MS 1000u

static atomic_t alive[TRIAL_TASK_COUNT];
static atomic_t ble_ok;
static atomic_t display_ok;
static atomic_t pending;
static atomic_t last_feed_ms;  /* uptime of the last wdt_feed */
static atomic_t stage0_hits;   /* times the watchdog's first (interrupt) stage fired */

static const char *const task_name[TRIAL_TASK_COUNT] = {"main", "nfc", "display"};

static const struct device *const wdt = DEVICE_DT_GET(DT_ALIAS(watchdog0));
static int wdt_channel = -1;

#define TRIAL_STACK 2048
#define TRIAL_PRIO 9
static K_THREAD_STACK_DEFINE(trial_stack, TRIAL_STACK);
static struct k_thread trial_thread;

void trial_alive(enum trial_task task)
{
	if ((unsigned int)task < TRIAL_TASK_COUNT) {
		atomic_inc(&alive[task]);
	}
}

void trial_report_ble(bool ok)
{
	atomic_set(&ble_ok, ok ? 1 : 0);
}

void trial_report_display(bool ok)
{
	atomic_set(&display_ok, ok ? 1 : 0);
}

bool trial_pending(void)
{
	return atomic_get(&pending) != 0;
}

/* Stage 0 of the watchdog raises an interrupt before stage 1 resets the chip. It is the one
 * warning there is, so say what was going on. Runs in interrupt context: only log and count. */
static void wdt_stage0(const struct device *dev, int channel)
{
	ARG_UNUSED(dev);
	ARG_UNUSED(channel);
	atomic_inc(&stage0_hits);
	LOG_ERR("WATCHDOG STAGE 0 FIRED: %u ms since the last feed, the reset follows if nothing feeds",
		(unsigned int)((uint32_t)k_uptime_get() - (uint32_t)atomic_get(&last_feed_ms)));
}

static int watchdog_start(void)
{
	if (!device_is_ready(wdt)) {
		LOG_ERR("watchdog device not ready");
		return -ENODEV;
	}

	struct wdt_timeout_cfg cfg = {
		.window.min = 0U,
		.window.max = CONFIG_TOMS_WATCHDOG_TIMEOUT_MS,
		.callback = wdt_stage0,
		.flags = WDT_FLAG_RESET_SOC,
	};

	wdt_channel = wdt_install_timeout(wdt, &cfg);
	if (wdt_channel < 0) {
		LOG_ERR("wdt_install_timeout failed (%d)", wdt_channel);
		return wdt_channel;
	}
	int rc = wdt_setup(wdt, WDT_OPT_PAUSE_HALTED_BY_DBG);

	if (rc != 0) {
		LOG_ERR("wdt_setup failed (%d)", rc);
		return rc;
	}
	atomic_set(&last_feed_ms, (atomic_val_t)k_uptime_get());
	LOG_INF("watchdog armed: channel %d, timeout value %u (driver units, to be measured below), "
		"action reset-SoC, stage-0 warning on, device %s", wdt_channel,
		(unsigned int)CONFIG_TOMS_WATCHDOG_TIMEOUT_MS, wdt->name);
	return 0;
}

static void trial_loop(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	struct toms_selftest st;
	struct toms_liveness lv;
	bool trial = atomic_get(&pending) != 0;

	uint32_t prev_snap[TRIAL_TASK_COUNT] = {0};
	uint32_t stalled_checks = 0, feeds = 0, last_report = 0;

	toms_selftest_init(&st, CONFIG_TOMS_SELFTEST_GOOD_CHECKS, CONFIG_TOMS_SELFTEST_DEADLINE_MS);
	toms_liveness_init(&lv);

	for (;;) {
		k_sleep(K_MSEC(CHECK_PERIOD_MS));
		uint32_t now = (uint32_t)k_uptime_get();

		uint32_t snap[TRIAL_TASK_COUNT];

		for (size_t i = 0; i < TRIAL_TASK_COUNT; i++) {
			snap[i] = (uint32_t)atomic_get(&alive[i]);
		}
		bool moving = toms_liveness_check(&lv, snap, TRIAL_TASK_COUNT);
		bool lv_moved[TRIAL_TASK_COUNT];

		for (size_t i = 0; i < TRIAL_TASK_COUNT; i++) {
			lv_moved[i] = snap[i] != prev_snap[i];
			prev_snap[i] = snap[i];
		}

		/* Feed only while every monitored task is running. */
		if (wdt_channel >= 0 && moving) {
			wdt_feed(wdt, wdt_channel);
			atomic_set(&last_feed_ms, (atomic_val_t)now);
			stalled_checks = 0;
			feeds++;
		} else if (wdt_channel >= 0) {
			stalled_checks++;
			/* Name the task that stopped, once at the start and then every 5 checks. */
			if (stalled_checks == 1 || stalled_checks % 5 == 0) {
				char who[48] = "";
				size_t n = 0;

				for (size_t i = 0; i < TRIAL_TASK_COUNT; i++) {
					if (!lv_moved[i]) {
						n += (size_t)snprintk(who + n, sizeof(who) - n, "%s%s",
								      n ? "," : "", task_name[i]);
					}
				}
				LOG_WRN("NOT FEEDING the watchdog (%u checks in a row): no progress from [%s]; "
					"%u ms since the last feed", stalled_checks, who,
					(unsigned int)(now - (uint32_t)atomic_get(&last_feed_ms)));
			}
		}
		if (now - last_report >= 10000u) {
			last_report = now;
			LOG_INF("watchdog: %u feeds, %u ms since the last, stage-0 fired %ld times",
				feeds, (unsigned int)(now - (uint32_t)atomic_get(&last_feed_ms)),
				(long)atomic_get(&stage0_hits));
		}

		if (!trial) {
			continue;
		}
		bool healthy = moving && atomic_get(&ble_ok) && atomic_get(&display_ok);
#ifdef CONFIG_TOMS_TRIAL_FAULT_SELFTEST_FAIL
		healthy = false;
#endif

		switch (toms_selftest_tick(&st, now, healthy)) {
		case TOMS_SELFTEST_PASSED:
#ifdef CONFIG_TOMS_TRIAL_FAULT_NO_CONFIRM
			LOG_WRN("FAULT INJECTION: self-test passed but the image will NOT confirm");
			trial = false;
#else
			if (boot_write_img_confirmed() == 0) {
				LOG_INF("self-test passed: image CONFIRMED, trial over");
			} else {
				LOG_ERR("boot_write_img_confirmed failed, image stays on trial");
			}
			atomic_set(&pending, 0);
			trial = false;
#endif
			break;
		case TOMS_SELFTEST_FAILED:
			LOG_ERR("self-test FAILED (ble %ld, display %ld, tasks moving %d): resetting so "
				"MCUboot restores the previous image",
				(long)atomic_get(&ble_ok), (long)atomic_get(&display_ok), moving);
			k_sleep(K_MSEC(200)); /* let the log drain */
			sys_reboot(SYS_REBOOT_COLD);
			break;
		default:
			break;
		}
	}
}

/* A crash must end in a reset, not a spin: the default handler halts, and an image that
 * halts with nothing watching it never lets MCUboot restore the old one. */
void k_sys_fatal_error_handler(unsigned int reason, const struct arch_esf *esf)
{
	ARG_UNUSED(esf);
	LOG_ERR("FATAL error %u: rebooting", reason);
	k_busy_wait(200000); /* let the log drain */
	sys_reboot(SYS_REBOOT_COLD);
}

int trial_start(void)
{
	bool confirmed = boot_is_img_confirmed();

	atomic_set(&pending, confirmed ? 0 : 1);
	LOG_INF("build tag: %s", CONFIG_TOMS_BUILD_TAG);
	LOG_INF("image state: %s (booted by MCUboot)",
		confirmed ? "CONFIRMED" : "ON TRIAL, will revert unless confirmed");

	/* Armed first, so even a fault injected below is covered. */
	int rc = watchdog_start();

#if defined(CONFIG_TOMS_TRIAL_FAULT_CRASH)
	if (!confirmed) {
		LOG_WRN("FAULT INJECTION: crashing now");
		k_sleep(K_MSEC(500));
		volatile int *p = NULL;

		*p = 1;
	}
#elif defined(CONFIG_TOMS_TRIAL_FAULT_HANG)
	if (!confirmed) {
		LOG_WRN("FAULT INJECTION: hanging now at uptime %u ms with the watchdog armed (rc %d) and "
			"interrupts locked, so no further log line can appear; the reset should follow",
			(unsigned int)k_uptime_get(), rc);
		k_sleep(K_MSEC(300));
		unsigned int key = irq_lock();

		for (;;) {
			/* never feeds, never yields */
		}
		irq_unlock(key);
	}
#endif

	k_thread_create(&trial_thread, trial_stack, TRIAL_STACK, trial_loop, NULL, NULL, NULL,
			TRIAL_PRIO, 0, K_NO_WAIT);
	k_thread_name_set(&trial_thread, "trial");
	return rc;
}
