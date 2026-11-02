/* TOMS Return Machine — main
 *
 * Behavior per firmware/PROTOCOL.md. Drivers come up, BLE advertises, a card
 * read is notified to the phone and checked with a paid_check round trip,
 * with the cached state as the offline fallback. NVS ring persistence,
 * signing and the watchdog land with the spike (firmware/SPIKE.md) and M2. */
#include <zephyr/kernel.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(main, LOG_LEVEL_INF);

#include "ble/svc.h"
#include "buzzer.h"
#include "button.h"
#include "display_test.h"
#include "pn532.h"
#include "reset_reason.h"
#include "toms_pins.h"
#include "trial.h"

#include <toms/codec.h>
#include <toms/state_table.h>

#include <zephyr/sys/printk.h> /* snprintk */

#define NFC_STACK 4096
#define NFC_PRIO 7
static K_THREAD_STACK_DEFINE(nfc_stack, NFC_STACK);
static struct k_thread nfc_thread;

static const struct gpio_dt_spec backlight =
	GPIO_DT_SPEC_GET(TOMS_BACKLIGHT_NODE, gpios);

/* 0 ok, 3 init failed (PROTOCOL.md heartbeat nfc_status). */
static atomic_t nfc_status = ATOMIC_INIT(3);
static uint8_t reset_reason;
static int display_rc = -1;
static int ble_rc = -1;

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

/* Tone for the phone's paid_reply. */
static uint8_t tone_for_verdict(uint8_t verdict)
{
	switch (verdict) {
	case TOMS_VERDICT_OK:
		return TOMS_TONE_OK;
	case TOMS_VERDICT_UNPAID:
		return TOMS_TONE_UNPAID;
	case TOMS_VERDICT_UNKNOWN:
		return TOMS_TONE_UNKNOWN;
	case TOMS_VERDICT_ALREADY_RETURNED:
		return TOMS_TONE_DUPLICATE;
	default:
		return TOMS_TONE_UNVERIFIED;
	}
}

/* Offline fallback (PROTOCOL.md): only a cached paid or returned-unpaid state
 * earns a definite tone; anything else, unknown included, is "unverified". */
static uint8_t tone_for_cached(uint8_t status)
{
	switch (status) {
	case TOMS_STATUS_ASSIGNED_PAID:
		return TOMS_TONE_OK;
	case TOMS_STATUS_RETURNED_UNPAID:
		return TOMS_TONE_UNPAID;
	default:
		return TOMS_TONE_UNVERIFIED;
	}
}

static void play(uint8_t tone)
{
	struct toms_config_msg cfg;

	rm_ble_get_config(&cfg);
	if (cfg.tones_mask & BIT(tone)) {
		buzzer_play(tone);
	}
}

/* A real card from the PN532 and a simulated one from sim_card share this
 * path. The mutex keeps two sources from interleaving a paid check. */
static K_MUTEX_DEFINE(card_lock);

static void on_read(const uint8_t *uid, uint8_t uid_len, uint8_t atype)
{
	k_mutex_lock(&card_lock, K_FOREVER);

	int64_t now = k_uptime_get();
	if (last_uid_len == uid_len && memcmp(last_uid, uid, uid_len) == 0 &&
	    now - last_read_ms < 2000) {
		play(TOMS_TONE_DUPLICATE);
		k_mutex_unlock(&card_lock);
		return;
	}
	memcpy(last_uid, uid, uid_len);
	last_uid_len = uid_len;
	last_read_ms = now;

	uint8_t status = rm_ble_state_lookup(uid, uid_len);
	log_uid("card read", uid, uid_len);
	display_test_set_uid(uid, uid_len, atype);
	LOG_INF("type %u, cached state %u", atype, status);

	struct toms_card_read_msg msg = {
		.read_seq = 0, /* persisted sequence lands with the NVS ring */
		.timestamp = rm_ble_epoch(),
		.boot_ms = (uint32_t)k_uptime_get(),
		.uid_len = uid_len,
		.atype = atype,
		.state_cached = status,
	};
	memcpy(msg.uid, uid, uid_len);

	uint8_t frame[32];
	int len = toms_codec_encode_card_read(frame, sizeof(frame), &msg);
	if (len < 0) {
		LOG_ERR("card_read encode failed (%d)", len);
		play(tone_for_cached(status));
		k_mutex_unlock(&card_lock);
		return;
	}
	int rc = rm_ble_notify_card_read(frame, (size_t)len);
	if (rc == -ENOTCONN) {
		/* Buffer to flash here once the NVS ring exists (spike item 3). */
		LOG_INF("no central; read would be buffered");
	} else if (rc != 0) {
		LOG_WRN("card_read notify failed (%d)", rc);
	}

	/* Ask the phone, and fall back to the cached state when it cannot answer. */
	struct toms_config_msg cfg;
	uint8_t verdict;

	rm_ble_get_config(&cfg);
	rc = rm_ble_paid_check(uid, uid_len, cfg.paid_timeout_ms, &verdict);
	if (rc == 0) {
		LOG_INF("paid_reply: verdict %u", verdict);
		play(tone_for_verdict(verdict));
	} else {
		LOG_INF("no paid_reply (%d); using cached state", rc);
		play(tone_for_cached(status));
	}
	k_mutex_unlock(&card_lock);
}

