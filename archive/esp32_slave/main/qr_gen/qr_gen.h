/**
 * @file qr_gen.h
 * @brief TOMS QR code generation and LCD rendering.
 */

#ifndef TOMS_QR_GEN_H
#define TOMS_QR_GEN_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

struct _lv_obj_t;
typedef struct _lv_obj_t lv_obj_t;

/**
 * @brief Generate and display a QR code using LVGL.
 *
 * @param parent  Parent LVGL object (e.g. a container).
 * @param text    Text to encode.
 */
void toms_qr_display_lvgl(lv_obj_t *parent, const char *text);

/** QR module scale factor (pixels per QR module) */
#define TOMS_QR_SCALE       2

/** QR quiet zone (modules of white border) */
#define TOMS_QR_QUIET_ZONE  2

/**
 * @brief Generate and display a QR code on the LCD.
 *
 * The QR code is centered on the display with a white quiet zone.
 *
 * @param text      Text to encode (null-terminated).
 * @param fg_color  Foreground (dark module) color (RGB565).
 * @param bg_color  Background (light module) color (RGB565).
 * @return true if QR generation and display succeeded.
 */
bool toms_qr_display(const char *text, uint16_t fg_color, uint16_t bg_color);

/**
 * @brief Generate a TOMS receipt QR payload string.
 *
 * Format: TOMS|<vehicle_id>|<timestamp>|<fare>|<passenger_id>
 *
 * @param vehicle_id    Vehicle identifier string.
 * @param timestamp     Epoch seconds.
 * @param fare_centavos Fare amount.
 * @param passenger_id  Passenger ID (hex string).
 * @param out           Output buffer (at least 128 bytes).
 * @param out_size      Buffer capacity.
 * @return Pointer to out, or NULL on error.
 */
char *toms_qr_build_receipt(const char *vehicle_id, uint32_t timestamp,
                            uint16_t fare_centavos, const char *passenger_id,
                            char *out, size_t out_size);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_QR_GEN_H */
