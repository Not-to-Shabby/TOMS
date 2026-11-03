#include "svc.h"
#include "ota.h"
#include "ota_ui.h"
#include "buzzer.h"
#include "display_test.h"
#include "pn532.h"

#include <errno.h>
#include <string.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/settings/settings.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/printk.h> /* snprintk */
LOG_MODULE_REGISTER(ble_svc, LOG_LEVEL_INF);

#include <toms/clock.h>
#include <toms/codec.h>
#include <toms/state_table.h>

#define TOMS_FW_MAJOR 0
#define TOMS_FW_MINOR 1
#define TOMS_FW_PATCH 0

/* UUID base "TOMS RETURN MACHINE": 544F4D53-5245-5455-524E-414348494Exx,
 * where xx is the characteristic suffix (see PROTOCOL.md). */
#define TOMS_UUID(suffix) \
	BT_UUID_128_ENCODE(0x544F4D53, 0x5245, 0x5455, 0x524E, (0x414348494E00ull | (suffix)))
#define TOMS_UUID_SVC TOMS_UUID(0x45)

/* Writes need an encrypted (bonded) link in a field build. */
#define WRITE_PERM (IS_ENABLED(CONFIG_TOMS_DEV_MODE) ? BT_GATT_PERM_WRITE : BT_GATT_PERM_WRITE_ENCRYPT)

#define CHAR_STATE_UPDATE 0x02
#define CHAR_PAID_REPLY 0x04
#define CHAR_TIME_SYNC 0x06
#define CHAR_CONFIG 0x07
#define CHAR_ACK 0x08
#define CHAR_SIM_CARD 0x0B
#define CHAR_OTA_CONTROL 0x0C
#define CHAR_OTA_DATA 0x0D
#define CHAR_DIAG 0x0E

#define ATT_HEADER 3 /* opcode + handle */

static struct bt_conn *current_conn;
static char adv_name[24];
static bool advertising;

static bool card_notify;
static bool check_notify;
static bool hb_notify;
static bool ack_notify;
static bool ota_notify;
static bool diag_notify;
static rm_ble_diag_cmd_cb_t diag_cb;

static uint32_t rx_writes;
static uint32_t rx_rejected;
static uint8_t last_write_char;
static uint32_t tx_card;
static uint32_t tx_heartbeat;
static uint32_t state_acked;
static uint32_t read_acked;

static struct toms_state_table table;
static struct toms_config_msg cfg;
static struct toms_clock rm_clock;
static K_MUTEX_DEFINE(table_lock);

static rm_ble_sim_card_cb_t sim_cb;

/* paid_check / paid_reply rendezvous */
static K_SEM_DEFINE(reply_sem, 0, 1);
static atomic_t check_seq_counter;
static volatile bool waiting_reply;
static volatile uint32_t pending_seq;
static volatile uint8_t reply_verdict;
static uint32_t paid_sent;
static uint32_t paid_replied;
static uint32_t paid_timeouts;

static ssize_t write_handler(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			     const void *buf, uint16_t len, uint16_t offset, uint8_t flags);
static ssize_t read_info(struct bt_conn *conn, const struct bt_gatt_attr *attr, void *buf,
			 uint16_t len, uint16_t offset);

static void ccc_card(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	card_notify = (value & BT_GATT_CCC_NOTIFY) != 0;
	LOG_INF("card_read notifications %s", card_notify ? "on" : "off");
}

static void ccc_check(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	check_notify = (value & BT_GATT_CCC_NOTIFY) != 0;
	LOG_INF("paid_check notifications %s", check_notify ? "on" : "off");
}

static void ccc_heartbeat(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	hb_notify = (value & BT_GATT_CCC_NOTIFY) != 0;
	LOG_INF("heartbeat notifications %s", hb_notify ? "on" : "off");
}

static void ccc_ota(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	ota_notify = (value & BT_GATT_CCC_NOTIFY) != 0;
	LOG_INF("ota_control notifications %s", ota_notify ? "on" : "off");
}

static void ccc_diag(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	diag_notify = (value & BT_GATT_CCC_NOTIFY) != 0;
	LOG_INF("diag notifications %s", diag_notify ? "on" : "off");
}

