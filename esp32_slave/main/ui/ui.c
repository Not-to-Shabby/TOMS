#include "ui.h"
#include "display.h"
#include "qr_gen.h"
#include "lvgl.h"

#include <stdio.h>
#include <string.h>
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

static const char *TAG = "toms_ui";

static toms_ui_screen_t s_current = TOMS_UI_WELCOME;
static SemaphoreHandle_t s_ui_mutex = NULL;

/* ── UI Objects ───────────────────────────────────────────────────────── */

static lv_style_t s_style_header;
static lv_style_t s_style_footer;
static lv_style_t s_style_bg;

static const toms_debug_receipt_t s_default_debug_receipt = {
    .title = "Mitsco",
    .ticket_no = 12345678,
    .datetime = "05/04/2026 11:47 AM",
    .route = "City Proper-Country Hills",
    .terminal = "DPWH - TAMBO TERMINAL",
    .discount = "DISCOUNT: 1 x 12.00 = P 12.00",
    .fare_centavos = 1200,
    .payment_mode = "Cash",
    .bus_id = "CDB0000",
    .etim_no = "MITSCO00",
    .waybill_no = 123456,
    .duty_no = "NA",
    .driver_id = "DCDR1001",
};

/* ── Initialization ───────────────────────────────────────────────────── */

void toms_ui_init(void)
{
    s_ui_mutex = xSemaphoreCreateMutex();

    /* Initialize styles */
    lv_style_init(&s_style_bg);
    lv_style_set_bg_color(&s_style_bg, lv_color_hex(0x101010)); /* Very dark grey */
    lv_style_set_bg_opa(&s_style_bg, LV_OPA_COVER);

    lv_style_init(&s_style_header);
    lv_style_set_bg_color(&s_style_header, lv_palette_main(LV_PALETTE_TEAL));
    lv_style_set_bg_opa(&s_style_header, LV_OPA_COVER);
    lv_style_set_text_color(&s_style_header, lv_color_white());
    lv_style_set_radius(&s_style_header, 0);
    lv_style_set_border_width(&s_style_header, 0);

    lv_style_init(&s_style_footer);
    lv_style_set_bg_color(&s_style_footer, lv_palette_main(LV_PALETTE_TEAL));
    lv_style_set_bg_opa(&s_style_footer, LV_OPA_COVER);
    lv_style_set_text_color(&s_style_footer, lv_color_white());
    lv_style_set_radius(&s_style_footer, 0);
    lv_style_set_border_width(&s_style_footer, 0);

    lv_obj_add_style(lv_scr_act(), &s_style_bg, 0);

    ESP_LOGI(TAG, "UI and LVGL styles initialized");
}

/* ── Helpers ──────────────────────────────────────────────────────────── */

#define UI_LOCK()   if (s_ui_mutex) xSemaphoreTake(s_ui_mutex, portMAX_DELAY)
#define UI_UNLOCK() if (s_ui_mutex) xSemaphoreGive(s_ui_mutex)

static void ui_clear_screen(void)
{
    lv_obj_clean(lv_scr_act());
    lv_obj_set_style_bg_color(lv_scr_act(), lv_color_hex(0x101010), 0);
    lv_obj_set_style_bg_opa(lv_scr_act(), LV_OPA_COVER, 0);
}

static void ui_create_header(const char *title, lv_color_t color)
{
    lv_obj_t *header = lv_obj_create(lv_scr_act());
    lv_obj_set_size(header, lv_pct(100), 32);
    lv_obj_add_style(header, &s_style_header, 0);
    lv_obj_set_style_bg_color(header, color, 0);
    lv_obj_align(header, LV_ALIGN_TOP_MID, 0, 0);

    lv_obj_t *label = lv_label_create(header);
    lv_label_set_text(label, title);
    lv_obj_set_style_text_font(label, &lv_font_montserrat_14, 0);
    lv_obj_align(label, LV_ALIGN_CENTER, 0, 0);
}

static void ui_create_footer(const char *text, lv_color_t color)
{
    lv_obj_t *footer = lv_obj_create(lv_scr_act());
    lv_obj_set_size(footer, lv_pct(100), 16);
    lv_obj_add_style(footer, &s_style_footer, 0);
    lv_obj_set_style_bg_color(footer, color, 0);
    lv_obj_align(footer, LV_ALIGN_BOTTOM_MID, 0, 0);

    lv_obj_t *label = lv_label_create(footer);
    lv_label_set_text(label, text);
    lv_obj_set_style_text_font(label, &lv_font_montserrat_12, 0);
    lv_obj_align(label, LV_ALIGN_CENTER, 0, 0);
}

