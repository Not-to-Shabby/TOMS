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
#include "battery.h"
#include "readstore.h"
#include "reset_reason.h"
#include "toms_pins.h"
#include "trial.h"

#include <toms/bondclr.h>
#include <toms/codec.h>
#include <toms/state_table.h>
#include <toms/wire.h>

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

/* Read de-duplication and slot presence tracking */
enum card_presence {
	PRESENCE_EMPTY = 0,
	PRESENCE_SEATED = 1,
};

static enum card_presence slot_presence = PRESENCE_EMPTY;
static uint8_t seated_uid[TOMS_UID_MAX];
static uint8_t seated_uid_len;
static int64_t last_seen_ms;
static int64_t card_removed_ms;
static uint8_t last_removed_uid[TOMS_UID_MAX];
static uint8_t last_removed_len;

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

#define ALARM_MS (CONFIG_TOMS_ALARM_TIMEOUT_S * 1000u) /* 0 = until the phone or button clears */

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

/* Wakes the flush thread: a read was stored, or a phone connected. */
static K_SEM_DEFINE(flush_wake, 0, 1);

/* How the paid check ended, as the phone is told in a v2 card_read. */
static uint8_t outcome_for(int check_rc)
{
	if (check_rc == 0) {
		return TOMS_OUTCOME_NORMAL;
	}
	if (check_rc == -ETIMEDOUT) {
		return TOMS_OUTCOME_UNVERIFIED_TIMEOUT; /* sent, no reply in time */
	}
	return TOMS_OUTCOME_UNVERIFIED_NO_LINK; /* could not be sent: no phone, not subscribed, notify failed */
}

/* One live notify of a read in the given layout. 0 or the notify error. */
static int deliver_live(const struct toms_card_read_msg *msg, uint8_t wire)
{
	uint8_t frame[32];
	int len = toms_codec_encode_card_read_wire(frame, sizeof(frame), msg, wire);

	if (len < 0) {
		LOG_ERR("card_read encode failed (%d)", len);
		return len;
	}
	return rm_ble_notify_card_read(frame, (size_t)len);
}