static void ccc_ack(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	ack_notify = (value & BT_GATT_CCC_NOTIFY) != 0;
	LOG_INF("rm_ack notifications %s", ack_notify ? "on" : "off");
}

BT_GATT_SERVICE_DEFINE(rm_svc,
	BT_GATT_PRIMARY_SERVICE(BT_UUID_DECLARE_128(TOMS_UUID_SVC)),
	/* 01 card_read: RM -> phone, notify */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x01)),
		BT_GATT_CHRC_NOTIFY, BT_GATT_PERM_READ, NULL, NULL, NULL),
	BT_GATT_CCC(ccc_card, BT_GATT_PERM_READ | WRITE_PERM),
	/* 02 state_update: phone -> RM, write */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x02)),
		BT_GATT_CHRC_WRITE | BT_GATT_CHRC_WRITE_WITHOUT_RESP,
		WRITE_PERM, NULL, write_handler, (void *)CHAR_STATE_UPDATE),
	/* 03 paid_check: RM -> phone, notify (the RM asks, the phone answers on 04) */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x03)),
		BT_GATT_CHRC_NOTIFY, BT_GATT_PERM_READ, NULL, NULL, NULL),
	BT_GATT_CCC(ccc_check, BT_GATT_PERM_READ | WRITE_PERM),
	/* 04 paid_reply: phone -> RM, write */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x04)),
		BT_GATT_CHRC_WRITE | BT_GATT_CHRC_WRITE_WITHOUT_RESP,
		WRITE_PERM, NULL, write_handler, (void *)CHAR_PAID_REPLY),
	/* 05 heartbeat: RM -> phone, notify */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x05)),
		BT_GATT_CHRC_NOTIFY, BT_GATT_PERM_READ, NULL, NULL, NULL),
	BT_GATT_CCC(ccc_heartbeat, BT_GATT_PERM_READ | WRITE_PERM),
	/* 06 time_sync: phone -> RM, write */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x06)),
		BT_GATT_CHRC_WRITE, WRITE_PERM, NULL, write_handler, (void *)CHAR_TIME_SYNC),
	/* 07 config: phone -> RM, write */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x07)),
		BT_GATT_CHRC_WRITE, WRITE_PERM, NULL, write_handler, (void *)CHAR_CONFIG),
	/* 08 ack: phone -> RM, write */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x08)),
		BT_GATT_CHRC_WRITE, WRITE_PERM, NULL, write_handler, (void *)CHAR_ACK),
	/* 09 device_info: read, no pairing needed so the app can check the
	 * protocol version before it bonds */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x09)),
		BT_GATT_CHRC_READ, BT_GATT_PERM_READ, read_info, NULL, NULL),
	/* 0A rm_ack: RM -> phone, notify (answers state_update) */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x0A)),
		BT_GATT_CHRC_NOTIFY, BT_GATT_PERM_READ, NULL, NULL, NULL),
	BT_GATT_CCC(ccc_ack, BT_GATT_PERM_READ | WRITE_PERM),
	/* 0B sim_card: phone -> RM, write (dev mode only) */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x0B)),
		BT_GATT_CHRC_WRITE, WRITE_PERM, NULL, write_handler, (void *)CHAR_SIM_CARD),
	/* 0C ota_control: phone -> RM write, RM -> phone notify (firmware update, ota.c) */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x0C)),
		BT_GATT_CHRC_WRITE | BT_GATT_CHRC_NOTIFY, WRITE_PERM, NULL, write_handler,
		(void *)CHAR_OTA_CONTROL),
	BT_GATT_CCC(ccc_ota, BT_GATT_PERM_READ | WRITE_PERM),
		/* 0D ota_data: phone -> RM, write without response (image chunks) */
		BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x0D)),
			BT_GATT_CHRC_WRITE_WITHOUT_RESP, WRITE_PERM, NULL, write_handler,
			(void *)CHAR_OTA_DATA),
		/* 0E diag: phone -> RM write/notify (diagnostic tools: buzzer, screen, nfc, button) */
		BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x0E)),
			BT_GATT_CHRC_WRITE | BT_GATT_CHRC_WRITE_WITHOUT_RESP | BT_GATT_CHRC_NOTIFY,
			WRITE_PERM, NULL, write_handler, (void *)CHAR_DIAG),
		BT_GATT_CCC(ccc_diag, BT_GATT_PERM_READ | WRITE_PERM),
	);

