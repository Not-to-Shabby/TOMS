/* Debounced push button (GPIO4, active low, external 10K pull-up + 100 nF). */
#ifndef TOMS_BUTTON_H
#define TOMS_BUTTON_H

typedef void (*toms_button_cb_t)(void);

/* Installs the GPIO interrupt. The callback runs in the system workqueue
 * after the 25 ms debounce window. Returns 0 on success. */
int button_init(toms_button_cb_t cb);

#endif /* TOMS_BUTTON_H */
