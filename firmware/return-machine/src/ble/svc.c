#include "svc.h"

#include <errno.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(ble_svc, LOG_LEVEL_INF);

/* UUID base "TOMS RETURN MACHINE": 544F4D53-5245-5455-524E-414348494Exx,
 * where xx is the characteristic suffix (see PROTOCOL.md). */
#define TOMS_UUID(suffix) \
	BT_UUID_128_ENCODE(0x544F4D53, 0x5245, 0x5455, 0x524E, (0x414348494E00ull | (suffix)))
#define TOMS_UUID_SVC TOMS_UUID(0x45)

static struct bt_conn *current_conn;

static ssize_t write_handler(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			     const void *buf, uint16_t len, uint16_t offset, uint8_t flags);

static void ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	LOG_INF("notifications %s", (value & BT_GATT_CCC_NOTIFY) ? "on" : "off");
}

BT_GATT_SERVICE_DEFINE(rm_svc,
	BT_GATT_PRIMARY_SERVICE(BT_UUID_DECLARE_128(TOMS_UUID_SVC)),
	/* 01 card_read: notify */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x01)),
		BT_GATT_CHRC_NOTIFY, BT_GATT_PERM_READ, NULL, NULL, NULL),
	BT_GATT_CCC(ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	/* 02 state_update: write (with or without response) */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x02)),
		BT_GATT_CHRC_WRITE | BT_GATT_CHRC_WRITE_WITHOUT_RESP,
		BT_GATT_PERM_READ | BT_GATT_PERM_WRITE, NULL, write_handler, (void *)0x02),
	/* 03 paid_check: request from the RM */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x03)),
		BT_GATT_CHRC_WRITE | BT_GATT_CHRC_WRITE_WITHOUT_RESP,
		BT_GATT_PERM_READ | BT_GATT_PERM_WRITE, NULL, write_handler, (void *)0x03),
	/* 04 paid_reply: phone's answer, notified back */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x04)),
		BT_GATT_CHRC_NOTIFY, BT_GATT_PERM_READ, NULL, NULL, NULL),
	BT_GATT_CCC(ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	/* 05 heartbeat: notify */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x05)),
		BT_GATT_CHRC_NOTIFY, BT_GATT_PERM_READ, NULL, NULL, NULL),
	BT_GATT_CCC(ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	/* 06 time_sync */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x06)),
		BT_GATT_CHRC_WRITE, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE,
		NULL, write_handler, (void *)0x06),
	/* 07 config */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x07)),
		BT_GATT_CHRC_WRITE, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE,
		NULL, write_handler, (void *)0x07),
	/* 08 ack */
	BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_128(TOMS_UUID(0x08)),
		BT_GATT_CHRC_WRITE, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE,
		NULL, write_handler, (void *)0x08),
);

static ssize_t write_handler(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			     const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(flags);

	if (offset != 0) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
	}

	const uint8_t *data = buf;
	LOG_INF("char 0x%02x, %u bytes: %02x %02x %02x %02x", (uint32_t)(uintptr_t)attr->user_data,
		len, len > 0 ? data[0] : 0, len > 1 ? data[1] : 0, len > 2 ? data[2] : 0,
		len > 3 ? data[3] : 0);
	return len;
}

static void connected(struct bt_conn *conn, uint8_t err)
{
	if (err != 0) {
		LOG_WRN("connection failed (err 0x%02x)", err);
		return;
	}
	current_conn = bt_conn_ref(conn);
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
}

BT_CONN_CB_DEFINE(conn_cb) = {
	.connected = connected,
	.disconnected = disconnected,
};

static void make_name(char *out, size_t cap)
{
	bt_addr_le_t addrs[1];
	size_t count = 1;
	char suffix[5] = "0000";

	if (bt_id_get(addrs, &count) == 1) {
		snprintk(suffix, sizeof(suffix), "%02X%02X", addrs[0].a.val[1], addrs[0].a.val[0]);
	}
	snprintk(out, cap, "TOMS-RM-%s", suffix);
}

static const struct bt_data adv_data[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
};

int rm_ble_start(void)
{
	int rc = bt_enable(NULL);
	if (rc != 0) {
		LOG_ERR("bt_enable failed (rc=%d)", rc);
		return rc;
	}

	char name[24];
	make_name(name, sizeof(name));
	bt_set_name(name);
	LOG_INF("advertising as %s", name);

	/* 500 ms connectable advertising with the full name in the payload. */
	rc = bt_le_adv_start(BT_LE_ADV_PARAM(BT_LE_ADV_OPT_CONNECTABLE | BT_LE_ADV_OPT_USE_NAME,
					     800, 800, NULL),
			     adv_data, ARRAY_SIZE(adv_data), NULL, 0);
	if (rc != 0) {
		LOG_ERR("advertising failed (rc=%d)", rc);
	}
	return rc;
}

bool rm_ble_connected(void)
{
	return current_conn != NULL;
}

int rm_ble_notify_card_read(const uint8_t *data, size_t len)
{
	if (current_conn == NULL) {
		return -ENOTCONN;
	}
	/* The value attribute is the second attribute in the service
	 * definition (primary service, characteristic, value, CCC). */
	return bt_gatt_notify(current_conn, &rm_svc.attrs[2], data, len);
}