static int notify_uuid(const struct bt_uuid *uuid, bool subscribed, const uint8_t *data,
		       size_t len)
{
	if (current_conn == NULL || !subscribed) {
		return -ENOTCONN;
	}
	if (len + ATT_HEADER > bt_gatt_get_mtu(current_conn)) {
		return -EMSGSIZE;
	}
	/* len is below the MTU (at most 65) after the check above. */
	return bt_gatt_notify_uuid(current_conn, uuid, rm_svc.attrs, data, (uint16_t)len);
}

#define NOTIFY(suffix, subscribed, data, len) \
	notify_uuid(BT_UUID_DECLARE_128(TOMS_UUID(suffix)), (subscribed), (data), (len))

static void send_rm_ack(uint8_t kind, uint32_t value)
{
	uint8_t frame[5];
	int len = toms_codec_encode_ack(frame, sizeof(frame), kind, value);

	if (len > 0) {
		int rc = NOTIFY(0x0A, ack_notify, frame, (size_t)len);
		if (rc != 0 && rc != -ENOTCONN) {
			LOG_WRN("rm_ack notify failed (%d)", rc);
		}
	}
}

static ssize_t read_info(struct bt_conn *conn, const struct bt_gatt_attr *attr, void *buf,
			 uint16_t len, uint16_t offset)
{
	struct toms_device_info info = {
		.protocol_version = TOMS_PROTOCOL_VERSION,
		.fw_major = TOMS_FW_MAJOR,
		.fw_minor = TOMS_FW_MINOR,
		.fw_patch = TOMS_FW_PATCH,
		.flags = IS_ENABLED(CONFIG_TOMS_DEV_MODE) ? TOMS_INFO_FLAG_DEV_MODE : 0,
		.max_state_entries = TOMS_STATE_TABLE_MAX,
	};
	uint8_t frame[8];
	int n = toms_codec_encode_device_info(frame, sizeof(frame), &info);

	if (n < 0) {
		return BT_GATT_ERR(BT_ATT_ERR_UNLIKELY);
	}
	return bt_gatt_attr_read(conn, attr, buf, len, offset, frame, (uint16_t)n);
}

static int handle_state_update(const uint8_t *data, uint16_t len)
{
	/* Static: the BT RX thread has a small stack and writes are serialized. */
	static struct toms_state_entry entries[TOMS_STATE_TABLE_MAX];
	uint32_t version;
	bool snapshot;
	uint16_t count;

	int rc = toms_codec_decode_state_update(data, len, &version, &snapshot, entries,
						ARRAY_SIZE(entries), &count);
	if (rc < 0) {
		LOG_WRN("state_update malformed (%d)", rc);
		return rc;
	}

	k_mutex_lock(&table_lock, K_FOREVER);
	rc = toms_state_table_apply(&table, version, snapshot, entries, count);
	uint16_t total = table.count;
	k_mutex_unlock(&table_lock);
	if (rc != 0) {
		LOG_WRN("state_update v%u rejected (%d)", (unsigned int)version, rc);
		return rc;
	}
	LOG_INF("state_update v%u applied: %s, %u entries, table now %u", (unsigned int)version,
		snapshot ? "snapshot" : "delta", count, total);
	send_rm_ack(TOMS_ACK_STATE, version);
	return 0;
}

static int handle_paid_reply(const uint8_t *data, uint16_t len)
{
	uint32_t seq;
	uint8_t verdict;

	if (toms_codec_decode_paid_reply(data, len, &seq, &verdict) < 0 ||
	    verdict > TOMS_VERDICT_ALREADY_RETURNED) {
		return -EINVAL;
	}
	if (!waiting_reply || seq != pending_seq) {
		LOG_INF("paid_reply %u ignored (late or unknown)", (unsigned int)seq);
		return 0;
	}
	reply_verdict = verdict;
	k_sem_give(&reply_sem);
	return 0;
}