static void on_read(const uint8_t *uid, uint8_t uid_len, uint8_t atype)
{
	k_mutex_lock(&card_lock, K_FOREVER);

	uint8_t status = rm_ble_state_lookup(uid, uid_len);
	log_uid("card read", uid, uid_len);
	LOG_INF("type %u, cached state %u", atype, status);

	int32_t seq = readstore_take_seq();
	struct toms_card_read_msg msg = {
		.read_seq = seq > 0 ? (uint32_t)seq : 0,
		.timestamp = rm_ble_epoch(),
		.boot_ms = (uint32_t)k_uptime_get(),
		.uid_len = uid_len,
		.atype = atype,
		.state_cached = status,
	};
	memcpy(msg.uid, uid, uid_len);

	/* Wire v1 (what the shipped phone parses): the read goes out before the paid check, exactly
	 * as before, and only when no older read is waiting so the phone sees them in read_seq
	 * order. The outcome is not on the v1 wire. */
	uint8_t wire = rm_ble_wire_version();
	bool delivered = false;

	if (seq > 0 && wire == TOMS_WIRE_V1 && readstore_unsent() == 0) {
		delivered = deliver_live(&msg, TOMS_WIRE_V1) == 0;
	}

	/* Ask the phone. */
	struct toms_config_msg cfg;
	uint8_t verdict;

	rm_ble_get_config(&cfg);
	int rc = rm_ble_paid_check(uid, uid_len, cfg.paid_timeout_ms, &verdict);

	msg.outcome = outcome_for(rc);
	if (rc == 0) {
		LOG_INF("paid_reply: verdict %u", verdict);
		if (verdict == TOMS_VERDICT_UNPAID) {
			buzzer_alarm_start(ALARM_MS); /* held until the phone or the button clears it */
		} else {
			play(tone_for_verdict(verdict));
		}
		display_set_verdict(verdict, uid, uid_len, atype);
	} else {
		/* No answer from the phone: the read is unverified. Never the full alarm, whatever
		 * the cached state says; the cache only goes to the phone in card_read. */
		LOG_INF("no paid_reply (%d): unverified, outcome %u (cached state %u)", rc,
			msg.outcome, status);
		play(TOMS_TONE_UNVERIFIED);
		display_set_verdict(0xFF, uid, uid_len, atype);
	}

	/* Wire v2: the read follows the check, so it can say how the check ended. If it cannot go
	 * out live (an older read is waiting, the notify fails, no phone), it is stored in flash
	 * with its outcome and the flush thread sends it, in read_seq order. */
	if (seq > 0 && !delivered && wire == TOMS_WIRE_V2 && readstore_unsent() == 0) {
		delivered = deliver_live(&msg, rm_ble_wire_version()) == 0;
	}
	if (delivered) {
		readstore_mark_sent(msg.read_seq);
	} else if (seq > 0) {
		int krc = readstore_keep(&msg);

		LOG_INF("read %u stored in flash (outcome %u, store %d), %u pending",
			(unsigned int)msg.read_seq, msg.outcome, krc,
			(unsigned int)readstore_pending());
		k_sem_give(&flush_wake);
	} else {
		LOG_ERR("no read_seq (%d): read not recorded", (int)seq);
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

/* Sends stored reads to the phone, oldest first, whenever it is connected and subscribed. A read
 * stays in flash until the phone acks it (rm "ack" kind 2), so a drop in the middle loses
 * nothing: the next connection offers everything not yet acked again. */
#define FLUSH_STACK 3072
#define FLUSH_PRIO 8
static K_THREAD_STACK_DEFINE(flush_stack, FLUSH_STACK);
static struct k_thread flush_thread;

static void flush_loop(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);
	bool was_up = false;

	for (;;) {
		(void)k_sem_take(&flush_wake, K_MSEC(1000));
		bool up = rm_ble_card_subscribed();

		if (up && !was_up) {
			readstore_rewind(); /* new link: whatever is not acked goes out again */
		}
		was_up = up;

		while (up) {
			uint8_t fr[32];
			uint32_t seq;
			uint8_t oc = TOMS_OUTCOME_NORMAL;

			k_mutex_lock(&card_lock, K_FOREVER);
			int n = readstore_next_unsent(fr, sizeof(fr), &seq, &oc);

			/* Stored frames are v1; this connection's layout is applied when sending. */
			if (n > 0) {
				n = toms_codec_card_read_to_wire(fr, sizeof(fr), (size_t)n, oc,
								 rm_ble_wire_version());
			}
			if (n > 0 && rm_ble_notify_card_read(fr, (size_t)n) == 0) {
				readstore_mark_sent(seq);
				k_mutex_unlock(&card_lock);
				k_sleep(K_MSEC(30)); /* leave room for the paid_check traffic */
				continue;
			}
			k_mutex_unlock(&card_lock);
			break;
		}
	}
}

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
				int64_t now = k_uptime_get();

				if (rc == 0) {
					last_seen_ms = now;

					/* If this exact card is already seated in the slot, ignore continuous re-reads */
					if (slot_presence == PRESENCE_SEATED &&
					    seated_uid_len == t.uid_len &&
					    memcmp(seated_uid, t.uid, t.uid_len) == 0) {
						/* Card is resting in the slot: no-op, suppress duplicates */
						k_sleep(K_MSEC(100));
						continue;
					}

					/* Card is newly detected in slot */
					slot_presence = PRESENCE_SEATED;
					memcpy(seated_uid, t.uid, t.uid_len);
					seated_uid_len = t.uid_len;

					/* Check if this card was recently removed within duplicate window (5.0s) */
					if (last_removed_len == t.uid_len &&
					    memcmp(last_removed_uid, t.uid, t.uid_len) == 0 &&
					    now - card_removed_ms < 5000) {
						LOG_INF("card re-inserted within 5s -> DUPLICATE");
						play(TOMS_TONE_DUPLICATE);
						display_set_verdict(TOMS_VERDICT_ALREADY_RETURNED, t.uid, t.uid_len, t.atype);
					} else {
						/* Fresh card insertion */
						on_read(t.uid, t.uid_len, t.atype);
					}
				} else if (rc == -ETIMEDOUT) {
					/* No card in RF field this round */
					if (slot_presence == PRESENCE_SEATED) {
						/* Check if card has been absent for > 400ms */
						if (now - last_seen_ms >= 400) {
							LOG_INF("card removed from slot");
							slot_presence = PRESENCE_EMPTY;
							card_removed_ms = now;
							memcpy(last_removed_uid, seated_uid, seated_uid_len);
							last_removed_len = seated_uid_len;
							seated_uid_len = 0;
						}
					}
					k_sleep(K_MSEC(100));
					continue;
				} else {
					LOG_WRN("PN532 read error (%d); re-init", rc);
					slot_presence = PRESENCE_EMPTY;
					seated_uid_len = 0;
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
		.battery_pct = 0xFF,
		.battery_mv = 0,
		.nfc_status = (uint8_t)atomic_get(&nfc_status),
		.state_version = st.state_version,
		.buffered_reads = (uint16_t)readstore_pending(),
		.dropped_reads = toms_u16_sat(readstore_dropped()),
		.late_replies = rm_ble_late_replies(),
		.reset_reason = reset_reason,
	};
	battery_read(&msg.battery_pct, &msg.battery_mv);

	uint8_t frame[24];
	int len = toms_codec_encode_heartbeat_wire(frame, sizeof(frame), &msg, rm_ble_wire_version());
	if (len < 0) {
		LOG_ERR("heartbeat encode failed (%d)", len);
		return;
	}
	int rc = rm_ble_notify_heartbeat(frame, (size_t)len);
	if (rc != 0 && rc != -ENOTCONN) {
		LOG_WRN("heartbeat notify failed (%d)", rc);
	}
}

