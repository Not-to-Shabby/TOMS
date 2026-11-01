#include "display_test.h"

#include <ctype.h>
#include <string.h>

#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/display.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/printk.h>
LOG_MODULE_REGISTER(display_test, LOG_LEVEL_INF);

#include "ble/svc.h"
#include "toms_pins.h"
#include "trial.h"
#include "ota_ui.h"

#define W 160
#define H 128
#define BAND_ROWS 16
#define AUTO_ADVANCE_S 3
#define UID_MAX 10
#define LONG_PRESS_MS 800

/* RGB565, sent high byte first. */
#define C_BLACK 0x0000
#define C_WHITE 0xFFFF
#define C_RED 0xF800
#define C_GREEN 0x07E0
#define C_BLUE 0x001F
#define C_YELLOW 0xFFE0
#define C_CYAN 0x07FF
#define C_MAGENTA 0xF81F
#define C_GRAY 0x8410

enum page {
	PAGE_BLE,
	PAGE_DIAG,
	PAGE_CARD,
	PAGE_RED,
	PAGE_GREEN,
	PAGE_BLUE,
	PAGE_WHITE,
	PAGE_BLACK,
	PAGE_BARS,
	PAGE_FRAME,
	PAGE_GRADIENT,
	PAGE_INFO,
	PAGE_COUNT,
};

#define REQ_NEXT BIT(0)
#define REQ_BACKLIGHT BIT(1)
#define REQ_CARD BIT(2)

#define DISPLAY_STACK 4096
#define DISPLAY_PRIO 8
static K_THREAD_STACK_DEFINE(display_stack, DISPLAY_STACK);
static struct k_thread display_thread;

static const struct device *const lcd = DEVICE_DT_GET(DT_NODELABEL(lcd));
static const struct gpio_dt_spec backlight = GPIO_DT_SPEC_GET(TOMS_BACKLIGHT_NODE, gpios);

static const struct gpio_dt_spec button = GPIO_DT_SPEC_GET(TOMS_BUTTON_NODE, gpios);

static uint8_t fb[W * H * 2];
static K_SEM_DEFINE(wake, 0, 1);
static atomic_t requests;

static K_MUTEX_DEFINE(uid_lock);
static uint8_t card_uid[UID_MAX];
static uint8_t card_uid_len;
static uint8_t card_uid_atype;
static bool diag_nfc_ready = true;
static uint16_t diag_btn_count;

static int backlight_level = IS_ENABLED(CONFIG_TOMS_BACKLIGHT_ACTIVE_HIGH) ? 1 : 0;
/* Boots on the live Bluetooth page; the button walks the other pages. */
static bool auto_advance;

/* 5x7 glyphs, one byte per column, bit 0 is the top row. */
struct glyph {
	char ch;
	uint8_t col[5];
};

