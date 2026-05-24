/**
 * @file ui.h
 * @brief TOMS slave UI screen management.
 */

#ifndef TOMS_UI_H
#define TOMS_UI_H

#include <stdint.h>
#include "protocol.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief UI screen identifiers.
 */
typedef enum {
    TOMS_UI_WELCOME,        /**< Boot/idle: "Tap or Press to Board" */
    TOMS_UI_PROCESSING,     /**< Spinner: "Processing..." */
    TOMS_UI_FARE,           /**< Fare display: route, amount, seat */
    TOMS_UI_QR,             /**< QR receipt code */
    TOMS_UI_ERROR,          /**< Error: "Please try again" */
    TOMS_UI_SLEEP,          /**< Pre-sleep: screen off */
    TOMS_UI_DEBUG_RECEIPT,  /**< Debug: Receipt information */
} toms_ui_screen_t;

/**
 * @brief Debug receipt content used for UI and QR consistency.
 */
typedef struct {
    char     title[16];
    uint32_t ticket_no;
    char     datetime[24];
    char     route[40];
    char     terminal[40];
    char     discount[40];
    uint16_t fare_centavos;
    char     payment_mode[12];
    char     bus_id[16];
    char     etim_no[16];
    uint32_t waybill_no;
    char     duty_no[8];
    char     driver_id[16];
} toms_debug_receipt_t;

/**
 * @brief Initialize the UI module.
 */
void toms_ui_init(void);

/**
 * @brief Show the welcome screen.
 */
void toms_ui_show_welcome(void);

/**
 * @brief Show the processing screen.
 */
void toms_ui_show_processing(void);

/**
 * @brief Show the fare display screen.
 *
 * @param route_name    Route name string.
 * @param fare_centavos Fare amount.
 * @param seat_number   Seat number (0 = unassigned).
 */
void toms_ui_show_fare(const char *route_name, uint16_t fare_centavos,
                       uint8_t seat_number);

/**
 * @brief Show the QR receipt screen.
 *
 * @param qr_data   Data to encode as QR.
 */
void toms_ui_show_qr(const char *qr_data);

/**
 * @brief Show the error screen.
 *
 * @param message   Error message (NULL for default).
 */
void toms_ui_show_error(const char *message);

/**
 * @brief Prepare for sleep (turn off display).
 */
void toms_ui_show_sleep(void);
/**
 * @brief Show the debug receipt screen with provided values.
 */
void toms_ui_show_debug_receipt(const toms_debug_receipt_t *receipt);

/**
 * @brief Thread-safe wrapper for LVGL timer handler.
 */
void toms_ui_process(void);

/**
 * @brief Get current screen.
 */
toms_ui_screen_t toms_ui_get_current(void);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_UI_H */
