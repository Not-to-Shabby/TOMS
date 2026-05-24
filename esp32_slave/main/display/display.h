/**
 * @file display.h
 * @brief TOMS SPI LCD driver for ST7735S (128x160, 1.8-inch TFT).
 */

#ifndef TOMS_DISPLAY_H
#define TOMS_DISPLAY_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ── Pin Configuration (ESP32-S3 Super Mini) ──────────────────────────── */

#define TOMS_LCD_PIN_SDA    11      /**< SPI MOSI (SDA) */
#define TOMS_LCD_PIN_SCK    12      /**< SPI Clock (SCK) */
#define TOMS_LCD_PIN_CS     10      /**< Chip Select (CS) */
#define TOMS_LCD_PIN_A0      9      /**< Data/Command (A0) */
#define TOMS_LCD_PIN_RESET   8      /**< Reset (RESET) */
#define TOMS_LCD_PIN_LED    13      /**< Backlight (LED) */

/* ── Display Dimensions ───────────────────────────────────────────────── */

#define TOMS_LCD_WIDTH      128
#define TOMS_LCD_HEIGHT     160

/* ── Color Definitions (RGB565) ───────────────────────────────────────── */

#define TOMS_COLOR_BLACK    0x0000
#define TOMS_COLOR_WHITE    0xFFFF
#define TOMS_COLOR_RED      0xF800
#define TOMS_COLOR_GREEN    0x07E0
#define TOMS_COLOR_BLUE     0x001F
#define TOMS_COLOR_YELLOW   0xFFE0
#define TOMS_COLOR_CYAN     0x07FF
#define TOMS_COLOR_ORANGE   0xFD20
#define TOMS_COLOR_DARK_BG  0x1082  /**< Dark gray background */
#define TOMS_COLOR_ACCENT   0x2C9F  /**< Teal accent */

/* ── Public API ───────────────────────────────────────────────────────── */

/**
 * @brief Initialize the SPI LCD display and LVGL.
 * @return ESP_OK on success.
 */
int toms_display_init(void);

/**
 * @brief Set backlight brightness (0–255).
 */
void toms_display_set_backlight(uint8_t brightness);

/**
 * @brief Turn backlight on/off.
 */
void toms_display_backlight(bool on);

/**
 * @brief Get the display panel handle (for advanced use).
 */
void *toms_display_get_panel(void);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_DISPLAY_H */
