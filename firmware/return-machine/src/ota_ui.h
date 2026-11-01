/* What the LCD shows during Bluetooth pairing and a firmware update.
 *
 * The OTA worker and the pairing callbacks only RECORD state here; the display
 * thread is the only code that draws, and it renders this state instead of its
 * colour pages while an update or a pairing is in progress. */
#ifndef TOMS_OTA_UI_H
#define TOMS_OTA_UI_H

#include <stdbool.h>
#include <stdint.h>

enum ota_ui_kind {
	OTA_UI_NONE = 0,
	OTA_UI_PAIR,   /* show the pairing passkey */
	OTA_UI_UPDATE, /* show update progress */
};

enum ota_ui_phase {
	OTA_UI_ERASE = 0,
	OTA_UI_RECV,
	OTA_UI_VERIFY,
	OTA_UI_READY, /* installed, rebooting */
};

/* A snapshot for the display thread to draw. */
struct ota_ui_state {
	enum ota_ui_kind kind;
	uint32_t passkey;  /* PAIR: the 6-digit number */
	uint8_t phase;     /* UPDATE: ota_ui_phase */
	uint32_t done;     /* UPDATE: bytes written (RECV) or progress numerator */
	uint32_t total;    /* UPDATE: image size */
};

/* Pairing screen ------------------------------------------------------- */

/* Called on the Bluetooth RX thread: show this passkey on the LCD. */
void ota_ui_show_pair(uint32_t passkey);

/* Called on the Bluetooth RX thread from passkey_confirm: wait up to `wait_ms`
 * for the user to press the button (numeric comparison), then return. The
 * caller always confirms afterwards, so a timeout keeps unattended pairing
 * working. */
void ota_ui_pair_wait_confirm(uint32_t wait_ms);

/* The display thread's button handler: returns true if the press was consumed
 * as a pairing confirmation. */
bool ota_ui_pair_button(void);

/* Pairing finished one way or another: back to the normal pages. */
void ota_ui_clear_pair(void);

/* Update screen -------------------------------------------------------- */

/* Called on the OTA worker thread: show the progress screen. */
void ota_ui_show_update(uint8_t phase, uint32_t done, uint32_t total);

void ota_ui_clear_update(void);

/* The display thread takes a snapshot to draw. */
struct ota_ui_state ota_ui_get(void);

#endif /* TOMS_OTA_UI_H */