/* ── Screens ──────────────────────────────────────────────────────────── */

void toms_ui_show_welcome(void)
{
    UI_LOCK();
    s_current = TOMS_UI_WELCOME;
    ui_clear_screen();

    ui_create_header(LV_SYMBOL_WIFI " T O M S", lv_palette_main(LV_PALETTE_TEAL));

    lv_obj_t *cont = lv_obj_create(lv_scr_act());
    lv_obj_set_size(cont, lv_pct(90), 80);
    lv_obj_align(cont, LV_ALIGN_CENTER, 0, 0);
    lv_obj_set_style_bg_opa(cont, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(cont, lv_color_hex(0x2A2A2A), 0);
    lv_obj_set_style_border_width(cont, 1, 0);
    lv_obj_set_style_border_color(cont, lv_palette_main(LV_PALETTE_TEAL), 0);
    lv_obj_set_style_radius(cont, 8, 0);

    lv_obj_t *label = lv_label_create(cont);
    lv_label_set_text(label, "Swipe to Sync\n- or -\nPress Button");
    lv_obj_set_style_text_align(label, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_color(label, lv_color_white(), 0);
    lv_obj_set_style_text_font(label, &lv_font_montserrat_14, 0);
    lv_obj_align(label, LV_ALIGN_CENTER, 0, 0);

    ui_create_footer("System Ready", lv_palette_main(LV_PALETTE_TEAL));
    UI_UNLOCK();
    ESP_LOGI(TAG, "Welcome screen (LVGL) shown");
}

void toms_ui_show_processing(void)
{
    UI_LOCK();
    s_current = TOMS_UI_PROCESSING;
    ui_clear_screen();

    ui_create_header(LV_SYMBOL_REFRESH " T O M S", lv_palette_main(LV_PALETTE_TEAL));

    lv_obj_t *cont = lv_obj_create(lv_scr_act());
    lv_obj_set_size(cont, lv_pct(90), 80);
    lv_obj_align(cont, LV_ALIGN_CENTER, 0, 0);
    lv_obj_set_style_bg_opa(cont, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(cont, lv_color_hex(0x2A2A2A), 0);
    lv_obj_set_style_border_width(cont, 1, 0);
    lv_obj_set_style_border_color(cont, lv_palette_main(LV_PALETTE_TEAL), 0);
    lv_obj_set_style_radius(cont, 8, 0);

    lv_obj_t *label = lv_label_create(cont);
    lv_label_set_text(label, LV_SYMBOL_REFRESH "\n\nProcessing...");
    lv_obj_set_style_text_align(label, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_color(label, lv_palette_main(LV_PALETTE_TEAL), 0);
    lv_obj_set_style_text_font(label, &lv_font_montserrat_14, 0);
    lv_obj_align(label, LV_ALIGN_CENTER, 0, 0);

    ui_create_footer("Please Wait", lv_palette_main(LV_PALETTE_ORANGE));
    UI_UNLOCK();
    ESP_LOGI(TAG, "Processing screen shown");
}

void toms_ui_show_fare(const char *route_name, uint16_t fare_centavos,
                       uint8_t seat_number)
{
    UI_LOCK();
    s_current = TOMS_UI_FARE;
    ui_clear_screen();

    ui_create_header(LV_SYMBOL_OK " APPROVED", lv_palette_main(LV_PALETTE_GREEN));

    lv_obj_t *fare_cont = lv_obj_create(lv_scr_act());
    lv_obj_set_size(fare_cont, lv_pct(90), 90);
    lv_obj_align(fare_cont, LV_ALIGN_CENTER, 0, 0);
    lv_obj_set_style_bg_opa(fare_cont, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(fare_cont, lv_color_hex(0x2A2A2A), 0);
    lv_obj_set_style_border_width(fare_cont, 1, 0);
    lv_obj_set_style_border_color(fare_cont, lv_palette_main(LV_PALETTE_GREEN), 0);
    lv_obj_set_style_radius(fare_cont, 8, 0);

    lv_obj_t *lbl_route = lv_label_create(fare_cont);
    lv_label_set_text_fmt(lbl_route, "Route: %s", route_name ? route_name : "General");
    lv_obj_set_style_text_color(lbl_route, lv_color_white(), 0);
    lv_obj_set_style_text_font(lbl_route, &lv_font_montserrat_14, 0);
    lv_obj_align(lbl_route, LV_ALIGN_TOP_MID, 0, 5);

    lv_obj_t *lbl_fare = lv_label_create(fare_cont);
    lv_label_set_text_fmt(lbl_fare, "P %d.%02d", fare_centavos / 100, fare_centavos % 100);
    lv_obj_set_style_text_color(lbl_fare, lv_palette_main(LV_PALETTE_GREEN), 0);
    lv_obj_set_style_text_font(lbl_fare, &lv_font_montserrat_18, 0);
    lv_obj_align(lbl_fare, LV_ALIGN_CENTER, 0, 0);

    if (seat_number > 0) {
        lv_obj_t *lbl_seat = lv_label_create(fare_cont);
        lv_label_set_text_fmt(lbl_seat, "Seat: %d", seat_number);
        lv_obj_set_style_text_color(lbl_seat, lv_color_white(), 0);
        lv_obj_set_style_text_font(lbl_seat, &lv_font_montserrat_14, 0);
        lv_obj_align(lbl_seat, LV_ALIGN_BOTTOM_MID, 0, -5);
    }

    ui_create_footer("Thank You!", lv_palette_main(LV_PALETTE_GREEN));
    UI_UNLOCK();
}

void toms_ui_show_qr(const char *qr_data)
{
    UI_LOCK();
    s_current = TOMS_UI_QR;
    ui_clear_screen();

    ui_create_header(LV_SYMBOL_FILE " RECEIPT", lv_palette_main(LV_PALETTE_TEAL));

    /* QR code container */
    lv_obj_t *qr_cont = lv_obj_create(lv_scr_act());
    lv_obj_set_size(qr_cont, 100, 100);
    lv_obj_align(qr_cont, LV_ALIGN_CENTER, 0, 5);
    lv_obj_set_style_bg_color(qr_cont, lv_color_white(), 0);
    lv_obj_set_style_border_width(qr_cont, 0, 0);
    lv_obj_set_style_radius(qr_cont, 4, 0);

    /* Render QR on container */
    toms_qr_display_lvgl(qr_cont, qr_data);

    ui_create_footer("Scan QR Code", lv_palette_main(LV_PALETTE_TEAL));
    UI_UNLOCK();
}

void toms_ui_show_error(const char *message)
{
    UI_LOCK();
    s_current = TOMS_UI_ERROR;
    ui_clear_screen();

    ui_create_header(LV_SYMBOL_WARNING " ERROR", lv_palette_main(LV_PALETTE_RED));

    lv_obj_t *label = lv_label_create(lv_scr_act());
    lv_label_set_text(label, message ? message : "Transaction\nFailed");
    lv_obj_set_style_text_align(label, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_color(label, lv_palette_main(LV_PALETTE_RED), 0);
    lv_obj_set_style_text_font(label, &lv_font_montserrat_16, 0);
    lv_obj_align(label, LV_ALIGN_CENTER, 0, 0);

    ui_create_footer("Try Again", lv_palette_main(LV_PALETTE_RED));
    UI_UNLOCK();
    ESP_LOGI(TAG, "Error screen shown: %s", message ? message : "(default)");
}

void toms_ui_show_sleep(void)
{
    UI_LOCK();
    s_current = TOMS_UI_SLEEP;
    ui_clear_screen();
    toms_display_backlight(false);
    UI_UNLOCK();
}

void toms_ui_show_alarm(uint8_t alarm_type, uint8_t minutes_left)
{
    UI_LOCK();
    s_current = TOMS_UI_ALARM;
    ui_clear_screen();

    /* Red background — maximum urgency signal */
    lv_obj_set_style_bg_color(lv_scr_act(), lv_palette_main(LV_PALETTE_RED), 0);
    lv_obj_set_style_bg_opa(lv_scr_act(), LV_OPA_COVER, 0);

    /* Header */
    ui_create_header(LV_SYMBOL_WARNING " ALARM", lv_color_hex(0xB71C1C)); /* dark red */

    /* Central alarm icon */
    lv_obj_t *icon = lv_label_create(lv_scr_act());
    lv_label_set_text(icon, LV_SYMBOL_WARNING);
    lv_obj_set_style_text_font(icon, &lv_font_montserrat_18, 0);
    lv_obj_set_style_text_color(icon, lv_color_white(), 0);
    lv_obj_align(icon, LV_ALIGN_TOP_MID, 0, 38);

    /* Main message */
    lv_obj_t *lbl_main = lv_label_create(lv_scr_act());
    lv_label_set_text(lbl_main, "PLEASE PAY");
    lv_obj_set_style_text_align(lbl_main, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_color(lbl_main, lv_color_white(), 0);
    lv_obj_set_style_text_font(lbl_main, &lv_font_montserrat_18, 0);
    lv_obj_align(lbl_main, LV_ALIGN_CENTER, 0, -8);

    /* Subtitle — minutes remaining or "PAY NOW!" */
    lv_obj_t *lbl_sub = lv_label_create(lv_scr_act());
    if (alarm_type == 0 && minutes_left > 0) {
        lv_label_set_text_fmt(lbl_sub, "~%d min to stop", (int)minutes_left);
    } else {
        lv_label_set_text(lbl_sub, "Approaching stop!");
    }
    lv_obj_set_style_text_align(lbl_sub, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_color(lbl_sub, lv_color_white(), 0);
    lv_obj_set_style_text_font(lbl_sub, &lv_font_montserrat_14, 0);
    lv_obj_align(lbl_sub, LV_ALIGN_CENTER, 0, 18);

    /* Flashing "PRESS BUTTON" footer */
    ui_create_footer("PRESS BUTTON TO PAY", lv_color_hex(0xB71C1C));

    /* Opacity blink animation on main label to draw attention */
    lv_anim_t anim;
    lv_anim_init(&anim);
    lv_anim_set_var(&anim, lbl_main);
    lv_anim_set_exec_cb(&anim, (lv_anim_exec_xcb_t)lv_obj_set_style_opa);
    lv_anim_set_values(&anim, LV_OPA_COVER, LV_OPA_30);
    lv_anim_set_time(&anim, 500);
    lv_anim_set_playback_time(&anim, 500);
    lv_anim_set_repeat_count(&anim, LV_ANIM_REPEAT_INFINITE);
    lv_anim_start(&anim);

    UI_UNLOCK();
    ESP_LOGI(TAG, "Alarm screen shown: type=%d, minutes=%d", alarm_type, minutes_left);
}

void toms_ui_show_debug_receipt(const toms_debug_receipt_t *receipt)
{
    UI_LOCK();
    s_current = TOMS_UI_DEBUG_RECEIPT;
    ui_clear_screen();

    const toms_debug_receipt_t *r = receipt ? receipt : &s_default_debug_receipt;

    ui_create_header("Debug Receipt", lv_palette_main(LV_PALETTE_TEAL));

    lv_obj_t *cont = lv_obj_create(lv_scr_act());
    lv_obj_set_size(cont, lv_pct(100), 100); /* remove the side inset */
    lv_obj_align(cont, LV_ALIGN_CENTER, 0, 0);
    lv_obj_set_style_bg_opa(cont, LV_OPA_TRANSP, 0); /* remove background color */
    lv_obj_set_style_border_width(cont, 0, 0);       /* remove border */
    lv_obj_set_style_pad_all(cont, 0, 0);            /* remove padding */
    lv_obj_set_scrollbar_mode(cont, LV_SCROLLBAR_MODE_OFF);

    /* Fit info without scroll by combining details */
    lv_obj_t *label = lv_label_create(cont);
    lv_label_set_text_fmt(label, 
        "%s\n"
        "Tkt: %u\n"
        "%s - %s\n"
        "Amount: P %u.%02u\n"
        "DRV: %s\n"
        "BUS: %s",
        r->title,
        (unsigned int)r->ticket_no,
        r->route, r->terminal,
        (unsigned int)(r->fare_centavos / 100),
        (unsigned int)(r->fare_centavos % 100),
        r->driver_id, r->bus_id);

    lv_obj_set_style_text_align(label, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_color(label, lv_color_white(), 0);
    lv_obj_set_style_text_font(label, &lv_font_montserrat_10, 0); /* Use much smaller font */
    lv_obj_align(label, LV_ALIGN_CENTER, 0, 0);

    ui_create_footer(r->datetime, lv_palette_main(LV_PALETTE_TEAL));

    UI_UNLOCK();
    ESP_LOGI(TAG, "Debug receipt shown");
}

void toms_ui_process(void)
{
    UI_LOCK();
    lv_timer_handler();
    UI_UNLOCK();
}

toms_ui_screen_t toms_ui_get_current(void)
{
    return s_current;
}
