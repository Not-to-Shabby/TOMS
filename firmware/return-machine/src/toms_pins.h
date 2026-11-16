/* Shared devicetree handles for the Return Machine board.
 * Pin assignments come from app.overlay, which was verified against the
 * Fritzing netlist (see firmware/HW_NOTES.md). */
#ifndef TOMS_PINS_H
#define TOMS_PINS_H

#define TOMS_PN532_NODE DT_NODELABEL(pn532)
#define TOMS_BUZZER_NODE DT_NODELABEL(buzzer)
#define TOMS_BACKLIGHT_NODE DT_NODELABEL(backlight)
#define TOMS_BUTTON_NODE DT_NODELABEL(return_button)

#endif /* TOMS_PINS_H */