#if IS_ENABLED(CONFIG_TOMS_DEV_MODE)
/* sim_card arrives on the Bluetooth RX thread, which must not wait for a
 * paid_reply that itself arrives on that thread. Hand the UID to a worker. */
struct sim_item {
	uint8_t uid[TOMS_UID_MAX];
	uint8_t uid_len;
};
K_MSGQ_DEFINE(sim_q, sizeof(struct sim_item), 4, 1);

static K_THREAD_STACK_DEFINE(sim_stack, 2048);
static struct k_thread sim_thread;

static void on_sim_card(const uint8_t *uid, uint8_t uid_len)
{
	struct sim_item it = {.uid_len = uid_len};

	memcpy(it.uid, uid, uid_len);
	if (k_msgq_put(&sim_q, &it, K_NO_WAIT) != 0) {
		LOG_WRN("sim_card dropped: queue full");
	}
}

static void sim_loop(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	for (;;) {
		struct sim_item it;

		k_msgq_get(&sim_q, &it, K_FOREVER);
		LOG_INF("simulated card");
		on_read(it.uid, it.uid_len, TOMS_ATYPE_TYPE2);
	}
}
#endif

static void nfc_loop(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	/* No RSTO is wired: after any bus failure the recovery is re-init. */
	for (;;) {
		trial_alive(TRIAL_TASK_NFC);
		if (pn532_init() != 0) {
			atomic_set(&nfc_status, 3);
			display_test_set_nfc_status(false);
			LOG_ERR("PN532 init failed; retry in 5 s");
			/* Sleep in short steps so the watchdog sees this task is alive. */
			for (int i = 0; i < 50; i++) {
				trial_alive(TRIAL_TASK_NFC);
				k_sleep(K_MSEC(100));
			}
			continue;
		}
		atomic_set(&nfc_status, 0);
		display_test_set_nfc_status(true);
		buzzer_play(TOMS_TONE_BOOT);

		for (;;) {
			struct pn532_target t;
			trial_alive(TRIAL_TASK_NFC);
			int rc = pn532_read_target(&t, K_MSEC(300));
			if (rc == 0) {
				on_read(t.uid, t.uid_len, t.atype);
			} else if (rc == -ETIMEDOUT) {
				/* No card this round: pause so the poll does not hot-loop
				 * (and flood the log) while the field is empty. */
				k_sleep(K_MSEC(150));
				continue;
			} else {
				LOG_WRN("PN532 read error (%d); re-init", rc);
				break;
			}
		}
	}
}

static void send_heartbeat(void)
{
	struct rm_ble_status st;
	rm_ble_get_status(&st);

	struct toms_heartbeat_msg msg = {
		.uptime_s = (uint32_t)(k_uptime_get() / 1000),
		.battery_pct = 0xFF, /* nothing wired to GPIO1 yet */
		.battery_mv = 0,
		.nfc_status = (uint8_t)atomic_get(&nfc_status),
		.state_version = st.state_version,
		.buffered_reads = 0, /* NVS ring not built */
		.reset_reason = reset_reason,
	};
	uint8_t frame[24];
	int len = toms_codec_encode_heartbeat(frame, sizeof(frame), &msg);
	if (len < 0) {
		LOG_ERR("heartbeat encode failed (%d)", len);
		return;
	}
	int rc = rm_ble_notify_heartbeat(frame, (size_t)len);
	if (rc != 0 && rc != -ENOTCONN) {
		LOG_WRN("heartbeat notify failed (%d)", rc);
	}
}

