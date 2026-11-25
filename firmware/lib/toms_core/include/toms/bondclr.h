/* Forgetting the one Bluetooth bond with the machine's own button, keeping the signing key.
 * Pure code: the firmware feeds it the clock and the button level, it says when to clear. Runs on
 * the host under test. PROTOCOL.md section 8.7 has the whole sequence.
 *
 *   hold the button while powering up        -> the pairing window opens (section 8.1)
 *   keep holding for TOMS_BONDCLR_HOLD_MS    -> the bond is cleared, BOND CLEARED is shown for
 *                                               TOMS_BONDCLR_SHOW_MS, then a fresh pairing window opens
 *
 * "Keep holding" means continuously from the power-up sample. Clearing a bond is destructive, so the
 * tracker fails safe: it gives up for good (until the next power-up) on a release, and on any gap in
 * the samples that is too long to know whether the button was let go. */
#ifndef TOMS_BONDCLR_H
#define TOMS_BONDCLR_H

#include <stdbool.h>
#include <stdint.h>

#define TOMS_BONDCLR_HOLD_MS 5000u        /* continuous hold, counted from the power-up sample */
#define TOMS_BONDCLR_RELEASE_MS 150u      /* released this long (contact bounce is shorter) = given up */
#define TOMS_BONDCLR_MAX_GAP_MS 500u      /* samples further apart than this = cannot tell, give up */
#define TOMS_BONDCLR_SHOW_MS 2000u        /* BOND CLEARED stays on the LCD this long before the window */
#define TOMS_BONDCLR_POLL_MS 50u          /* how often the firmware samples the button */

enum toms_bondclr_state {
	TOMS_BONDCLR_IDLE = 0,  /* the button was not held at power-up */
	TOMS_BONDCLR_HOLDING,   /* held so far, counting */
	TOMS_BONDCLR_GAVE_UP,   /* released, or samples missed: no clear this power-up */
	TOMS_BONDCLR_DONE,      /* the 5 s were reached and the clear was handed out */
};

enum toms_bondclr_event {
	TOMS_BONDCLR_NONE = 0,
	TOMS_BONDCLR_FIRE, /* returned once: clear the bond now */
};

struct toms_bondclr {
	enum toms_bondclr_state state;
	uint32_t start_ms;      /* the power-up sample */
	uint32_t last_ms;       /* the previous sample */
	uint32_t released_ms;   /* when the current release started, valid while `released` */
	bool released;
};

/* `held` = the button was pressed in the power-up sample taken at now_ms. */
void toms_bondclr_init(struct toms_bondclr *s, uint32_t now_ms, bool held);

/* One sample. Returns FIRE exactly once, on the first sample at or after the hold time that still
 * counts as continuous. The 32-bit millisecond clock may wrap; only differences are used. */
enum toms_bondclr_event toms_bondclr_tick(struct toms_bondclr *s, uint32_t now_ms, bool pressed);

/* True while the hold is still being counted: the caller keeps polling and ignores button presses
 * for other purposes (diagnostic mode, pairing confirmation). */
bool toms_bondclr_active(const struct toms_bondclr *s);

#endif /* TOMS_BONDCLR_H */