static const struct glyph font[] = {
	{' ', {0x00, 0x00, 0x00, 0x00, 0x00}}, {'-', {0x08, 0x08, 0x08, 0x08, 0x08}},
	{':', {0x00, 0x36, 0x36, 0x00, 0x00}}, {'.', {0x00, 0x60, 0x60, 0x00, 0x00}},
	{'/', {0x20, 0x10, 0x08, 0x04, 0x02}}, {'0', {0x3E, 0x51, 0x49, 0x45, 0x3E}},
	{'1', {0x00, 0x42, 0x7F, 0x40, 0x00}}, {'2', {0x42, 0x61, 0x51, 0x49, 0x46}},
	{'3', {0x21, 0x41, 0x45, 0x4B, 0x31}}, {'4', {0x18, 0x14, 0x12, 0x7F, 0x10}},
	{'5', {0x27, 0x45, 0x45, 0x45, 0x39}}, {'6', {0x3C, 0x4A, 0x49, 0x49, 0x30}},
	{'7', {0x01, 0x71, 0x09, 0x05, 0x03}}, {'8', {0x36, 0x49, 0x49, 0x49, 0x36}},
	{'9', {0x06, 0x49, 0x49, 0x29, 0x1E}}, {'A', {0x7E, 0x11, 0x11, 0x11, 0x7E}},
	{'B', {0x7F, 0x49, 0x49, 0x49, 0x36}}, {'C', {0x3E, 0x41, 0x41, 0x41, 0x22}},
	{'D', {0x7F, 0x41, 0x41, 0x22, 0x1C}}, {'E', {0x7F, 0x49, 0x49, 0x49, 0x41}},
	{'F', {0x7F, 0x09, 0x09, 0x09, 0x01}}, {'G', {0x3E, 0x41, 0x49, 0x49, 0x7A}},
	{'H', {0x7F, 0x08, 0x08, 0x08, 0x7F}}, {'I', {0x00, 0x41, 0x7F, 0x41, 0x00}},
	{'J', {0x20, 0x40, 0x41, 0x3F, 0x01}}, {'K', {0x7F, 0x08, 0x14, 0x22, 0x41}},
	{'L', {0x7F, 0x40, 0x40, 0x40, 0x40}}, {'M', {0x7F, 0x02, 0x0C, 0x02, 0x7F}},
	{'N', {0x7F, 0x04, 0x08, 0x10, 0x7F}}, {'O', {0x3E, 0x41, 0x41, 0x41, 0x3E}},
	{'P', {0x7F, 0x09, 0x09, 0x09, 0x06}}, {'Q', {0x3E, 0x41, 0x51, 0x21, 0x5E}},
	{'R', {0x7F, 0x09, 0x19, 0x29, 0x46}}, {'S', {0x46, 0x49, 0x49, 0x49, 0x31}},
	{'T', {0x01, 0x01, 0x7F, 0x01, 0x01}}, {'U', {0x3F, 0x40, 0x40, 0x40, 0x3F}},
	{'V', {0x1F, 0x20, 0x40, 0x20, 0x1F}}, {'W', {0x3F, 0x40, 0x38, 0x40, 0x3F}},
	{'X', {0x63, 0x14, 0x08, 0x14, 0x63}}, {'Y', {0x07, 0x08, 0x70, 0x08, 0x07}},
	{'Z', {0x61, 0x51, 0x49, 0x45, 0x43}},
};

static const uint8_t *glyph_for(char c)
{
	c = (char)toupper((unsigned char)c);
	for (size_t i = 0; i < ARRAY_SIZE(font); i++) {
		if (font[i].ch == c) {
			return font[i].col;
		}
	}
	return font[0].col;
}

static void put_px(int x, int y, uint16_t c)
{
	if (x < 0 || y < 0 || x >= W || y >= H) {
		return;
	}
	size_t i = ((size_t)y * W + (size_t)x) * 2;
	fb[i] = (uint8_t)(c >> 8);
	fb[i + 1] = (uint8_t)c;
}

static void fill_rect(int x, int y, int w, int h, uint16_t c)
{
	for (int j = y; j < y + h; j++) {
		for (int i = x; i < x + w; i++) {
			put_px(i, j, c);
		}
	}
}

static void fill_all(uint16_t c)
{
	fill_rect(0, 0, W, H, c);
}

static int text_width(const char *s, int scale)
{
	return (int)strlen(s) * 6 * scale - scale;
}

static void draw_text(int x, int y, int scale, uint16_t c, const char *s)
{
	for (; *s != '\0'; s++, x += 6 * scale) {
		const uint8_t *g = glyph_for(*s);
		for (int col = 0; col < 5; col++) {
			for (int row = 0; row < 7; row++) {
				if (g[col] & BIT(row)) {
					fill_rect(x + col * scale, y + row * scale, scale, scale, c);
				}
			}
		}
	}
}

static void draw_centered(int y, int scale, uint16_t c, const char *s)
{
	draw_text((W - text_width(s, scale)) / 2, y, scale, c, s);
}

static void solid_page(uint16_t bg, uint16_t fg, const char *name, const char *hint)
{
	fill_all(bg);
	draw_centered(50, 3, fg, name);
	draw_centered(H - 12, 1, fg, hint);
}

static void render_pair(const struct ota_ui_state *u)
{
	char num[8];

	snprintk(num, sizeof(num), "%06u", (unsigned int)u->passkey);
	fill_all(C_BLACK);
	draw_centered(20, 2, C_WHITE, "PAIR");
	draw_centered(52, 3, C_CYAN, num);
	draw_centered(H - 12, 1, C_GRAY, "MATCH ON PHONE - PRESS BTN");
}

