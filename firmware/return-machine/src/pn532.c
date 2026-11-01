#include "pn532.h"

#include <errno.h>
#include <string.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/i2c.h>
#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(pn532, LOG_LEVEL_INF);

#include "toms_pins.h"

#define PN532_I2C_ADDR 0x24

#define PN532_TFI_HOST_TO_PN532 0xD4u
#define PN532_TFI_PN532_TO_HOST 0xD5u

/* Largest frame we handle: response payload is capped at 265 per the manual. */
#define PN532_FRAME_MAX 300u

static const struct i2c_dt_spec pn_i2c = I2C_DT_SPEC_GET(TOMS_PN532_NODE);
static const struct gpio_dt_spec pn_irq = GPIO_DT_SPEC_GET(TOMS_PN532_NODE, irq_gpios);

static struct gpio_callback irq_cb;
static struct k_sem irq_sem;

static void irq_fired(const struct device *dev, struct gpio_callback *cb, uint32_t pins)
{
	ARG_UNUSED(dev);
	ARG_UNUSED(cb);
	ARG_UNUSED(pins);
	k_sem_give(&irq_sem);
}

static uint8_t dcs_of(const uint8_t *data, size_t len)
{
	uint8_t sum = 0;
	for (size_t i = 0; i < len; i++) {
		sum += data[i];
	}
	return (uint8_t)(~sum + 1u);
}

static int wait_ready(k_timeout_t timeout)
{
	int64_t deadline = k_uptime_get() + k_ticks_to_ms_floor64(timeout.ticks);

	do {
		/* IRQ pin is active low, logical 1 in Zephyr DT */
		if (gpio_pin_get_dt(&pn_irq) == 1) {
			return 0;
		}
		if (k_sem_take(&irq_sem, K_MSEC(5)) == 0) {
			return 0;
		}
	} while (k_uptime_get() < deadline);

	return -ETIMEDOUT;
}

/* Raw frame: 00 00 FF LEN LCS [TFI data...] DCS 00. */
static int pn532_write_frame(const uint8_t *cmd, size_t clen)
{
	if (clen == 0 || clen + 1u > PN532_FRAME_MAX - 8u) {
		return -EINVAL;
	}
	uint8_t payload[PN532_FRAME_MAX];
	size_t len = clen + 1u; /* TFI + command */
	uint8_t buf[PN532_FRAME_MAX];
	size_t n = 0;
	uint8_t hdr[3] = { 0x00, 0x00, 0xFF };
	uint8_t lcs = (uint8_t)(~(uint8_t)len + 1u);

	payload[0] = PN532_TFI_HOST_TO_PN532;
	memcpy(&payload[1], cmd, clen);

	memcpy(&buf[n], hdr, 3);
	n += 3;
	buf[n++] = (uint8_t)len;
	buf[n++] = lcs;
	memcpy(&buf[n], payload, len);
	n += len;
	buf[n++] = dcs_of(payload, len);
	buf[n++] = 0x00;

	return i2c_write_dt(&pn_i2c, buf, n);
}

/* Abort a command the PN532 is still processing (no RSTO is wired). */
static int pn532_abort(void)
{
	static const uint8_t ack_frame[6] = { 0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00 };
	return i2c_write_dt(&pn_i2c, ack_frame, sizeof(ack_frame));
}

/* Read ACK frame: exactly 7 bytes (1 status + 6 ACK bytes). */
static int pn532_read_ack(k_timeout_t timeout)
{
	int rc = wait_ready(timeout);
	if (rc != 0) {
		return (rc == -EAGAIN) ? -ETIMEDOUT : rc;
	}

	uint8_t ack[7];
	rc = i2c_read_dt(&pn_i2c, ack, sizeof(ack));
	if (rc != 0) {
		return -EIO;
	}
	if ((ack[0] & 0x01u) == 0u) {
		return -EIO;
	}
	/* ACK frame: 00 00 FF 00 FF 00 */
	if (ack[1] != 0x00u || ack[2] != 0x00u || ack[3] != 0xFFu ||
	    ack[4] != 0x00u || ack[5] != 0xFFu || ack[6] != 0x00u) {
		return -EIO;
	}
	return 0;
}

