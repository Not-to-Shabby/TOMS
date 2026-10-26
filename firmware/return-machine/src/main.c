/* TOMS Return Machine — main
 *
 * Behavior per firmware/PROTOCOL.md. M0 skeleton: drivers come up, BLE
 * advertises, reads are logged and notified, cached state drives the tones.
 * Paid check, NVS ring persistence, signing and the watchdog land with the
 * spike (firmware/SPIKE.md) and M2. */
#include <zephyr/kernel.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(main, LOG_LEVEL_INF);

#include "ble/svc.h"
#include "buzzer.h"
#include "button.h"
#include "pn532.h"
#include "reset_reason.h"
#include "toms_pins.h"

#include <toms/codec.h>
#include <toms/state_table.h>

#include <stdio.h>

#define NFC_STACK 4096
#define NFC_PRIO 7
static K_THREAD_STACK_DEFINE(nfc_stack, NFC_STACK);
static struct k_thread nfc_thread;

static const struct gpio_dt_spec backlight =
	GPIO_DT_SPEC_GET(TOMS_BACKLIGHT_NODE, gpios);

static struct toms_state_table state;

/* Read de-duplication: same UID within 2 s is not re-notified (PROTOCOL.md). */
static uint8_t last_uid[TOMS_UID_MAX];
static uint8_t last_uid_len;
static int64_t last_read_ms;

static void log_uid(const char *what, const uint8_t *uid, uint8_t len)
{
	char hex[3 * TOMS_UID_MAX + 1];
	for (uint8_t i = 0; i < len; i++) {
		snprintk(&hex[i * 3], 4, "%02X ", uid[i]);
	}
	hex[len * 3 - 1] = '\0';
	LOG_INF("%s UID [%s] len %u", what, hex, len);
}

static uint8_t tone_for_status(uint8_t status)
{
	switch (status) {
	case TOMS_STATUS_ASSIGNED_PAID:
		return TOMS_TONE_OK;
	case TOMS_STATUS_RETURNED_UNPAID:
		return TOMS_TONE_UNPAID;
	case TOMS_STATUS_UNKNOWN:
		return TOMS_TONE_UNKNOWN;
	default:
		return TOMS_TONE_UNVERIFIED;
	}
}

static void on_read(const struct pn532_target *t)
{
	int64_t now = k_uptime_get();
	if (last_uid_len == t->uid_len && memcmp(last_uid, t->uid, t->uid_len) == 0 &&
	    now - last_read_ms < 2000) {
		buzzer_play(TOMS_TONE_DUPLICATE);
		return;
	}
	memcpy(last_uid, t->uid, t->uid_len);
	last_uid_len = t->uid_len;
	last_read_ms = now;

	uint8_t status = toms_state_table_lookup(&state, t->uid, t->uid_len);
	log_uid("card read", t->uid, t->uid_len);
	LOG_INF("type %u, cached state %u", t->atype, status);
	buzzer_play(tone_for_status(status));

	struct toms_card_read_msg msg = {
		.read_seq = 0, /* persisted sequence lands with the NVS ring */
		.timestamp = 0,
		.boot_ms = (uint32_t)k_uptime_get(),
		.uid_len = t->uid_len,
		.atype = t->atype,
		.state_cached = status,
	};
	memcpy(msg.uid, t->uid, t->uid_len);

	uint8_t frame[32];
	int len = toms_codec_encode_card_read(frame, sizeof(frame), &msg);
	if (len < 0) {
		LOG_ERR("card_read encode failed (%d)", len);
		return;
	}
	int rc = rm_ble_notify_card_read(frame, (size_t)len);
	if (rc == -ENOTCONN) {
		/* Buffer to flash here once the NVS ring exists (spike item 3). */
		LOG_INF("no central; read would be buffered");
	} else if (rc != 0) {
		LOG_WRN("card_read notify failed (%d)", rc);
	}
}

static void nfc_loop(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	/* No RSTO is wired: after any bus failure the recovery is re-init. */
	for (;;) {
		if (pn532_init() != 0) {
			LOG_ERR("PN532 init failed; retry in 5 s");
			k_sleep(K_SECONDS(5));
			continue;
		}
		buzzer_play(TOMS_TONE_BOOT);

		for (;;) {
			struct pn532_target t;
			int rc = pn532_read_target(&t, K_MSEC(300));
			if (rc == 0) {
				on_read(&t);
			} else if (rc == -ETIMEDOUT) {
				continue;
			} else {
				LOG_WRN("PN532 read error (%d); re-init", rc);
				break;
			}
		}
	}
}

static void on_button(void)
{
	LOG_INF("button pressed");
	buzzer_play(TOMS_TONE_OK);
}

int main(void)
{
	uint8_t rr = toms_reset_reason();
	LOG_INF("TOMS Return Machine boot, reset: %s (%u)", toms_reset_reason_name(rr), rr);

	toms_state_table_init(&state);

	int rc = gpio_pin_configure_dt(&backlight, CONFIG_TOMS_BACKLIGHT_ACTIVE_HIGH
							      ? GPIO_OUTPUT_ACTIVE
							      : GPIO_OUTPUT_INACTIVE);
	if (rc == 0) {
		LOG_INF("backlight on (polarity: active-%s, bench test pending)",
			CONFIG_TOMS_BACKLIGHT_ACTIVE_HIGH ? "high" : "low");
	} else {
		LOG_ERR("backlight GPIO failed (%d)", rc);
	}

	buzzer_init();
	buzzer_play(TOMS_TONE_BOOT);
	button_init(on_button);

	rc = rm_ble_start();
	if (rc != 0) {
		LOG_ERR("BLE start failed (%d)", rc);
	}

	k_thread_create(&nfc_thread, nfc_stack, NFC_STACK, nfc_loop, NULL, NULL, NULL, NFC_PRIO, 0,
			K_NO_WAIT);

	uint32_t beat = 0;
	for (;;) {
		k_sleep(K_SECONDS(CONFIG_TOMS_HEARTBEAT_INTERVAL_S));
		beat++;
		LOG_INF("heartbeat #%u: uptime %u s, reset %s", beat, (uint32_t)(k_uptime_get() / 1000),
			toms_reset_reason_name(rr));
	}
	return 0;
}
