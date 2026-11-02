#include "ota.h"

#include <errno.h>
#include <string.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/dfu/flash_img.h>
#include <zephyr/dfu/mcuboot.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/storage/flash_map.h>
#include <zephyr/sys/reboot.h>
LOG_MODULE_REGISTER(ota, LOG_LEVEL_INF);

#include "ota_ui.h"
#include <toms/ota.h>
#include <toms/sha256.h>

#define SLOT0_ID FIXED_PARTITION_ID(slot0_partition)
#define SLOT1_ID FIXED_PARTITION_ID(slot1_partition)
#define SECTOR 4096u
#define PROGRESS_EVERY_CHUNKS 32u
#define QUEUE_DEPTH 24u

enum item_type { ITEM_START, ITEM_CHUNK, ITEM_FINISH };

struct item {
	uint8_t type;
	uint8_t len;
	uint16_t epoch; /* session generation; a stale item is dropped by the worker */
	uint32_t offset; /* chunk: its offset. start: the announced image size */
	uint8_t data[TOMS_OTA_CHUNK_MAX];
};

K_MSGQ_DEFINE(ota_q, sizeof(struct item), QUEUE_DEPTH, 4);
K_THREAD_STACK_DEFINE(ota_stack, 4096);
static struct k_thread ota_thread_data;

static K_MUTEX_DEFINE(lock); /* guards session, epoch, nagged */
static struct toms_ota_session session;
static uint16_t epoch;
static bool nagged;      /* a resync/failed event was already sent since the last good chunk */
static uint32_t accepted_chunks;

/* worker thread only */
static struct flash_img_context img_ctx;
static bool ctx_open;
static uint32_t announced_size;
static uint32_t chunk_tick;
static struct toms_image_version running;

static void send(uint8_t op, uint8_t status, uint32_t offset)
{
	uint8_t buf[TOMS_OTA_REPLY_LEN];
	uint8_t flags = boot_is_img_confirmed() ? TOMS_OTA_FLAG_CONFIRMED : 0;
	int n = toms_ota_encode_reply(buf, sizeof(buf), op, status, offset, session.state, &running,
				      flags);

	if (n > 0) {
		int rc = rm_ble_notify_ota(buf, (size_t)n);

		if (rc != 0 && rc != -ENOTCONN) {
			LOG_WRN("ota notify failed (%d)", rc);
		}
	}
}

/* Give up the upload, tell the phone, and forget it. */
static void fail(uint8_t status, const char *why)
{
	LOG_ERR("OTA FAILED: %s (status %u)", why, status);
	k_mutex_lock(&lock, K_FOREVER);
	epoch++;
	toms_ota_abort(&session);
	session.state = TOMS_OTA_FAILED;
	k_mutex_unlock(&lock);
	if (ctx_open) {
		flash_area_close(img_ctx.flash_area);
		ctx_open = false;
	}
	ota_ui_clear_update();
	send(TOMS_OTA_EVENT_FAILED, status, 0);
}

static int erase_slot(uint32_t size)
{
	const struct flash_area *fa;
	int rc = flash_area_open(SLOT1_ID, &fa);

	if (rc != 0) {
		return rc;
	}
	uint32_t body = (size + SECTOR - 1u) / SECTOR * SECTOR;
	uint32_t trailer = fa->fa_size - TOMS_OTA_TRAILER_RESERVE;
	uint32_t sectors = (body + TOMS_OTA_TRAILER_RESERVE) / SECTOR;
	uint32_t done = 0;
	int64_t t0 = k_uptime_get();

	LOG_INF("OTA: erasing the update slot: %u KB of image plus the %u KB trailer (%u sectors)",
		(unsigned int)(body / 1024u), (unsigned int)(TOMS_OTA_TRAILER_RESERVE / 1024u),
		(unsigned int)sectors);
	/* One sector at a time with a pause between, so the display and watchdog tasks keep running
	 * while the flash is busy. */
	for (uint32_t off = 0; off < fa->fa_size && rc == 0; off += SECTOR) {
		if (off >= body && off < trailer) {
			off = trailer - SECTOR;
			continue;
		}
		rc = flash_area_erase(fa, off, SECTOR);
		if (++done % 64u == 0u) {
			LOG_INF("OTA: erased %u of %u sectors", (unsigned int)done,
				(unsigned int)sectors);
		}
		k_msleep(1);
	}
	flash_area_close(fa);
	LOG_INF("OTA: erase %s after %d ms", rc == 0 ? "done" : "FAILED", (int)(k_uptime_get() - t0));
	return rc;
}