/* Read response: single 64-byte I2C transaction, parse in memory. */
static int pn532_read_response(uint8_t *resp, size_t *rlen, k_timeout_t timeout)
{
	int rc = wait_ready(timeout);
	if (rc != 0) {
		return (rc == -EAGAIN) ? -ETIMEDOUT : rc;
	}

	uint8_t raw[64];
	memset(raw, 0, sizeof(raw));
	rc = i2c_read_dt(&pn_i2c, raw, sizeof(raw));
	if (rc != 0) {
		return -EIO;
	}
	if ((raw[0] & 0x01u) == 0u) {
		return -EIO;
	}

	const uint8_t *p = &raw[1];
	if (p[0] != 0x00u || p[1] != 0x00u || p[2] != 0xFFu) {
		return -EIO;
	}
	uint8_t len = p[3];
	uint8_t lcs = p[4];
	if ((uint8_t)(len + lcs) != 0u) {
		return -EIO;
	}
	if (len < 2u || len > 48u) {
		return -EIO;
	}
	if (p[5] != PN532_TFI_PN532_TO_HOST) {
		return -EIO;
	}

	/* DCS check: sum of p[5 .. 5 + len] == 0 mod 256 */
	uint8_t dcs_sum = 0;
	for (uint8_t i = 0; i <= len; i++) {
		dcs_sum += p[5 + i];
	}
	if (dcs_sum != 0u) {
		return -EIO;
	}

	if (*rlen < (size_t)len) {
		return -ENOSPC;
	}
	memcpy(resp, &p[5], len);
	*rlen = len;
	return 0;
}

/* Send a command, consume the ACK, deliver the response payload. */
static int pn532_exchange(const uint8_t *cmd, size_t clen, uint8_t *resp, size_t *rlen,
			  k_timeout_t timeout)
{
	int rc = pn532_write_frame(cmd, clen);
	if (rc != 0) {
		return -EIO;
	}

	rc = pn532_read_ack(K_MSEC(100));
	if (rc != 0) {
		pn532_abort();
		return rc;
	}

	rc = pn532_read_response(resp, rlen, timeout);
	if (rc != 0) {
		pn532_abort();
		return rc;
	}

	return 0;
}

int pn532_in_release(uint8_t tg)
{
	const uint8_t cmd[2] = { 0x52, tg };
	uint8_t resp[8];
	size_t rlen = sizeof(resp);

	return pn532_exchange(cmd, sizeof(cmd), resp, &rlen, K_MSEC(100));
}

int pn532_get_firmware_version(uint8_t out[4])
{
	const uint8_t cmd[1] = { 0x02 }; /* GetFirmwareVersion */
	uint8_t resp[8];
	size_t rlen = sizeof(resp);

	int rc = pn532_exchange(cmd, sizeof(cmd), resp, &rlen, K_MSEC(100));
	if (rc != 0) {
		return rc;
	}
	/* resp: D5 03 IC VER REV SUPPORT */
	if (rlen < 6u || resp[1] != 0x03u) {
		return -EIO;
	}
	memcpy(out, &resp[2], 4);
	return 0;
}