static int handle_time_sync(const uint8_t *data, uint16_t len)
{
	uint32_t epoch;
	uint16_t rem;

	if (toms_codec_decode_time_sync(data, len, &epoch, &rem) < 0) {
		return -EINVAL;
	}
	int rc = toms_clock_sync(&rm_clock, epoch, rem, k_uptime_get());
	if (rc == 0) {
		LOG_INF("time synced: epoch %u.%03u", (unsigned int)epoch, rem);
	}
	return rc;
}

static int handle_config(const uint8_t *data, uint16_t len)
{
	struct toms_config_msg next;

	if (toms_codec_decode_config(data, len, &next) < 0) {
		return -EINVAL;
	}
	cfg = next;
	LOG_INF("config: vehicle id %u bytes, tones 0x%02x, paid timeout %u ms, heartbeat %u s",
		cfg.vehicle_id_len, cfg.tones_mask, cfg.paid_timeout_ms, cfg.heartbeat_interval_s);
	return 0;
}

static int handle_ack(const uint8_t *data, uint16_t len)
{
	uint8_t kind;
	uint32_t value;

	if (toms_codec_decode_ack(data, len, &kind, &value) < 0) {
		return -EINVAL;
	}
	if (kind == TOMS_ACK_STATE) {
		state_acked = value;
	} else if (kind == TOMS_ACK_READ) {
		read_acked = value;
	} else {
		return -EINVAL;
	}
	LOG_INF("phone ack kind %u value %u", kind, (unsigned int)value);
	return 0;
}

static int handle_sim_card(const uint8_t *data, uint16_t len)
{
	uint8_t uid[TOMS_UID_MAX];
	uint8_t uid_len;

	if (!IS_ENABLED(CONFIG_TOMS_DEV_MODE)) {
		return -EACCES;
	}
	if (toms_codec_decode_uid(data, len, uid, &uid_len) < 0) {
		return -EINVAL;
	}
	if (sim_cb != NULL) {
		sim_cb(uid, uid_len);
	}
	return 0;
}

static const uint8_t TOMS_DIAG_MAGIC[5] = { 'T', 'O', 'M', 'S', '!' };
static bool diag_authenticated;

static int handle_diag(const uint8_t *data, uint16_t len)
{
	if (len < 1) {
		return -EINVAL;
	}
	uint8_t cmd = data[0];
	uint8_t arg = (len >= 2) ? data[1] : 0;

	/* Command 0x00: Diagnostic Unlock with special security code ("TOMS!") */
	if (cmd == 0x00) {
		if (len >= 6 && memcmp(&data[1], TOMS_DIAG_MAGIC, sizeof(TOMS_DIAG_MAGIC)) == 0) {
			diag_authenticated = true;
			display_set_diag_mode(true);
			LOG_INF("diag: session UNLOCKED with special diagnostic code");
			uint8_t ack[4] = { 0x80, 0x00, 0x01, 0x00 };
			(void)NOTIFY(0x0E, diag_notify, ack, sizeof(ack));
			return 0;
		}
		LOG_WRN("diag: invalid unlock key rejected");
		uint8_t nack[4] = { 0x80, 0x00, 0x00, 0xFF };
		(void)NOTIFY(0x0E, diag_notify, nack, sizeof(nack));
		return -EACCES;
	}

	/* Command 0x05: Clear / Silence Unpaid Alarm (allowed for conductors anytime) */
	if (cmd == 0x05) {
		buzzer_alarm_stop();
		display_clear_alarm();
		LOG_INF("diag: unpaid alarm cleared/silenced by phone");
		uint8_t ack[4] = { 0x85, 0x00, 0x00, 0x00 };
		(void)NOTIFY(0x0E, diag_notify, ack, sizeof(ack));
		return 0;
	}

	/* Protected commands 0x01..0x04 require diagnostic session to be unlocked */
	if (!diag_authenticated && !IS_ENABLED(CONFIG_TOMS_DEV_MODE)) {
		LOG_WRN("diag: cmd 0x%02X rejected (diagnostic session locked)", cmd);
		uint8_t nack[4] = { (uint8_t)(0x80 | cmd), 0x00, 0x00, 0xFF };
		(void)NOTIFY(0x0E, diag_notify, nack, sizeof(nack));
		return -EACCES;
	}

	LOG_INF("diag cmd 0x%02X arg 0x%02X", cmd, arg);

	if (diag_cb != NULL) {
		diag_cb(cmd, arg);
	}

	switch (cmd) {
	case 0x01: /* Buzzer test */
		buzzer_play(arg <= TOMS_TONE_DUPLICATE ? (enum toms_tone)arg : TOMS_TONE_OK);
		break;
	case 0x02: /* Screen test: next page */
		display_test_next();
		break;
	case 0x03: /* Screen test: toggle backlight */
		display_test_backlight_toggle();
		break;
	case 0x04: { /* NFC health test */
		uint8_t ver[4] = {0};
		int nfc_rc = pn532_get_firmware_version(ver);
		uint8_t ack[6] = { 0x84, (uint8_t)(nfc_rc == 0 ? 0 : 3), ver[0], ver[1], ver[2], ver[3] };
		(void)NOTIFY(0x0E, diag_notify, ack, sizeof(ack));
		return 0;
	}
	default:
		break;
	}

	uint8_t ack[4] = { 0x80 | cmd, arg, 0x00, 0x00 };
	(void)NOTIFY(0x0E, diag_notify, ack, sizeof(ack));
	return 0;
}

