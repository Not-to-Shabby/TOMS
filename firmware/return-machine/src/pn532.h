/* PN532 NFC controller driver — I2C, reader mode only.
 *
 * The old archive driver (archive/shared_lib/pn532) was NFC-DEP focused and
 * ESP-IDF specific; this one speaks plain ISO 14443-A reader commands on the
 * Zephyr I2C API. Framing follows the PN532 user manual: the I2C read starts
 * with a one-byte ready status, frames are 00 00 FF LEN LCS TFI ... DCS 00.
 * There is no hardware reset wired (RSTO unconnected) — recovery is
 * re-initialization (pn532_init) or a bus-level abort with the ACK frame. */
#ifndef TOMS_PN532_H
#define TOMS_PN532_H

#include <stdint.h>
#include <zephyr/kernel.h>

#include <toms/codec.h>

/* Antenna type classification: the wire codes live in toms/codec.h
 * (TOMS_ATYPE_*); these aliases keep the driver's vocabulary. */
#define PN532_ATYPE_OTHER TOMS_ATYPE_OTHER
#define PN532_ATYPE_TYPE2 TOMS_ATYPE_TYPE2
#define PN532_ATYPE_TYPE4 TOMS_ATYPE_TYPE4
#define PN532_ATYPE_CLASSIC TOMS_ATYPE_CLASSIC

struct pn532_target {
	uint8_t uid[10];
	uint8_t uid_len; /* 4, 7 or 10 */
	uint8_t atype;   /* PN532_ATYPE_* */
};

/* Bring the PN532 up: SAM configuration, RF retry limits, version check.
 * Returns 0 on success; call again to recover after bus errors. */
int pn532_init(void);

/* Filled with IC, firmware version, revision, support (response of 0x02). */
int pn532_get_firmware_version(uint8_t out[4]);

/* Poll once for one ISO 14443-A target (INLISTPASSIVETARGET, 106 kbps).
 * Returns 0 and fills `out` when a card was found, -ETIMEDOUT when the IRQ
 * stayed idle for `timeout`, -EIO on bus/protocol errors. */
int pn532_read_target(struct pn532_target *out, k_timeout_t timeout);

#endif /* TOMS_PN532_H */