int pn532_init(void)
{
	if (!device_is_ready(pn_i2c.bus) || !gpio_is_ready_dt(&pn_irq)) {
		LOG_ERR("PN532 bus or IRQ not ready");
		return -ENODEV;
	}

	k_sem_reset(&irq_sem);

	/* SAMConfiguration: normal mode, timeout 0x14, use IRQ pin */
	const uint8_t sam[4] = { 0x14, 0x01, 0x14, 0x01 };
	uint8_t resp[8];
	size_t rlen = sizeof(resp);
	int rc = pn532_exchange(sam, sizeof(sam), resp, &rlen, K_MSEC(500));
	if (rc != 0 || rlen < 2u || resp[1] != 0x15u) {
		LOG_ERR("SAMConfiguration failed (rc=%d)", rc);
		return rc == 0 ? -EIO : rc;
	}

	/* MaxRetries (item 5): MxRtyATR=0xFF, MxRtyPSL=0x01, MxRtyPassiveActivation=0x01.
	 * Requires 5 bytes total. INLISTPASSIVETARGET gives up quickly per round. */
	const uint8_t rf[5] = { 0x32, 0x05, 0xFF, 0x01, 0x01 };
	rlen = sizeof(resp);
	rc = pn532_exchange(rf, sizeof(rf), resp, &rlen, K_MSEC(100));
	if (rc != 0) {
		LOG_WRN("RFConfiguration failed (rc=%d), continuing", rc);
	}

	uint8_t ver[4];
	rc = pn532_get_firmware_version(ver);
	if (rc == 0) {
		LOG_INF("PN532 v%u.%u (IC=0x%02X)", ver[1], ver[2], ver[0]);
	} else {
		LOG_WRN("GetFirmwareVersion failed (rc=%d) - continuing", rc);
	}

	LOG_INF("PN532 initialized successfully");
	return 0;
}

int pn532_read_target(struct pn532_target *out, k_timeout_t timeout)
{
	const uint8_t cmd[3] = { 0x4A, 0x01, 0x00 }; /* InListPassiveTarget, 1 target, 106 kbps */
	uint8_t resp[64];
	size_t rlen = sizeof(resp);

	int rc = pn532_exchange(cmd, sizeof(cmd), resp, &rlen, timeout);
	if (rc == -ETIMEDOUT) {
		return -ETIMEDOUT;
	}
	if (rc != 0) {
		return -EIO;
	}
	/* resp: D5 4B NbTg [Tg SENS_RES(2) SEL_RES UIDLen UID] */
	if (rlen < 3u || resp[0] != 0xD5u || resp[1] != 0x4Bu) {
		return -EIO;
	}

	uint8_t nb_tg = resp[2];
	if (nb_tg == 0u) {
		/* No card in the field. Normal polling condition, NOT an error! */
		return -ETIMEDOUT;
	}

	if (rlen < 8u) {
		return -EIO;
	}

	const uint8_t sel_res = resp[6];
	const uint8_t uid_len = resp[7];
	if (uid_len < 4u || uid_len > 10u || rlen < 8u + (size_t)uid_len) {
		return -EIO;
	}

	out->uid_len = uid_len;
	memcpy(out->uid, &resp[8], uid_len);

	if ((sel_res & 0x40u) != 0u) {
		out->atype = PN532_ATYPE_TYPE4; /* ISO 14443-4: DESFire */
	} else if ((sel_res & 0x08u) != 0u) {
		out->atype = PN532_ATYPE_CLASSIC; /* MIFARE Classic */
	} else if ((sel_res & 0x04u) != 0u) {
		out->atype = PN532_ATYPE_TYPE2; /* NTAG21x / Ultralight */
	} else {
		out->atype = PN532_ATYPE_OTHER;
	}

	/* Release target so card can be re-detected or removed cleanly */
	(void)pn532_in_release(0);

	return 0;
}

static int pn532_setup(void)
{
	k_sem_init(&irq_sem, 0, 1);

	int rc = gpio_pin_configure_dt(&pn_irq, GPIO_INPUT);
	if (rc != 0) {
		return rc;
	}
	rc = gpio_pin_interrupt_configure_dt(&pn_irq, GPIO_INT_EDGE_TO_ACTIVE);
	if (rc != 0) {
		return rc;
	}
	gpio_init_callback(&irq_cb, irq_fired, BIT(pn_irq.pin));
	return gpio_add_callback(pn_irq.port, &irq_cb);
}

/* Init the IRQ machinery before the app threads start polling. */
SYS_INIT(pn532_setup, POST_KERNEL, CONFIG_I2C_INIT_PRIORITY);