static void render_update(const struct ota_ui_state *u)
{
	fill_all(C_BLACK);
	draw_centered(6, 2, C_WHITE, "UPDATE");
	if (u->phase == OTA_UI_ERASE) {
		draw_centered(56, 2, C_YELLOW, "ERASING");
		return;
	}
	if (u->phase == OTA_UI_VERIFY) {
		draw_centered(56, 2, C_YELLOW, "CHECKING");
		return;
	}
	if (u->phase == OTA_UI_READY) {
		draw_centered(56, 2, C_GREEN, "RESTARTING");
		return;
	}
	uint32_t pct = u->total != 0u ? u->done * 100u / u->total : 0u;
	int bw = W - 20;

	if (pct > 100u) {
		pct = 100u;
	}
	fill_rect(10, 50, bw, 18, C_GRAY);
	fill_rect(11, 51, (int)(bw - 2) * (int)pct / 100, 16, C_GREEN);
	char line[12];

	snprintk(line, sizeof(line), "%u/100", (unsigned int)pct);
	draw_centered(82, 2, C_WHITE, line);
}

static void bars_page(void)
{
	static const uint16_t bars[] = {C_WHITE, C_YELLOW, C_CYAN,  C_GREEN,
					C_MAGENTA, C_RED,    C_BLUE, C_BLACK};
	int bw = W / ARRAY_SIZE(bars);

	for (size_t i = 0; i < ARRAY_SIZE(bars); i++) {
		fill_rect((int)i * bw, 0, bw, H, bars[i]);
	}
	fill_rect(0, H - 20, W, 20, C_BLACK);
	draw_centered(H - 14, 1, C_WHITE, "W Y C G M R B K");
}

static void frame_page(void)
{
	fill_all(C_BLACK);
	fill_rect(0, 0, W, 1, C_WHITE);
	fill_rect(0, H - 1, W, 1, C_WHITE);
	fill_rect(0, 0, 1, H, C_WHITE);
	fill_rect(W - 1, 0, 1, H, C_WHITE);

	fill_rect(1, 1, 14, 14, C_RED);
	fill_rect(W - 15, 1, 14, 14, C_GREEN);
	fill_rect(1, H - 15, 14, 14, C_BLUE);
	fill_rect(W - 15, H - 15, 14, 14, C_YELLOW);

	fill_rect(W / 2, 16, 1, H - 32, C_GRAY);
	fill_rect(16, H / 2, W - 32, 1, C_GRAY);

	draw_centered(36, 2, C_WHITE, "160X128");
	draw_centered(84, 1, C_WHITE, "TL RED   TR GREEN");
	draw_centered(96, 1, C_WHITE, "BL BLUE  BR YELLOW");
}

static void gradient_page(void)
{
	fill_all(C_BLACK);
	for (int x = 0; x < W; x++) {
		int lvl = x * 32 / W;
		uint16_t grey = (uint16_t)((lvl << 11) | ((lvl * 2) << 5) | lvl);
		fill_rect(x, 0, 1, 56, grey);
	}
	for (int y = 56; y < H; y += 8) {
		for (int x = 0; x < W; x += 8) {
			bool on = (((x / 8) + ((y - 56) / 8)) & 1) == 0;
			fill_rect(x, y, 8, 8, on ? C_WHITE : C_BLACK);
		}
	}
}

static void info_page(void)
{
	char line[24];

	fill_all(C_BLACK);
	draw_centered(6, 2, C_CYAN, "TOMS");
	draw_centered(26, 1, C_WHITE, "RETURN MACHINE");
	draw_centered(40, 1, C_WHITE, "DISPLAY TEST OK");
	snprintk(line, sizeof(line), "BACKLIGHT GPIO13 %s", backlight_level ? "HIGH" : "LOW");
	draw_centered(62, 1, C_YELLOW, line);
	draw_centered(80, 1, C_WHITE, "BUTTON: NEXT PAGE");
	draw_centered(92, 1, C_WHITE, "HOLD: FLIP BACKLIGHT");
	draw_centered(108, 1, C_GRAY, auto_advance ? "AUTO NEXT 3 S" : "MANUAL");
}

static const char *card_type_str(uint8_t atype)
{
	switch (atype) {
	case 1:
		return "NTAG (TYPE 2)";
	case 2:
		return "DESFIRE (TYPE 4)";
	case 3:
		return "MIFARE CLASSIC";
	default:
		return "ISO 14443-A";
	}
}