static ssize_t write_handler(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			     const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
	ARG_UNUSED(flags);

	if (offset != 0) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
	}

	uint8_t which = (uint8_t)(uintptr_t)attr->user_data;
	int rc;

	rx_writes++;
	last_write_char = which;

	switch (which) {
	case CHAR_STATE_UPDATE:
		rc = handle_state_update(buf, len);
		break;
	case CHAR_PAID_REPLY:
		rc = handle_paid_reply(buf, len);
		break;
	case CHAR_TIME_SYNC:
		rc = handle_time_sync(buf, len);
		break;
	case CHAR_CONFIG:
		rc = handle_config(buf, len);
		break;
	case CHAR_ACK:
		rc = handle_ack(buf, len);
		break;
	case CHAR_SIM_CARD:
		rc = handle_sim_card(buf, len);
		break;
	case CHAR_OTA_CONTROL:
		rc = rm_ota_control_write(conn, buf, len);
		break;
		case CHAR_OTA_DATA:
			rc = rm_ota_data_write(conn, buf, len);
			break;
		case CHAR_DIAG:
			rc = handle_diag(buf, len);
			break;
		default:
		rc = -EINVAL;
		break;
	}

	if (rc != 0) {
		rx_rejected++;
		LOG_WRN("write to 0x%02x rejected (%d)", which, rc);
		if (rc == -EACCES) {
			return BT_GATT_ERR(BT_ATT_ERR_WRITE_NOT_PERMITTED);
		}
		return BT_GATT_ERR(BT_ATT_ERR_VALUE_NOT_ALLOWED);
	}
	return len;
}

