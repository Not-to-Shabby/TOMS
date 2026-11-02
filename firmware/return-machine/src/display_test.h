/* Stand-alone display bench test. Needs no computer: it runs from power-up and
 * the Return button drives it.
 *
 *   power-up        pages advance by themselves every 3 s
 *   short press     next page (stops the automatic advance)
 *   hold > 0.8 s    flip the backlight GPIO (finds the polarity by eye)
 *
 * Pages: RED, GREEN, BLUE, WHITE, BLACK, colour bars, frame + corner markers,
 * gradient + checkerboard, info, last card UID. */
#ifndef TOMS_DISPLAY_TEST_H
#define TOMS_DISPLAY_TEST_H

#include <stdbool.h>
#include <stdint.h>

/* Brings up the panel and starts the test thread. Returns 0 on success. */
int display_test_init(void);

/* Called from the button callback: short press = next page, hold = flip
 * the backlight. Blocks up to the long-press window; the callback runs in
 * the system workqueue, which is fine for a bench build. */
void display_test_button(void);

/* Show the next page. */
void display_test_next(void);

/* Long press: flip the backlight GPIO and redraw. */
void display_test_backlight_toggle(void);

/* Stores the UID and card type, jumps to card page. Safe to call from any thread. */
void display_test_set_uid(const uint8_t *uid, uint8_t len, uint8_t atype);

/* Passenger deployment UI: displays the paid check verdict on the LCD:
 * TOMS_VERDICT_OK, TOMS_VERDICT_UNPAID, TOMS_VERDICT_UNKNOWN,
 * TOMS_VERDICT_ALREADY_RETURNED, or 0xFF (offline unverified). */
void display_set_verdict(uint8_t verdict, const uint8_t *uid, uint8_t len, uint8_t atype);

/* Diagnostics tools status updates. */
void display_test_set_nfc_status(bool ready);
void display_test_record_button_press(uint16_t count);

#endif /* TOMS_DISPLAY_TEST_H */