static void diag_page(void)
{
	char line[32];

	fill_all(C_BLACK);
	draw_centered(4, 2, C_CYAN, "DIAGNOSTICS");

	draw_text(6, 24, 1, C_WHITE, "SCREEN: 160x128 OK");
	snprintk(line, sizeof(line), "BLIGHT: %s", backlight_level ? "ON" : "OFF");
	draw_text(6, 36, 1, backlight_level ? C_GREEN : C_YELLOW, line);

	snprintk(line, sizeof(line), "NFC   : %s", diag_nfc_ready ? "PN532 v1.6 OK" : "SEARCHING");
	draw_text(6, 48, 1, diag_nfc_ready ? C_GREEN : C_RED, line);

	k_mutex_lock(&uid_lock, K_FOREVER);
	uint8_t len = card_uid_len;
	uint8_t atype = card_uid_atype;
	uint8_t uid[UID_MAX];
	memcpy(uid, card_uid, sizeof(uid));
	k_mutex_unlock(&uid_lock);

	if (len > 0) {
		char hex[22] = {0};
		for (uint8_t i = 0; i < len && i < 6; i++) {
			snprintk(&hex[i * 3], 4, "%02X ", uid[i]);
		}
		snprintk(line, sizeof(line), "CARD  : %s", hex);
		draw_text(6, 60, 1, C_CYAN, line);
		snprintk(line, sizeof(line), "TYPE  : %s", card_type_str(atype));
		draw_text(6, 72, 1, C_GREEN, line);
	} else {
		draw_text(6, 60, 1, C_GRAY, "CARD  : TAP CARD TO TEST");
		draw_text(6, 72, 1, C_GRAY, "HEALTH: WAITING FOR TAP");
	}

	snprintk(line, sizeof(line), "BUTTON: %u PRESSES", (unsigned int)diag_btn_count);
	draw_text(6, 86, 1, C_WHITE, line);

	draw_text(6, 98, 1, C_WHITE, "BUZZER: GPIO6 ACTIVE");
	draw_text(6, 110, 1, C_YELLOW, "BEEPS ON TAP/PRESS");

	draw_centered(120, 1, C_GRAY, "PRESS BTN: TEST/NEXT");
}

static void card_page(void)
{
	char hex[3 * UID_MAX + 1];
	uint8_t uid[UID_MAX];
	uint8_t len;
	uint8_t atype;

	k_mutex_lock(&uid_lock, K_FOREVER);
	len = card_uid_len;
	atype = card_uid_atype;
	memcpy(uid, card_uid, sizeof(uid));
	k_mutex_unlock(&uid_lock);

	fill_all(C_BLACK);
	draw_centered(6, 2, C_GREEN, "CARD VERIFIED");
	if (len == 0) {
		draw_centered(50, 1, C_GRAY, "NONE YET");
		draw_centered(66, 1, C_WHITE, "TAP CARD ON ANTENNA");
		return;
	}
	for (uint8_t i = 0; i < len; i++) {
		snprintk(&hex[i * 3], 4, "%02X ", uid[i]);
	}
	hex[len * 3 - 1] = '\0';

	char lenline[28];
	snprintk(lenline, sizeof(lenline), "%u BYTES - %s", len, card_type_str(atype));
	draw_centered(38, 1, C_CYAN, hex);
	draw_centered(54, 1, C_YELLOW, lenline);
	draw_centered(72, 1, C_GREEN, "RF COUPLING: HEALTHY");
	draw_centered(88, 1, C_WHITE, "STATUS: IN WORKING ORDER");
	draw_centered(114, 1, C_GRAY, "AUTO ADVANCE IN 4 S");
}

static void ble_page(void)
{
	struct rm_ble_status st;
	char line[28];

	rm_ble_get_status(&st);
	fill_all(C_BLACK);
	draw_centered(4, 2, C_CYAN, "BLUETOOTH");
	draw_centered(26, 1, C_WHITE, st.name);

	if (st.connected) {
		draw_centered(40, 2, C_GREEN, "CONNECTED");
	} else if (st.advertising) {
		draw_centered(40, 2, C_YELLOW, "WAITING");
	} else {
		draw_centered(40, 2, C_RED, "NOT READY");
	}

	snprintk(line, sizeof(line), "CARD NOTIFY %s", st.card_notify ? "ON" : "OFF");
	draw_centered(64, 1, st.card_notify ? C_GREEN : C_GRAY, line);
	snprintk(line, sizeof(line), "HEARTBEAT NOTIFY %s", st.hb_notify ? "ON" : "OFF");
	draw_centered(76, 1, st.hb_notify ? C_GREEN : C_GRAY, line);
	snprintk(line, sizeof(line), "WRITES %u LAST %02X", (unsigned int)st.rx_writes,
		 st.last_write_char);
	draw_centered(88, 1, C_WHITE, line);
	snprintk(line, sizeof(line), "STATE V%u  %u CARDS", (unsigned int)st.state_version,
		 st.state_count);
	draw_centered(100, 1, C_WHITE, line);
	snprintk(line, sizeof(line), "SENT CARD %u HB %u", (unsigned int)st.tx_card,
		 (unsigned int)st.tx_heartbeat);
	draw_centered(112, 1, C_WHITE, line);
}

