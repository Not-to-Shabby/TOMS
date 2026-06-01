#include "display.h"

#include <string.h>
#include "esp_log.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_vendor.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_st7735.h"
#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include "esp_timer.h"

/* LVGL */
#include "lvgl.h"

static const char *TAG = "toms_display";

/* ── Internal State ───────────────────────────────────────────────────── */

static esp_lcd_panel_handle_t    s_panel    = NULL;
static esp_lcd_panel_io_handle_t s_panel_io = NULL;
static bool                      s_initialized = false;

/* LVGL display buffers */
static lv_disp_draw_buf_t s_disp_buf;
static lv_color_t        *s_buf1 = NULL;
static lv_color_t        *s_buf2 = NULL;
static lv_disp_drv_t      s_disp_drv;

/* ── LVGL Flush Callback ─────────────────────────────────────────────── */

static void lvgl_flush_cb(lv_disp_drv_t *drv, const lv_area_t *area, lv_color_t *color_map)
{
    esp_lcd_panel_handle_t panel = (esp_lcd_panel_handle_t)drv->user_data;
    int x1 = area->x1;
    int y1 = area->y1;
    int x2 = area->x2;
    int y2 = area->y2;

    /* Copy buffer to LCD */
    esp_lcd_panel_draw_bitmap(panel, x1, y1, x2 + 1, y2 + 1, color_map);
}

/* ── LVGL Flush Ready Callback ───────────────────────────────────────── */

static bool lvgl_flush_ready_callback(esp_lcd_panel_io_handle_t panel_io, esp_lcd_panel_io_event_data_t *edata, void *user_ctx)
{
    lv_disp_drv_t *disp_driver = (lv_disp_drv_t *)user_ctx;
    lv_disp_flush_ready(disp_driver);
    return false;
}

/* ── LVGL Tick Callback ─────────────────────────────────────────────── */

static void lv_tick_task(void *arg)
{
    (void)arg;
    lv_tick_inc(10);
}

/* ── Backlight PWM ────────────────────────────────────────────────────── */

static void backlight_init(void)
{
    ESP_LOGI(TAG, "Initializing P-channel backlight GPIO%d (default ON/LOW)...", TOMS_LCD_PIN_LED);
    gpio_reset_pin(TOMS_LCD_PIN_LED);
    gpio_set_direction(TOMS_LCD_PIN_LED, GPIO_MODE_OUTPUT);
    gpio_set_level(TOMS_LCD_PIN_LED, 0); /* LOW = MOSFET/PNP ON */
}

/* ── Public API ───────────────────────────────────────────────────────── */

int toms_display_init(void)
{
    if (s_initialized) return 0;

    ESP_LOGI(TAG, "Initializing ST7735S LCD (128x160) with LVGL");

    /* SPI bus */
    spi_bus_config_t bus_cfg = {
        .mosi_io_num     = TOMS_LCD_PIN_SDA,
        .miso_io_num     = -1,
        .sclk_io_num     = TOMS_LCD_PIN_SCK,
        .quadwp_io_num   = -1,
        .quadhd_io_num   = -1,
        .max_transfer_sz = TOMS_LCD_WIDTH * TOMS_LCD_HEIGHT * sizeof(lv_color_t),
    };

    esp_err_t err = spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);
    if (err != ESP_OK) return (int)err;

    /* Panel IO */
    esp_lcd_panel_io_spi_config_t io_cfg = {
        .dc_gpio_num       = TOMS_LCD_PIN_A0,
        .cs_gpio_num       = TOMS_LCD_PIN_CS,
        .pclk_hz           = 26 * 1000 * 1000, /* 26MHz max for ST7735S */
        .lcd_cmd_bits      = 8,
        .lcd_param_bits    = 8,
        .spi_mode          = 0,
        .trans_queue_depth = 10,
        .on_color_trans_done = lvgl_flush_ready_callback,
        .user_ctx          = &s_disp_drv,
    };

    err = esp_lcd_new_panel_io_spi(SPI2_HOST, &io_cfg, &s_panel_io);
    if (err != ESP_OK) return (int)err;

    /* Panel driver */
    esp_lcd_panel_dev_config_t panel_cfg = {
        .reset_gpio_num = TOMS_LCD_PIN_RESET,
        .rgb_ele_order  = LCD_RGB_ELEMENT_ORDER_BGR,
        .bits_per_pixel = 16,
    };

    err = esp_lcd_new_panel_st7735(s_panel_io, &panel_cfg, &s_panel);
    if (err != ESP_OK) return (int)err;

    esp_lcd_panel_reset(s_panel);
    esp_lcd_panel_init(s_panel);
    esp_lcd_panel_swap_xy(s_panel, false);
    esp_lcd_panel_mirror(s_panel, false, false);
    esp_lcd_panel_disp_on_off(s_panel, true);

    backlight_init();

    /* ── LVGL Setup ─────────────────────────────────────────────────── */
    lv_init();

    /* Allocate display buffers */
    s_buf1 = heap_caps_malloc(TOMS_LCD_WIDTH * 40 * sizeof(lv_color_t), MALLOC_CAP_DMA);
    s_buf2 = heap_caps_malloc(TOMS_LCD_WIDTH * 40 * sizeof(lv_color_t), MALLOC_CAP_DMA);
    lv_disp_draw_buf_init(&s_disp_buf, s_buf1, s_buf2, TOMS_LCD_WIDTH * 40);

    /* Register display driver */
    lv_disp_drv_init(&s_disp_drv);
    s_disp_drv.hor_res = TOMS_LCD_WIDTH;
    s_disp_drv.ver_res = TOMS_LCD_HEIGHT;
    s_disp_drv.flush_cb = lvgl_flush_cb;
    s_disp_drv.draw_buf = &s_disp_buf;
    s_disp_drv.user_data = s_panel;
    lv_disp_drv_register(&s_disp_drv);

    /* Start tick timer */
    const esp_timer_create_args_t tick_timer_args = {
        .callback = &lv_tick_task,
        .name = "lv_tick"
    };
    esp_timer_handle_t tick_timer = NULL;
    esp_timer_create(&tick_timer_args, &tick_timer);
    esp_timer_start_periodic(tick_timer, 10 * 1000); /* 10ms */

    s_initialized = true;
    ESP_LOGI(TAG, "Display and LVGL initialized");
    return 0;
}

void toms_display_set_backlight(uint8_t brightness)
{
    /* brightness > 0 -> turn ON (LOW), otherwise turn OFF (HIGH) */
    gpio_set_level(TOMS_LCD_PIN_LED, brightness > 0 ? 0 : 1);
}

void toms_display_backlight(bool on)
{
    toms_display_set_backlight(on ? 1 : 0);
}

void *toms_display_get_panel(void)
{
    return s_panel;
}