static uint16_t btn_press_count;

static void on_button(void)
{
	btn_press_count++;
	LOG_INF("button pressed (#%u)", (unsigned int)btn_press_count);
	buzzer_play(TOMS_TONE_OK);
	display_test_record_button_press(btn_press_count);
	display_test_button();

	uint8_t ev[4] = { 0x81, (uint8_t)(btn_press_count & 0xFF), (uint8_t)(btn_press_count >> 8), 0x01 };
	rm_ble_notify_diag(ev, sizeof(ev));
}

int main(void)
{
	uint8_t rr = toms_reset_reason();
	reset_reason = rr;
	LOG_INF("TOMS Return Machine boot, reset: %s (%u)", toms_reset_reason_name(rr), rr);
	LOG_INF("reset cause raw 0x%02x: %s%s", (unsigned int)toms_reset_raw(),
		toms_reset_cause_name(toms_reset_raw()),
		toms_reset_is_watchdog(toms_reset_raw()) ? "  <-- WATCHDOG" : "");

	int rc = gpio_pin_configure_dt(&backlight, IS_ENABLED(CONFIG_TOMS_BACKLIGHT_ACTIVE_HIGH)
							      ? GPIO_OUTPUT_ACTIVE
							      : GPIO_OUTPUT_INACTIVE);
	if (rc == 0) {
		LOG_INF("backlight on (polarity: active-%s)",
			IS_ENABLED(CONFIG_TOMS_BACKLIGHT_ACTIVE_HIGH) ? "high" : "low");
	} else {
		LOG_ERR("backlight GPIO failed (%d)", rc);
	}

	display_rc = display_test_init();
	if (display_rc != 0) {
		LOG_ERR("display test init failed (%d)", display_rc);
	}
	trial_report_display(display_rc == 0);

	buzzer_init();
	buzzer_play(TOMS_TONE_BOOT);
	button_init(on_button);

	ble_rc = rm_ble_start();
	if (ble_rc != 0) {
		LOG_ERR("BLE start failed (%d)", ble_rc);
	}
	trial_report_ble(ble_rc == 0);

	k_thread_create(&nfc_thread, nfc_stack, NFC_STACK, nfc_loop, NULL, NULL, NULL, NFC_PRIO, 0,
			K_NO_WAIT);

#if IS_ENABLED(CONFIG_TOMS_DEV_MODE)
	rm_ble_set_sim_card_cb(on_sim_card);
	k_thread_create(&sim_thread, sim_stack, K_THREAD_STACK_SIZEOF(sim_stack), sim_loop, NULL,
			NULL, NULL, NFC_PRIO, 0, K_NO_WAIT);
#endif

	int trial_rc = trial_start();

	if (trial_rc != 0) {
		LOG_ERR("trial/watchdog start failed (%d)", trial_rc);
	}

	uint32_t beat = 0;
	for (;;) {
		struct toms_config_msg cfg;

		rm_ble_get_config(&cfg);
		/* Sleep in short steps so the watchdog sees this task is alive. */
		for (uint32_t s = 0; s < cfg.heartbeat_interval_s * 10u; s++) {
			trial_alive(TRIAL_TASK_MAIN);
			k_sleep(K_MSEC(100));
		}
		beat++;
		LOG_INF("heartbeat #%u: uptime %u s, reset %s (raw 0x%02x), display init %d, "
			"ble start %d, image %s (%s)", beat, (uint32_t)(k_uptime_get() / 1000),
			toms_reset_reason_name(rr), (unsigned int)toms_reset_raw(), display_rc, ble_rc,
			CONFIG_TOMS_BUILD_TAG, trial_pending() ? "ON TRIAL" : "confirmed");
		send_heartbeat();
	}
	return 0;
}