static void do_start(const struct item *it)
{
	if (ctx_open) {
		flash_area_close(img_ctx.flash_area);
		ctx_open = false;
	}
	announced_size = it->offset;
	chunk_tick = 0;
	ota_ui_show_update(OTA_UI_ERASE, 0, 1);
	if (erase_slot(it->offset) != 0) {
		fail(TOMS_OTA_FLASH, "erasing the update slot failed");
		return;
	}
	if (flash_img_init_id(&img_ctx, SLOT1_ID) != 0) {
		fail(TOMS_OTA_FLASH, "opening the update slot failed");
		return;
	}
	ctx_open = true;
	ota_ui_show_update(OTA_UI_RECV, 0, announced_size);
	LOG_INF("OTA: ready for %u bytes", (unsigned int)it->offset);
	send(TOMS_OTA_OP_START | TOMS_OTA_REPLY_FLAG, TOMS_OTA_OK, 0);
}

static void do_chunk(const struct item *it)
{
	if (!ctx_open) {
		return; /* no start was processed for this epoch */
	}
	if (flash_img_buffered_write(&img_ctx, it->data, it->len, false) != 0) {
		fail(TOMS_OTA_FLASH, "flash write failed");
		return;
	}
	/* Every 16 chunks and the final one (so the bar reaches 100). */
	if (++chunk_tick % 16u == 0u || it->offset + it->len >= announced_size) {
		/* Count bytes ACCEPTED (the item's end offset), not bytes already
		 * in flash: the tail sits in the writer's buffer until FINISH, so
		 * flash-written would stop at 99%. */
		ota_ui_show_update(OTA_UI_RECV, it->offset + it->len, announced_size);
	}
}

/* Hash the stored image and read its header. Returns a toms_ota_status. */
static int verify(uint32_t size, const uint8_t want[32], struct toms_image_version *img)
{
	const struct flash_area *fa;
	struct toms_sha256 c;
	uint8_t piece[256], got[TOMS_SHA256_LEN], hdr[32];

	if (flash_area_open(SLOT1_ID, &fa) != 0) {
		return TOMS_OTA_FLASH;
	}
	toms_sha256_init(&c);
	for (uint32_t off = 0; off < size; off += sizeof(piece)) {
		uint32_t n = size - off < sizeof(piece) ? size - off : (uint32_t)sizeof(piece);

		if (flash_area_read(fa, off, piece, n) != 0) {
			flash_area_close(fa);
			return TOMS_OTA_FLASH;
		}
		toms_sha256_update(&c, piece, n);
	}
	toms_sha256_final(&c, got);
	int rc = flash_area_read(fa, 0, hdr, sizeof(hdr));

	flash_area_close(fa);
	if (rc != 0) {
		return TOMS_OTA_FLASH;
	}
	if (memcmp(got, want, sizeof(got)) != 0) {
		LOG_ERR("OTA: SHA-256 differs: stored %02x%02x%02x%02x... announced %02x%02x%02x%02x...",
			got[0], got[1], got[2], got[3], want[0], want[1], want[2], want[3]);
		return TOMS_OTA_HASH;
	}
	return toms_ota_parse_header(hdr, sizeof(hdr), size, img);
}

static void do_finish(void)
{
	uint32_t size;
	uint8_t sha[32];
	struct toms_image_version img = {0};

	k_mutex_lock(&lock, K_FOREVER);
	size = session.size;
	memcpy(sha, session.sha, sizeof(sha));
	k_mutex_unlock(&lock);

	if (!ctx_open) {
		fail(TOMS_OTA_STATE, "finish without an open update slot");
		return;
	}
	if (flash_img_buffered_write(&img_ctx, NULL, 0, true) != 0) {
		fail(TOMS_OTA_FLASH, "flushing the last block failed");
		return;
	}
	flash_area_close(img_ctx.flash_area);
	ctx_open = false;

	ota_ui_show_update(OTA_UI_VERIFY, 0, 0);
	LOG_INF("OTA: all %u bytes stored; checking SHA-256 and header", (unsigned int)size);
	int st = verify(size, sha, &img);

	if (st == TOMS_OTA_OK) {
		LOG_INF("OTA: image says version %u.%u.%u+%u, running %u.%u.%u+%u", img.major, img.minor,
			img.revision, (unsigned int)img.build, running.major, running.minor,
			running.revision, (unsigned int)running.build);
		if (toms_ota_version_cmp(&img, &running) <= 0) {
			if (IS_ENABLED(CONFIG_TOMS_DEV_MODE)) {
				LOG_WRN("OTA: image is not newer, allowed only because this is a dev build");
			} else {
				st = TOMS_OTA_VERSION;
			}
		}
	}
	if (st != TOMS_OTA_OK) {
		k_mutex_lock(&lock, K_FOREVER);
		toms_ota_verified(&session, false);
		k_mutex_unlock(&lock);
		LOG_ERR("OTA REFUSED at the check: status %d", st);
		send(TOMS_OTA_OP_FINISH | TOMS_OTA_REPLY_FLAG, (uint8_t)st, 0);
		k_msleep(2000);
		ota_ui_clear_update();
		return;
	}

	k_mutex_lock(&lock, K_FOREVER);
	toms_ota_verified(&session, true);
	k_mutex_unlock(&lock);
	int rc = boot_request_upgrade(BOOT_UPGRADE_TEST);

	if (rc != 0) {
		fail(TOMS_OTA_FLASH, "boot_request_upgrade failed");
		send(TOMS_OTA_OP_FINISH | TOMS_OTA_REPLY_FLAG, TOMS_OTA_FLASH, 0);
		return;
	}
	ota_ui_show_update(OTA_UI_READY, 0, 0);
	LOG_INF("OTA: upgrade requested as a TRIAL; MCUboot swaps on the next reset and reverts if the "
		"new image does not confirm itself. Rebooting in 3 s.");
	send(TOMS_OTA_OP_FINISH | TOMS_OTA_REPLY_FLAG, TOMS_OTA_OK, size);
	k_msleep(3000);
	sys_reboot(SYS_REBOOT_COLD);
}