static int start_advertising(void)
{
	static const uint8_t flags[] = {BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR};
	static const uint8_t svc_uuid[] = {TOMS_UUID_SVC};
	static const struct bt_data ad[] = {
		BT_DATA(BT_DATA_FLAGS, flags, sizeof(flags)),
		BT_DATA(BT_DATA_UUID128_ALL, svc_uuid, sizeof(svc_uuid)),
	};
	/* The 128-bit UUID fills most of the advertising packet, so the name
	 * goes in the scan response. */
	static struct bt_data sd[1];

	sd[0] = (struct bt_data)BT_DATA(BT_DATA_NAME_COMPLETE, adv_name, strlen(adv_name));

	int rc = bt_le_adv_start(BT_LE_ADV_PARAM(BT_LE_ADV_OPT_CONN, 800, 800, NULL), ad,
				 ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
	advertising = (rc == 0);
	if (rc != 0) {
		LOG_ERR("advertising failed (rc=%d)", rc);
	}
	return rc;
}

static void connected(struct bt_conn *conn, uint8_t err)
{
	if (err != 0) {
		LOG_WRN("connection failed (err 0x%02x)", err);
		return;
	}
	current_conn = bt_conn_ref(conn);
	advertising = false;
	LOG_INF("phone connected");
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
	ARG_UNUSED(conn);
	LOG_INF("phone disconnected (reason 0x%02x)", reason);
	if (current_conn != NULL) {
		bt_conn_unref(current_conn);
		current_conn = NULL;
	}
	card_notify = false;
	check_notify = false;
	hb_notify = false;
	ack_notify = false;
	ota_notify = false;
	diag_notify = false;
	diag_authenticated = false;
	display_set_diag_mode(false);
}

/* The connection object is free again: advertise for the next connection. */
static void recycled(void)
{
	if (start_advertising() == 0) {
		LOG_INF("advertising again as %s", adv_name);
	}
}

BT_CONN_CB_DEFINE(conn_cb) = {
	.connected = connected,
	.disconnected = disconnected,
	.recycled = recycled,
};

/* Pairing: without callbacks the board advertises "no input, no output", and Android's
 * createBond asks for an authenticated pairing, which cannot use that - the pairing is
 * rejected (SMP_PAIR_AUTH_FAIL, shown as "wrong passkey"). Claiming display+yes/no and
 * auto-confirming makes numeric comparison work: the phone shows 6 digits, the user
 * taps Pair, and the bond stores on both sides. */
static void auth_passkey_display(struct bt_conn *conn, unsigned int passkey)
{
	LOG_INF("pairing passkey %06u (shown on the LCD)", passkey);
	ota_ui_show_pair(passkey);
}

static void auth_passkey_confirm(struct bt_conn *conn, unsigned int passkey)
{
	LOG_INF("pairing passkey %06u: displaying on LCD and confirming", passkey);
	ota_ui_show_pair(passkey);
	bt_conn_auth_passkey_confirm(conn);
}

static void auth_cancel(struct bt_conn *conn)
{
	LOG_INF("pairing cancelled by the phone");
	ota_ui_clear_pair();
}

static struct bt_conn_auth_cb auth_cb = {
	.passkey_display = auth_passkey_display,
	.passkey_confirm = auth_passkey_confirm,
	.cancel = auth_cancel,
};

static void auth_pairing_complete(struct bt_conn *conn, bool bonded)
{
	LOG_INF("pairing complete (bonded=%d)", bonded);
	ota_ui_clear_pair();
}

static void auth_pairing_failed(struct bt_conn *conn, enum bt_security_err reason)
{
	LOG_WRN("pairing failed (reason %d)", (int)reason);
	ota_ui_clear_pair();
}

static struct bt_conn_auth_info_cb auth_info_cb = {
	.pairing_complete = auth_pairing_complete,
	.pairing_failed = auth_pairing_failed,
};

static void make_name(char *out, size_t cap)
{
	bt_addr_le_t addrs[1];
	size_t count = 1;
	char suffix[5] = "0000";

	bt_id_get(addrs, &count); /* returns void in Zephyr 4.x */
	if (count == 1) {
		snprintk(suffix, sizeof(suffix), "%02X%02X", addrs[0].a.val[1], addrs[0].a.val[0]);
	}
	snprintk(out, cap, "TOMS-RM-%s", suffix);
}

int rm_ble_start(void)
{
	toms_state_table_init(&table);
	toms_config_defaults(&cfg);
	toms_clock_init(&rm_clock);
	rm_ota_init();

	int rc = bt_enable(NULL);
	if (rc != 0) {
		LOG_ERR("bt_enable failed (rc=%d)", rc);
		return rc;
	}

	/* With CONFIG_BT_SETTINGS the identity and bonds live in NVS. Zephyr
	 * requires the "bt/" tree to be loaded AFTER bt_enable (its handler
	 * needs the stack up; loading earlier resets the board at boot - measured
	 * 2026-11-01). This also restores the stored identity, so pairing
	 * survives the OTA reboot. */
	settings_load();

	make_name(adv_name, sizeof(adv_name));
	bt_set_name(adv_name);

	int rc_auth = bt_conn_auth_cb_register(&auth_cb);

	if (rc_auth != 0) {
		LOG_ERR("auth cb register failed (rc=%d)", rc_auth);
	}
	rc_auth = bt_conn_auth_info_cb_register(&auth_info_cb);

	if (rc_auth != 0) {
		LOG_ERR("auth info register failed (rc=%d)", rc_auth);
	}

	rc = start_advertising();
	if (rc == 0) {
		LOG_INF("advertising as %s", adv_name);
	}
	return rc;
}

bool rm_ble_connected(void)
{
	return current_conn != NULL;
}

void rm_ble_set_sim_card_cb(rm_ble_sim_card_cb_t cb)
{
	sim_cb = cb;
}

void rm_ble_get_config(struct toms_config_msg *out)
{
	*out = cfg;
}

uint32_t rm_ble_epoch(void)
{
	return toms_clock_epoch(&rm_clock, k_uptime_get());
}

void rm_ble_get_status(struct rm_ble_status *out)
{
	memset(out, 0, sizeof(*out));
	strncpy(out->name, adv_name[0] != '\0' ? adv_name : "STARTING", sizeof(out->name) - 1);
	out->advertising = advertising;
	out->connected = current_conn != NULL;
	out->card_notify = card_notify;
	out->check_notify = check_notify;
	out->hb_notify = hb_notify;
	out->ack_notify = ack_notify;
	out->time_synced = rm_clock.synced;
	out->mtu = current_conn != NULL ? bt_gatt_get_mtu(current_conn) : 0;
	out->rx_writes = rx_writes;
	out->rx_rejected = rx_rejected;
	out->last_write_char = last_write_char;
	out->state_acked = state_acked;
	out->read_acked = read_acked;
	out->tx_card = tx_card;
	out->tx_heartbeat = tx_heartbeat;
	out->paid_sent = paid_sent;
	out->paid_replied = paid_replied;
	out->paid_timeouts = paid_timeouts;

	k_mutex_lock(&table_lock, K_FOREVER);
	out->state_version = table.version;
	out->state_count = table.count;
	k_mutex_unlock(&table_lock);
}

uint8_t rm_ble_state_lookup(const uint8_t *uid, uint8_t uid_len)
{
	k_mutex_lock(&table_lock, K_FOREVER);
	uint8_t status = toms_state_table_lookup(&table, uid, uid_len);
	k_mutex_unlock(&table_lock);
	return status;
}

int rm_ble_notify_ota(const uint8_t *data, size_t len)
{
	return NOTIFY(0x0C, ota_notify, data, len);
}

int rm_ble_notify_card_read(const uint8_t *data, size_t len)
{
	int rc = NOTIFY(0x01, card_notify, data, len);

	if (rc == 0) {
		tx_card++;
	}
	return rc;
}

int rm_ble_notify_heartbeat(const uint8_t *data, size_t len)
{
	int rc = NOTIFY(0x05, hb_notify, data, len);

	if (rc == 0) {
		tx_heartbeat++;
	}
	return rc;
}

int rm_ble_paid_check(const uint8_t *uid, uint8_t uid_len, uint32_t timeout_ms,
		      uint8_t *verdict)
{
	uint8_t frame[5 + TOMS_UID_MAX];
	uint32_t seq = (uint32_t)atomic_inc(&check_seq_counter) + 1u;
	int len = toms_codec_encode_paid_check(frame, sizeof(frame), seq, uid, uid_len);

	if (len < 0) {
		return len;
	}
	if (current_conn == NULL || !check_notify) {
		return -ENOTCONN;
	}

	k_sem_reset(&reply_sem);
	pending_seq = seq;
	waiting_reply = true;

	int rc = NOTIFY(0x03, check_notify, frame, (size_t)len);
	if (rc != 0) {
		waiting_reply = false;
		return rc;
	}
	paid_sent++;

	if (k_sem_take(&reply_sem, K_MSEC(timeout_ms)) != 0) {
		waiting_reply = false;
		paid_timeouts++;
		return -ETIMEDOUT;
	}
	waiting_reply = false;
	paid_replied++;
	*verdict = reply_verdict;
	return 0;
}

void rm_ble_set_diag_cmd_cb(rm_ble_diag_cmd_cb_t cb)
{
	diag_cb = cb;
}

int rm_ble_notify_diag(const uint8_t *data, size_t len)
{
	return NOTIFY(0x0E, diag_notify, data, len);
}
