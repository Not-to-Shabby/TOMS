/* Who may pair with the Return Machine, and when. Pure code: the firmware feeds it the clock, the
 * number of bonds and what the peer announced, it answers allow or deny. Runs on the host under test.
 *
 * Rules (PROTOCOL.md section 8):
 *  - a new bond is possible only while the pairing window is open; the window opens when the button is
 *    held at power-up and closes after TOMS_PAIR_WINDOW_DEFAULT_MS (or when a bond is made);
 *  - at most one bond: while one exists, only that same peer may pair again (to replace its own key);
 *  - the peer must be able to take part in an authenticated pairing (numeric comparison or passkey):
 *    "no input no output" and "display only" peers can only end in Just Works, so they are refused. */
#ifndef TOMS_PAIRING_H
#define TOMS_PAIRING_H

#include <stdbool.h>
#include <stdint.h>

#define TOMS_PAIR_WINDOW_DEFAULT_MS 60000u
#define TOMS_PAIR_MAX_BONDS 1u

/* SMP IO capability, Core spec Vol 3 Part H 3.5.1. */
#define TOMS_IO_DISPLAY_ONLY 0u
#define TOMS_IO_DISPLAY_YESNO 1u
#define TOMS_IO_KEYBOARD_ONLY 2u
#define TOMS_IO_NO_INPUT_NO_OUTPUT 3u
#define TOMS_IO_KEYBOARD_DISPLAY 4u

enum toms_pair_verdict {
	TOMS_PAIR_ALLOW = 0,
	TOMS_PAIR_DENY_CLOSED, /* the pairing window is not open */
	TOMS_PAIR_DENY_FULL,   /* a bond exists and this is not that peer */
	TOMS_PAIR_DENY_IO,     /* the peer cannot do an authenticated pairing */
};

struct toms_pair_window {
	bool open;
	uint32_t deadline_ms;
};

void toms_pair_window_init(struct toms_pair_window *w);

/* Opens the window for duration_ms from now_ms. Opening again restarts the time. */
void toms_pair_window_open(struct toms_pair_window *w, uint32_t now_ms, uint32_t duration_ms);

void toms_pair_window_close(struct toms_pair_window *w);

/* True while the window is open. An expired window closes itself here. The clock is a 32-bit
 * millisecond counter that may wrap; only the difference to the deadline is used. */
bool toms_pair_window_is_open(struct toms_pair_window *w, uint32_t now_ms);

/* Milliseconds left, 0 when closed or expired. */
uint32_t toms_pair_window_left_ms(struct toms_pair_window *w, uint32_t now_ms);

/* True for the IO capabilities that can end in an authenticated pairing with this machine, which
 * claims "display + yes/no": DisplayYesNo, KeyboardOnly (passkey shown here) and KeyboardDisplay. */
bool toms_pair_io_ok(uint8_t io_capability);

/* The decision for one incoming pairing request. bond_count: bonds stored now. peer_bonded: this
 * peer already has one of them. Checked in this order: window, peer IO, bond slot. */
enum toms_pair_verdict toms_pair_decide(struct toms_pair_window *w, uint32_t now_ms,
					uint8_t io_capability, uint8_t bond_count, bool peer_bonded);

#endif /* TOMS_PAIRING_H */