static void ota_thread(void *a, void *b, void *c)
{
	ARG_UNUSED(a);
	ARG_UNUSED(b);
	ARG_UNUSED(c);
	struct item it;

	for (;;) {
		k_msgq_get(&ota_q, &it, K_FOREVER);
		k_mutex_lock(&lock, K_FOREVER);
		bool stale = it.epoch != epoch;

		k_mutex_unlock(&lock);
		if (stale) {
			continue;
		}
		switch (it.type) {
		case ITEM_START:
			do_start(&it);
			break;
		case ITEM_CHUNK:
			do_chunk(&it);
			break;
		case ITEM_FINISH:
			do_finish();
			break;
		}
	}
}

void rm_ota_init(void)
{
	const struct flash_area *fa;
	uint8_t hdr[32];

	if (flash_area_open(SLOT0_ID, &fa) == 0) {
		uint32_t slot = (uint32_t)fa->fa_size;

		toms_ota_init(&session, slot);
		if (flash_area_read(fa, 0, hdr, sizeof(hdr)) != 0 ||
		    toms_ota_parse_header(hdr, sizeof(hdr), slot, &running) != TOMS_OTA_OK) {
			LOG_WRN("OTA: could not read the running image's version");
		}
		flash_area_close(fa);
	}
	/* The update slot must be the same size as the primary one. */
	if (flash_area_open(SLOT1_ID, &fa) == 0) {
		if (fa->fa_size != session.slot_size) {
			LOG_ERR("OTA: slot sizes differ (%u vs %u); updates are disabled",
				(unsigned int)fa->fa_size, (unsigned int)session.slot_size);
			session.slot_size = 0;
		}
		flash_area_close(fa);
	}
	k_thread_create(&ota_thread_data, ota_stack, K_THREAD_STACK_SIZEOF(ota_stack), ota_thread, NULL,
			NULL, NULL, K_PRIO_PREEMPT(10), 0, K_NO_WAIT);
	k_thread_name_set(&ota_thread_data, "ota");
	LOG_INF("OTA ready: running %u.%u.%u+%u, %s, update slot %u KB, chunk %u B", running.major,
		running.minor, running.revision, (unsigned int)running.build,
		boot_is_img_confirmed() ? "confirmed" : "ON TRIAL (updates refused)",
		(unsigned int)(session.slot_size / 1024u), TOMS_OTA_CHUNK_MAX);
}

/* ---- Bluetooth RX thread side: parse, decide, queue ---- */

static bool link_ok(struct bt_conn *conn)
{
	return conn != NULL && bt_conn_get_security(conn) >= BT_SECURITY_L2;
}

static int enqueue(const struct item *it)
{
	return k_msgq_put(&ota_q, it, K_NO_WAIT);
}

