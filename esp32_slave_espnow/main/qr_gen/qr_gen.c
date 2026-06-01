#include "qr_gen.h"
#include "display.h"
#include "ui.h"

#include <string.h>
#include <stdio.h>
#include "esp_log.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "qrcode.h"
#include "lvgl.h"

static const char *TAG = "toms_qr";

typedef struct {
    lv_obj_t  *canvas;
    lv_color_t *buf;
    lv_coord_t  buf_w;
    lv_coord_t  buf_h;
} qr_render_ctx_t;

/* ── LVGL QR Renderer ────────────────────────────────────────────────── */

static void qr_canvas_delete_cb(lv_event_t *e)
{
    void *buf = lv_event_get_user_data(e);
    if (buf) {
        heap_caps_free(buf);
    }
}

static void render_qr_lvgl_cb(esp_qrcode_handle_t qrcode, void *user_data)
{
    qr_render_ctx_t *ctx = (qr_render_ctx_t *)user_data;
    lv_obj_t *canvas = ctx ? ctx->canvas : NULL;
    if (!canvas || !ctx->buf) return;

    int qr_size = esp_qrcode_get_size(qrcode);
    if (qr_size <= 0) return;
    
    /* Calculate scale to fit in canvas (typically 100x100) */
    lv_coord_t w = ctx->buf_w;
    lv_coord_t h = ctx->buf_h;
    int scale = (w < h ? w : h) / qr_size;
    if (scale < 1) scale = 1;

    int total_px = qr_size * scale;
    int x_off = (w - total_px) / 2;
    int y_off = (h - total_px) / 2;

    /* Draw black modules directly into the canvas buffer */
    lv_color_t black = lv_color_black();
    for (int qy = 0; qy < qr_size; qy++) {
        for (int qx = 0; qx < qr_size; qx++) {
            if (esp_qrcode_get_module(qrcode, qx, qy)) {
                int start_x = x_off + qx * scale;
                int start_y = y_off + qy * scale;
                for (int yy = 0; yy < scale; yy++) {
                    int py = start_y + yy;
                    if (py < 0 || py >= h) continue;
                    lv_color_t *row = ctx->buf + (py * w);
                    for (int xx = 0; xx < scale; xx++) {
                        int px = start_x + xx;
                        if (px < 0 || px >= w) continue;
                        row[px] = black;
                    }
                }
            }
        }
    }

    lv_obj_invalidate(canvas);
}

void toms_qr_display_lvgl(lv_obj_t *parent, const char *text)
{
    if (!text || !parent) return;

    ESP_LOGI(TAG, "Generating LVGL QR: %.32s...", text);

    lv_obj_update_layout(parent);
    lv_coord_t w = lv_obj_get_width(parent);
    lv_coord_t h = lv_obj_get_height(parent);
    if (w <= 0 || h <= 0) return;

    lv_obj_t *canvas = lv_canvas_create(parent);
    lv_obj_set_size(canvas, w, h);
    lv_obj_align(canvas, LV_ALIGN_CENTER, 0, 0);
    lv_obj_clear_flag(canvas, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_set_style_border_width(canvas, 0, 0);

    size_t buf_px = (size_t)w * (size_t)h;
    lv_color_t *buf = heap_caps_malloc(buf_px * sizeof(lv_color_t), MALLOC_CAP_8BIT);
    if (!buf) {
        ESP_LOGE(TAG, "QR canvas alloc failed (%dx%d)", (int)w, (int)h);
        return;
    }

    lv_canvas_set_buffer(canvas, buf, w, h, LV_IMG_CF_TRUE_COLOR);
    lv_color_t white = lv_color_white();
    for (size_t i = 0; i < buf_px; i++) {
        buf[i] = white;
    }
    lv_obj_add_event_cb(canvas, qr_canvas_delete_cb, LV_EVENT_DELETE, buf);

    qr_render_ctx_t ctx = {
        .canvas = canvas,
        .buf = buf,
        .buf_w = w,
        .buf_h = h
    };

    esp_qrcode_config_t qr_cfg = ESP_QRCODE_CONFIG_DEFAULT();
    qr_cfg.display_func_with_cb = render_qr_lvgl_cb;
    qr_cfg.user_data = &ctx;
    qr_cfg.max_qrcode_version = 10;
    qr_cfg.qrcode_ecc_level = ESP_QRCODE_ECC_MED;

    esp_err_t err = esp_qrcode_generate(&qr_cfg, text);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "QR generate failed: %s", esp_err_to_name(err));
    }
}

bool toms_qr_display(const char *text, uint16_t fg_color, uint16_t bg_color)
{
    /* Fallback to LVGL UI show QR */
    toms_ui_show_qr(text);
    return true;
}

/* ── Receipt Payload Builder ──────────────────────────────────────────── */

char *toms_qr_build_receipt(const char *vehicle_id, uint32_t timestamp,
                            uint16_t fare_centavos, const char *passenger_id,
                            char *out, size_t out_size)
{
    if (!out || out_size < 32) return NULL;

    int written = snprintf(out, out_size,
                           "TOMS|%s|%lu|%d|%s",
                           vehicle_id ? vehicle_id : "UNKNOWN",
                           (unsigned long)timestamp,
                           fare_centavos,
                           passenger_id ? passenger_id : "ANON");

    if (written < 0 || (size_t)written >= out_size) return NULL;
    return out;
}
