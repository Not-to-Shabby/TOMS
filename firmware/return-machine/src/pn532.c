#include "pn532.h"

#include <errno.h>
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
	/* The IRQ spec is GPIO_ACTIVE_LOW: the PN532 pulls the line LOW when a
	 * response is pending, so logical 1 means "ready now". The line may
	 * already be asserted when we arrive, so check before waiting. */
	if (gpio_pin_get_dt(&pn_irq) == 1) {
		return 0;
	}
	k_sem_reset(&irq_sem);
	if (gpio_pin_get_dt(&pn_irq) == 1) {
		return 0;
	}
	return k_sem_take(&irq_sem, timeout);
}

/* Raw frame: 00 00 FF LEN LCS [TFI data...] DCS 00 */
static int pn532_write_frame(const uint8_t *payload, size_t len)
{
	if (len == 0 || len > PN532_FRAME_MAX - 8u) {
		return -EINVAL;
	}
	uint8_t buf[PN532_FRAME_MAX];
	size_t n = 0;
	uint8_t hdr[3] = { 0x00, 0x00, 0xFF };
	uint8_t lcs = (uint8_t)(~(uint8_t)len + 1u);

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

/* Read one frame. `payload` receives TFI..end-of-data (TFI included). */
static int pn532_read_frame(uint8_t *payload, size_t *len, k_timeout_t timeout)
{
	int rc = wait_ready(timeout);
	if (rc != 0) {
		return (rc == -EAGAIN || rc == -EWOULDBLOCK) ? -ETIMEDOUT : rc;
	}

	uint8_t head[6]; /* status byte + 00 00 FF LEN LCS */
	rc = i2c_read_dt(&pn_i2c, head, sizeof(head));
	if (rc != 0) {
		return -EIO;
	}
	if ((head[0] & 0x01u) == 0u) {
		return -EIO; /* PN532 says it is not ready after all */
	}
	if (head[1] != 0x00 || head[2] != 0x00 || head[3] != 0xFF) {
		return -EIO; /* bad start code */
	}

	bool is_ack = (head[4] == 0x00 && head[5] == 0xFF);
	size_t flen = head[4];

	if (is_ack) {
		/* ACK frame: 00 00 FF 00 FF 00 — two more bytes follow. */
		uint8_t tail[2];
		if (i2c_read_dt(&pn_i2c, tail, sizeof(tail)) != 0) {
			return -EIO;
		}
		*len = 0;
		return 1; /* caller distinguishes ACK with the return value */
	}

	if (flen < 1u || flen > PN532_FRAME_MAX - 8u) {
		return -EIO;
	}
	uint8_t lcs;
	if (i2c_read_dt(&pn_i2c, &lcs, 1) != 0) {
		return -EIO;
	}
	if ((uint8_t)(flen + lcs) != 0u) {
		return -EIO; /* length checksum mismatch */
	}

	uint8_t body[PN532_FRAME_MAX];
	if (i2c_read_dt(&pn_i2c, body, flen + 2u) != 0) { /* data.. + DCS + postamble */
		return -EIO;
	}
	uint8_t dcs = body[flen];
	if (dcs != dcs_of(body, flen)) {
		return -EIO; /* data checksum mismatch */
	}

	memcpy(payload, body, flen);
	*len = flen;
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

	uint8_t frame[PN532_FRAME_MAX];
	size_t flen = 0;

	rc = pn532_read_frame(frame, &flen, K_MSEC(60));
	if (rc < 0) {
		pn532_abort();
		return rc;
	}
	if (rc != 1) { /* first frame must be the ACK */
		pn532_abort();
		return -EIO;
	}

	rc = pn532_read_frame(frame, &flen, timeout);
	if (rc < 0) {
		pn532_abort();
		return rc;
	}
	if (rc != 0 || flen < 1 || frame[0] != PN532_TFI_PN532_TO_HOST) {
		pn532_abort();
		return -EIO;
	}

	size_t copy = MIN(flen, *rlen);
	memcpy(resp, frame, copy);
	*rlen = flen;
	return 0;
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

	/* SAMConfiguration: normal mode, no timeout, use IRQ pin. */
	const uint8_t sam[4] = { 0x14, 0x01, 0x00, 0x00 };
	uint8_t resp[8];
	size_t rlen = sizeof(resp);
	int rc = pn532_exchange(sam, sizeof(sam), resp, &rlen, K_MSEC(100));
	if (rc != 0 || rlen < 2u || resp[1] != 0x15u) {
		LOG_ERR("SAMConfiguration failed (rc=%d)", rc);
		return rc == 0 ? -EIO : rc;
	}

	/* MaxRtyCOM = 1: INLISTPASSIVETARGET gives up quickly per round, our
	 * own timeout decides when to stop polling. */
	const uint8_t rf[3] = { 0x32, 0x05, 0x01 };
	rlen = sizeof(resp);
	rc = pn532_exchange(rf, sizeof(rf), resp, &rlen, K_MSEC(100));
	if (rc != 0) {
		LOG_WRN("RFConfiguration failed (rc=%d), continuing", rc);
	}

	uint8_t ver[4];
	rc = pn532_get_firmware_version(ver);
	if (rc != 0) {
		LOG_ERR("GetFirmwareVersion failed (rc=%d)", rc);
		return rc;
	}
	LOG_INF("PN532 v%u.%u (IC=0x%02x)", ver[1], ver[2], ver[0]);
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
	if (rlen < 5u || resp[1] != 0x4Bu || resp[2] < 1u) {
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