int rm_ota_control_write(struct bt_conn *conn, const uint8_t *data, uint16_t len)
{
	struct item it = {0};
	uint32_t size, expected;
	uint8_t sha[32];
	bool resumed;

	if (len == 0) {
		return -EINVAL;
	}
	uint8_t op = data[0];

	if (!link_ok(conn)) {
		LOG_WRN("OTA control refused: link is not encrypted/bonded");
		send(op | TOMS_OTA_REPLY_FLAG, TOMS_OTA_SECURITY, 0);
		return 0;
	}

	switch (op) {
	case TOMS_OTA_OP_START: {
		if (toms_ota_decode_start(data, len, &size, sha) < 0) {
			send(op | TOMS_OTA_REPLY_FLAG, TOMS_OTA_BAD_MSG, 0);
			return 0;
		}
		if (!boot_is_img_confirmed()) {
			LOG_WRN("OTA refused: the running image is still on trial");
			send(op | TOMS_OTA_REPLY_FLAG, TOMS_OTA_BUSY, 0);
			return 0;
		}
		k_mutex_lock(&lock, K_FOREVER);
		int st = toms_ota_start(&session, size, sha, &resumed);
		uint32_t next = session.rx_next;

		if (st == TOMS_OTA_OK && !resumed) {
			epoch++;
			nagged = false;
			accepted_chunks = 0;
		}
		it.epoch = epoch;
		k_mutex_unlock(&lock);

		if (st != TOMS_OTA_OK) {
			LOG_WRN("OTA start refused: status %d (size %u)", st, (unsigned int)size);
			send(op | TOMS_OTA_REPLY_FLAG, (uint8_t)st, 0);
			return 0;
		}
		if (resumed) {
			LOG_INF("OTA: same upload announced again, resuming at %u of %u",
				(unsigned int)next, (unsigned int)size);
			send(op | TOMS_OTA_REPLY_FLAG, TOMS_OTA_OK, next);
			return 0;
		}
		LOG_INF("OTA: new upload of %u bytes announced", (unsigned int)size);
		it.type = ITEM_START;
		it.offset = size;
		if (enqueue(&it) != 0) {
			k_mutex_lock(&lock, K_FOREVER);
			toms_ota_abort(&session);
			k_mutex_unlock(&lock);
			send(op | TOMS_OTA_REPLY_FLAG, TOMS_OTA_BUSY, 0);
		}
		return 0; /* the worker replies once the slot is erased */
	}
	case TOMS_OTA_OP_FINISH: {
		k_mutex_lock(&lock, K_FOREVER);
		int st = toms_ota_finish(&session, &expected);

		it.epoch = epoch;
		k_mutex_unlock(&lock);
		if (st != TOMS_OTA_OK) {
			LOG_WRN("OTA finish refused: status %d, machine has %u bytes", st,
				(unsigned int)expected);
			send(op | TOMS_OTA_REPLY_FLAG, (uint8_t)st, expected);
			return 0;
		}
		it.type = ITEM_FINISH;
		if (enqueue(&it) != 0) {
			send(op | TOMS_OTA_REPLY_FLAG, TOMS_OTA_BUSY, expected);
		}
		return 0;
	}
	case TOMS_OTA_OP_ABORT:
		k_mutex_lock(&lock, K_FOREVER);
		epoch++;
		toms_ota_abort(&session);
		k_mutex_unlock(&lock);
		LOG_INF("OTA: aborted by the phone");
		send(op | TOMS_OTA_REPLY_FLAG, TOMS_OTA_OK, 0);
		return 0;
	case TOMS_OTA_OP_STATUS:
		send(op | TOMS_OTA_REPLY_FLAG, TOMS_OTA_OK, session.rx_next);
		return 0;
	default:
		send(op | TOMS_OTA_REPLY_FLAG, TOMS_OTA_BAD_MSG, 0);
		return 0;
	}
}

int rm_ota_data_write(struct bt_conn *conn, const uint8_t *data, uint16_t len)
{
	struct item it = {0};
	const uint8_t *payload;
	size_t plen;
	uint32_t offset, expected;

	if (!link_ok(conn)) {
		return -EACCES;
	}
	if (toms_ota_decode_chunk(data, len, &offset, &payload, &plen) < 0) {
		return -EINVAL;
	}

	k_mutex_lock(&lock, K_FOREVER);
	int st;

	if (k_msgq_num_free_get(&ota_q) == 0) {
		st = TOMS_OTA_OFFSET; /* queue full: treat like a lost chunk */
		expected = session.rx_next;
	} else {
		st = toms_ota_chunk(&session, offset, plen, &expected);
	}
	bool tell = false;
	bool progress = false;

	if (st == TOMS_OTA_OK) {
		it.type = ITEM_CHUNK;
		it.epoch = epoch;
		it.offset = offset;
		it.len = (uint8_t)plen;
		memcpy(it.data, payload, plen);
		(void)enqueue(&it); /* space was checked above; this is the only producer */
		nagged = false;
		progress = (++accepted_chunks % PROGRESS_EVERY_CHUNKS) == 0;
	} else if (!nagged) {
		nagged = true;
		tell = true;
	}
	uint32_t next = session.rx_next;

	k_mutex_unlock(&lock);

	if (progress) {
		send(TOMS_OTA_EVENT_PROGRESS, TOMS_OTA_OK, next);
	}
	if (tell) {
		LOG_WRN("OTA chunk at %u refused (status %d); machine wants %u", (unsigned int)offset, st,
			(unsigned int)expected);
		send(st == TOMS_OTA_OFFSET ? TOMS_OTA_EVENT_RESYNC : TOMS_OTA_EVENT_FAILED, (uint8_t)st,
		     expected);
	}
	return 0; /* write-without-response: errors travel by notification */
}