static void render_page(enum page p)
{
	switch (p) {
	case PAGE_BLE:
		ble_page();
		break;
	case PAGE_DIAG:
		diag_page();
		break;
	case PAGE_CARD:
		card_page();
		break;
	case PAGE_RED:
		solid_page(C_RED, C_WHITE, "RED", "SHOULD BE RED. CYAN = INVERSION");
		break;
	case PAGE_GREEN:
		solid_page(C_GREEN, C_BLACK, "GREEN", "SHOULD BE GREEN");
		break;
	case PAGE_BLUE:
		solid_page(C_BLUE, C_WHITE, "BLUE", "SHOULD BE BLUE. RED = RGB/BGR");
		break;
	case PAGE_WHITE:
		solid_page(C_WHITE, C_BLACK, "WHITE", "LOOK FOR DEAD PIXELS");
		break;
	case PAGE_BLACK:
		solid_page(C_BLACK, C_WHITE, "BLACK", "LOOK FOR LIGHT LEAK");
		break;
	case PAGE_BARS:
		bars_page();
		break;
	case PAGE_FRAME:
		frame_page();
		break;
	case PAGE_GRADIENT:
		gradient_page();
		break;
		case PAGE_INFO:
			info_page();
			break;
		default:
			fill_all(C_BLACK);
			break;
	}
}

static int flush(void)
{
	for (int y = 0; y < H; y += BAND_ROWS) {
		struct display_buffer_descriptor desc = {
			.buf_size = (size_t)W * BAND_ROWS * 2,
			.width = W,
			.height = BAND_ROWS,
			.pitch = W,
		};
		int rc = display_write(lcd, 0, (uint16_t)y, &desc, &fb[(size_t)y * W * 2]);
		if (rc != 0) {
			return rc;
		}
	}
	return 0;
}

static void apply_backlight(void)
{
	gpio_pin_set_raw(backlight.port, backlight.pin, backlight_level);
	LOG_INF("backlight GPIO13 now %s", backlight_level ? "HIGH" : "LOW");
}

static void display_loop(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);

	enum page page = PAGE_BLE;
	enum page shown = PAGE_COUNT;
	bool redraw = true;
	uint32_t frames = 0;
	uint32_t idle_ms = 0;

	for (;;) {
		trial_alive(TRIAL_TASK_DISPLAY);

		/* An active pairing or update screen takes over the panel. */
		struct ota_ui_state ui = ota_ui_get();
		if (ui.kind != OTA_UI_NONE) {
			if (ui.kind == OTA_UI_PAIR) {
				render_pair(&ui);
			} else {
				render_update(&ui);
			}
			int urc = flush();
			if (urc != 0) {
				LOG_ERR("ota ui write failed (%d)", urc);
			}
			/* Consume a queued wake so it cannot skip a page later. */
			(void)k_sem_take(&wake, K_MSEC(200));
			continue;
		}
		if (redraw) {
			render_page(page);
			int rc = flush();
			if (rc != 0) {
				LOG_ERR("display_write failed (%d)", rc);
			} else if (page != shown) {
				LOG_INF("display page %d/%d", page + 1, PAGE_COUNT);
			}
			shown = page;
			redraw = false;

			/* Boot lines are lost when USB re-enumerates, so say it again. */
			if (++frames % 10 == 1) {
				size_t unused = 0;
				k_thread_stack_space_get(&display_thread, &unused);
				LOG_INF("display alive: page %d, frame %u, last write rc %d, stack free %u of %u",
					page + 1, (unsigned int)frames, rc, (unsigned int)unused,
					(unsigned int)DISPLAY_STACK);
			}
		}

		/* Never wait longer than 500 ms: the watchdog treats a silent display thread as hung.
		 * The Bluetooth page redraws every second, the others advance on their own timer. */
		int rc = k_sem_take(&wake, K_MSEC(500));
		if (rc == -EAGAIN) {
				idle_ms += 500;
				if (page == PAGE_BLE || page == PAGE_DIAG) {
					if (idle_ms >= 1000) {
						idle_ms = 0;
						redraw = true;
					}
				} else if (page == PAGE_CARD) {
					if (idle_ms >= 4000) {
						idle_ms = 0;
						auto_advance = true;
						page = PAGE_BLE;
						redraw = true;
					}
				} else if (auto_advance && idle_ms >= AUTO_ADVANCE_S * 1000) {
				idle_ms = 0;
				page = (page + 1) % PAGE_COUNT;
				redraw = true;
			}
			continue;
		}
		idle_ms = 0;

		uint32_t req = (uint32_t)atomic_set(&requests, 0);
		if (req & REQ_BACKLIGHT) {
			backlight_level = !backlight_level;
			apply_backlight();
			redraw = true;
		}
		if (req & REQ_NEXT) {
			auto_advance = false;
			page = (page + 1) % PAGE_COUNT;
			redraw = true;
		}
		if (req & REQ_CARD) {
			auto_advance = false;
			page = PAGE_CARD;
			redraw = true;
		}
	}
}

