#include <toms/pairing.h>

void toms_pair_window_init(struct toms_pair_window *w)
{
	w->open = false;
	w->deadline_ms = 0;
}

void toms_pair_window_open(struct toms_pair_window *w, uint32_t now_ms, uint32_t duration_ms)
{
	w->open = duration_ms != 0u;
	w->deadline_ms = now_ms + duration_ms;
}

void toms_pair_window_close(struct toms_pair_window *w)
{
	w->open = false;
}

bool toms_pair_window_is_open(struct toms_pair_window *w, uint32_t now_ms)
{
	if (!w->open) {
		return false;
	}
	/* Signed difference: still correct when the 32-bit clock wraps inside the window. */
	if ((int32_t)(w->deadline_ms - now_ms) <= 0) {
		w->open = false;
		return false;
	}
	return true;
}

uint32_t toms_pair_window_left_ms(struct toms_pair_window *w, uint32_t now_ms)
{
	if (!toms_pair_window_is_open(w, now_ms)) {
		return 0;
	}
	return w->deadline_ms - now_ms;
}

bool toms_pair_io_ok(uint8_t io_capability)
{
	return io_capability == TOMS_IO_DISPLAY_YESNO || io_capability == TOMS_IO_KEYBOARD_ONLY ||
	       io_capability == TOMS_IO_KEYBOARD_DISPLAY;
}

enum toms_pair_verdict toms_pair_decide(struct toms_pair_window *w, uint32_t now_ms,
					uint8_t io_capability, uint8_t bond_count, bool peer_bonded)
{
	if (!toms_pair_window_is_open(w, now_ms)) {
		return TOMS_PAIR_DENY_CLOSED;
	}
	if (!toms_pair_io_ok(io_capability)) {
		return TOMS_PAIR_DENY_IO;
	}
	if (bond_count >= TOMS_PAIR_MAX_BONDS && !peer_bonded) {
		return TOMS_PAIR_DENY_FULL;
	}
	return TOMS_PAIR_ALLOW;
}
