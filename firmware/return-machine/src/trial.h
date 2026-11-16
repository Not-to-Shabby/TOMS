/* Trial boot for OTA (firmware/OTA_PLAN.md, phase 2).
 *
 * After MCUboot swaps in a new image it runs it on trial. The image must prove it works and
 * call boot_write_img_confirmed(); if it crashes, hangs, or never confirms, the next reset
 * makes MCUboot swap the old image back.
 *
 * This module reports the image state at boot, runs a self-test, confirms on a pass, and
 * runs a watchdog that is fed only while the monitored tasks are making progress.
 *
 * Bench fault injection (CONFIG_TOMS_TRIAL_FAULT, off in any real build) can make an image
 * crash, hang, or refuse to confirm, to prove the revert works. */
#ifndef TOMS_TRIAL_H
#define TOMS_TRIAL_H

#include <stdbool.h>
#include <stdint.h>

enum trial_task {
	TRIAL_TASK_MAIN = 0,
	TRIAL_TASK_NFC,
	TRIAL_TASK_DISPLAY,
	TRIAL_TASK_COUNT,
};

/* Logs the image state and starts the watchdog and self-test thread. Call once the display,
 * BLE and NFC threads are started. Returns 0, or a negative errno when the watchdog could not
 * be set up (the self-test still runs, but nothing resets a hung image). */
int trial_start(void);

/* A task calls this from its main loop to show it is alive. Cheap and safe from any thread. */
void trial_alive(enum trial_task task);

/* Tell the self-test whether a subsystem came up. */
void trial_report_ble(bool ok);
void trial_report_display(bool ok);

/* True while the image is on trial (not yet confirmed). */
bool trial_pending(void);

#endif /* TOMS_TRIAL_H */