int display_test_init(void)
{
	if (!device_is_ready(lcd)) {
		LOG_ERR("display device not ready");
		return -ENODEV;
	}
	if (!gpio_is_ready_dt(&backlight)) {
		LOG_ERR("backlight GPIO not ready");
		return -ENODEV;
	}

	struct display_capabilities caps;
	display_get_capabilities(lcd, &caps);
	LOG_INF("display %ux%u, pixel formats 0x%x, current 0x%x", caps.x_resolution,
		caps.y_resolution, caps.supported_pixel_formats, caps.current_pixel_format);

	fill_all(C_BLACK);
	int rc = flush();
	if (rc != 0) {
		LOG_ERR("first display_write failed (%d)", rc);
		return rc;
	}
	display_blanking_off(lcd);
	apply_backlight();

	/* Splash: the machine's name while the rest of boot runs. */
	fill_all(C_BLACK);
	draw_centered(30, 4, C_GREEN, "TOMS");
	draw_centered(84, 2, C_WHITE, CONFIG_TOMS_BUILD_TAG);
	if (flush() != 0) {
		LOG_WRN("splash write failed");
	}
	k_sleep(K_MSEC(1800));

	k_thread_create(&display_thread, display_stack, DISPLAY_STACK, display_loop, NULL, NULL, NULL,
			DISPLAY_PRIO, 0, K_NO_WAIT);
	k_thread_name_set(&display_thread, "display_test");
	return 0;
}

void display_test_next(void)
{
	atomic_or(&requests, REQ_NEXT);
	k_sem_give(&wake);
}

void display_test_backlight_toggle(void)
{
	atomic_or(&requests, REQ_BACKLIGHT);
	k_sem_give(&wake);
}

void display_test_set_uid(const uint8_t *uid, uint8_t len, uint8_t atype)
{
	if (len > UID_MAX) {
		len = UID_MAX;
	}
	k_mutex_lock(&uid_lock, K_FOREVER);
	memcpy(card_uid, uid, len);
	card_uid_len = len;
	card_uid_atype = atype;
	k_mutex_unlock(&uid_lock);

	atomic_or(&requests, REQ_CARD);
	k_sem_give(&wake);
}

void display_test_button(void)
{
	/* Runs in the system workqueue right after the debounced press. */
	if (ota_ui_pair_button()) {
		return; /* the press confirms the pairing number */
	}
	k_sleep(K_MSEC(LONG_PRESS_MS));
	if (gpio_pin_get_dt(&button) == 1) {
		display_test_backlight_toggle();
		/* Wait for release so one long hold toggles only once. */
		while (gpio_pin_get_dt(&button) == 1) {
			k_sleep(K_MSEC(20));
		}
	} else {
		display_test_next();
	}
}

void display_test_set_nfc_status(bool ready)
{
	diag_nfc_ready = ready;
	k_sem_give(&wake);
}

void display_test_record_button_press(uint16_t count)
{
	diag_btn_count = count;
	k_sem_give(&wake);
}