/* Set while the 5 s hold is being counted. A bounce of the held button must not run the normal button
 * actions (long-press diagnostic mode, pairing confirm) in the middle of it. */
static atomic_t bondclr_counting = ATOMIC_INIT(0);

static uint16_t btn_press_count;

static void on_button(void)
{
	if (atomic_get(&bondclr_counting) != 0) {
		return; /* the power-up hold is being counted; a bounce is not a press */
	}
	btn_press_count++;
	LOG_INF("button pressed (#%u)", (unsigned int)btn_press_count);
	if (buzzer_is_alarming() || display_is_alarming()) {
		LOG_INF("button pressed: silencing unpaid alarm");
		buzzer_alarm_stop();
		display_clear_alarm();
		display_test_record_button_press(btn_press_count);
		return;
	}
	buzzer_play(TOMS_TONE_OK);
	display_test_record_button_press(btn_press_count);
	display_test_button();

	uint8_t ev[4] = { 0x81, (uint8_t)(btn_press_count & 0xFF), (uint8_t)(btn_press_count >> 8), 0x01 };
	rm_ble_notify_diag(ev, sizeof(ev));
}

/* Bond clear: hold the button at power-up (the pairing window opens), keep holding for 5 s, and the
 * one stored bond is forgotten; the signing key is kept. The hold is tracked by a thread that starts
 * at power-up, before the slow parts of boot, and it gives up for good on a release or a missed
 * sample (toms/bondclr.h). The bond itself can only be removed once Bluetooth is up. */
static K_SEM_DEFINE(ble_ready, 0, 1);
#define BONDCLR_STACK 1536
#define BONDCLR_PRIO 6
static K_THREAD_STACK_DEFINE(bondclr_stack, BONDCLR_STACK);
static struct k_thread bondclr_thread;

static void bondclr_loop(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	struct toms_bondclr s;

	toms_bondclr_init(&s, (uint32_t)k_uptime_get(), true);
	atomic_set(&bondclr_counting, 1);
	while (toms_bondclr_active(&s)) {
		k_msleep(TOMS_BONDCLR_POLL_MS);
		if (toms_bondclr_tick(&s, (uint32_t)k_uptime_get(), button_pressed_now()) !=
		    TOMS_BONDCLR_FIRE) {
			continue;
		}
		atomic_set(&bondclr_counting, 0);
		LOG_WRN("button held %u s from power-up: clearing the bond", TOMS_BONDCLR_HOLD_MS / 1000u);
		if (k_sem_take(&ble_ready, K_SECONDS(10)) != 0) {
			LOG_ERR("Bluetooth is not up: the bond was NOT cleared");
			return;
		}
		int left = rm_ble_forget_bond();

		if (left != 0) {
			LOG_ERR("the bond was NOT cleared (%d)", left);
			return;
		}
		display_show_bond_cleared(TOMS_BONDCLR_SHOW_MS);
		buzzer_play(TOMS_TONE_OK);
		k_msleep(TOMS_BONDCLR_SHOW_MS);
		rm_ble_open_pairing(); /* a fresh window, counted from now */
		return;
	}
	atomic_set(&bondclr_counting, 0);
	LOG_INF("bond-clear hold ended before %u s: nothing cleared", TOMS_BONDCLR_HOLD_MS / 1000u);
}

int main(void)
{
	/* Pairing window: only if the button is held at power-up. Sampled first, before anything else
	 * can take time, and acted on once Bluetooth is up. */
	bool pairing_requested = button_is_held();

	if (pairing_requested) {
		k_thread_create(&bondclr_thread, bondclr_stack, BONDCLR_STACK, bondclr_loop, NULL, NULL,
				NULL, BONDCLR_PRIO, 0, K_NO_WAIT);
		k_thread_name_set(&bondclr_thread, "bondclr");
	}

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

	int store_rc = readstore_init();

	if (store_rc != 0) {
		LOG_ERR("read store init failed (%d): reads cannot be buffered", store_rc);
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
	if (ble_rc == 0 && pairing_requested) {
		rm_ble_open_pairing();
	} else if (pairing_requested) {
		LOG_ERR("button held at power-up but Bluetooth did not start: no pairing window");
	}
	if (ble_rc == 0) {
		k_sem_give(&ble_ready); /* after the window is open: a bond clear then closes it, not the reverse */
	}

	k_thread_create(&flush_thread, flush_stack, FLUSH_STACK, flush_loop, NULL, NULL, NULL,
			FLUSH_PRIO, 0, K_NO_WAIT);
	k_thread_name_set(&flush_thread, "flush");

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
			/* The BLE task counts as alive only while the controller answers an HCI command. */
			if (s % 5u == 0u && ble_rc == 0 && rm_ble_probe() == 0) {
				trial_alive(TRIAL_TASK_BLE);
			}
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
