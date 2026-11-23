#include "readstore.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/settings/settings.h>
#include <zephyr/sys/printk.h>
LOG_MODULE_REGISTER(readstore, LOG_LEVEL_INF);

#include <toms/readlog.h>

#define CAP CONFIG_TOMS_RING_CAPACITY

/* RAM copy of the log records. The settings API cannot read one key by name, so the records
 * are loaded once at boot and every change goes to flash first, then to this copy. */
static uint8_t mirror[CAP][TOMS_READLOG_REC];
static bool used[CAP];

static uint8_t key_buf[TOMS_SIG_KEY_LEN];
static bool key_present;
static uint32_t saved_hwm;
static bool hwm_present;
static uint32_t saved_drop;
static bool drop_present;

static struct toms_readlog log_state;
static K_MUTEX_DEFINE(lock);
static bool ready;

static int io_read(void *ctx, uint32_t slot, uint8_t rec[TOMS_READLOG_REC])
{
	ARG_UNUSED(ctx);
	if (slot >= CAP || !used[slot]) {
		return -ENOENT;
	}
	memcpy(rec, mirror[slot], TOMS_READLOG_REC);
	return 0;
}

static int io_write(void *ctx, uint32_t slot, const uint8_t rec[TOMS_READLOG_REC])
{
	ARG_UNUSED(ctx);
	char name[24];

	if (slot >= CAP) {
		return -EINVAL;
	}
	snprintk(name, sizeof(name), "toms/rl/%u", (unsigned int)slot);
	int rc = settings_save_one(name, rec, TOMS_READLOG_REC);

	if (rc != 0) {
		return rc;
	}
	memcpy(mirror[slot], rec, TOMS_READLOG_REC);
	used[slot] = true;
	return 0;
}

static int io_erase(void *ctx, uint32_t slot)
{
	ARG_UNUSED(ctx);
	char name[24];

	if (slot >= CAP) {
		return -EINVAL;
	}
	snprintk(name, sizeof(name), "toms/rl/%u", (unsigned int)slot);
	int rc = settings_delete(name);

	if (rc != 0) {
		return rc;
	}
	used[slot] = false;
	return 0;
}

static int io_load_hwm(void *ctx, uint32_t *hwm)
{
	ARG_UNUSED(ctx);
	if (!hwm_present) {
		return -ENOENT;
	}
	*hwm = saved_hwm;
	return 0;
}

static int io_save_hwm(void *ctx, uint32_t hwm)
{
	ARG_UNUSED(ctx);
	int rc = settings_save_one("toms/k/hwm", &hwm, sizeof(hwm));

	if (rc == 0) {
		saved_hwm = hwm;
		hwm_present = true;
	}
	return rc;
}

static int io_load_dropped(void *ctx, uint32_t *dropped)
{
	ARG_UNUSED(ctx);
	if (!drop_present) {
		return -ENOENT;
	}
	*dropped = saved_drop;
	return 0;
}

static int io_save_dropped(void *ctx, uint32_t dropped)
{
	ARG_UNUSED(ctx);
	int rc = settings_save_one("toms/k/drop", &dropped, sizeof(dropped));

	if (rc == 0) {
		saved_drop = dropped;
		drop_present = true;
	}
	return rc;
}

static int load_rl(const char *key, size_t len, settings_read_cb read_cb, void *cb_arg,
		   void *param)
{
	ARG_UNUSED(param);
	char *end;
	unsigned long slot = strtoul(key, &end, 10);

	if (*key == '\0' || *end != '\0' || slot >= CAP || len != TOMS_READLOG_REC) {
		return 0; /* a record from a build with a larger ring, or damaged: skip it */
	}
	if (read_cb(cb_arg, mirror[slot], TOMS_READLOG_REC) == (ssize_t)TOMS_READLOG_REC) {
		used[slot] = true;
	}
	return 0;
}

static int load_k(const char *key, size_t len, settings_read_cb read_cb, void *cb_arg,
		  void *param)
{
	ARG_UNUSED(param);
	if (strcmp(key, "key") == 0 && len == sizeof(key_buf)) {
		key_present = read_cb(cb_arg, key_buf, sizeof(key_buf)) == (ssize_t)sizeof(key_buf);
	} else if (strcmp(key, "drop") == 0 && len == sizeof(saved_drop)) {
		drop_present = read_cb(cb_arg, &saved_drop, sizeof(saved_drop)) ==
			       (ssize_t)sizeof(saved_drop);
	} else if (strcmp(key, "hwm") == 0 && len == sizeof(saved_hwm)) {
		hwm_present = read_cb(cb_arg, &saved_hwm, sizeof(saved_hwm)) ==
			      (ssize_t)sizeof(saved_hwm);
	}
	return 0;
}

int readstore_init(void)
{
	k_mutex_lock(&lock, K_FOREVER);
	if (ready) {
		k_mutex_unlock(&lock);
		return 0;
	}
	int rc = settings_subsys_init();

	if (rc == 0) {
		rc = settings_load_subtree_direct("toms/k", load_k, NULL);
	}
	if (rc == 0) {
		rc = settings_load_subtree_direct("toms/rl", load_rl, NULL);
	}
	if (rc == 0) {
		struct toms_readlog_io io = {NULL, io_read, io_write, io_erase, io_load_hwm,
					     io_save_hwm, io_load_dropped, io_save_dropped};

		rc = toms_readlog_open(&log_state, &io, CAP);
	}
	if (rc == 0) {
		ready = true;
		LOG_INF("read log: %u pending, next read_seq %u, signing key %s",
			(unsigned int)log_state.count, (unsigned int)log_state.next_seq,
			key_present ? "provisioned" : "NOT provisioned (values go out unsigned)");
	} else {
		LOG_ERR("read log init failed (%d)", rc);
	}
	k_mutex_unlock(&lock);
	return rc;
}

int32_t readstore_record(struct toms_card_read_msg *m)
{
	if (!ready) {
		return -ENODEV;
	}
	k_mutex_lock(&lock, K_FOREVER);
	int32_t s = toms_readlog_append(&log_state, m);

	k_mutex_unlock(&lock);
	return s;
}

int32_t readstore_take_seq(void)
{
	if (!ready) {
		return -ENODEV;
	}
	k_mutex_lock(&lock, K_FOREVER);
	int32_t s = toms_readlog_take_seq(&log_state);

	k_mutex_unlock(&lock);
	return s;
}

int readstore_keep(const struct toms_card_read_msg *m)
{
	if (!ready) {
		return -ENODEV;
	}
	k_mutex_lock(&lock, K_FOREVER);
	int rc = toms_readlog_store(&log_state, m);

	k_mutex_unlock(&lock);
	return rc;
}

void readstore_mark_sent(uint32_t seq)
{
	if (ready) {
		k_mutex_lock(&lock, K_FOREVER);
		toms_readlog_mark_sent(&log_state, seq);
		k_mutex_unlock(&lock);
	}
}

uint32_t readstore_unsent(void)
{
	if (!ready) {
		return 0;
	}
	k_mutex_lock(&lock, K_FOREVER);
	uint32_t n = toms_readlog_unsent(&log_state);

	k_mutex_unlock(&lock);
	return n;
}

int readstore_next_unsent(uint8_t *frame, size_t cap, uint32_t *seq, uint8_t *outcome)
{
	if (!ready) {
		return -ENODEV;
	}
	k_mutex_lock(&lock, K_FOREVER);
	int n = toms_readlog_get_from_ex(&log_state, log_state.sent_upto + 1u, frame, cap, seq,
					 outcome);

	k_mutex_unlock(&lock);
	return n;
}

void readstore_rewind(void)
{
	if (ready) {
		k_mutex_lock(&lock, K_FOREVER);
		toms_readlog_rewind(&log_state, 0);
		k_mutex_unlock(&lock);
	}
}

int readstore_next(uint32_t from_seq, uint8_t *frame, size_t cap, uint32_t *seq)
{
	if (!ready) {
		return -ENODEV;
	}
	k_mutex_lock(&lock, K_FOREVER);
	int n = toms_readlog_get_from(&log_state, from_seq, frame, cap, seq);

	k_mutex_unlock(&lock);
	return n;
}

int readstore_ack(uint32_t upto_seq)
{
	if (!ready) {
		return -ENODEV;
	}
	k_mutex_lock(&lock, K_FOREVER);
	int rc = toms_readlog_ack(&log_state, upto_seq);

	k_mutex_unlock(&lock);
	return rc;
}

uint32_t readstore_pending(void)
{
	return ready ? log_state.count : 0;
}

uint32_t readstore_dropped(void)
{
	return ready ? log_state.dropped : 0;
}

bool keystore_get(uint8_t key[TOMS_SIG_KEY_LEN])
{
	if (!key_present) {
		return false;
	}
	memcpy(key, key_buf, TOMS_SIG_KEY_LEN);
	return true;
}

int keystore_provision(const uint8_t key[TOMS_SIG_KEY_LEN])
{
	if (key_present) {
		return -EEXIST;
	}
	int rc = settings_save_one("toms/k/key", key, TOMS_SIG_KEY_LEN);

	if (rc == 0) {
		memcpy(key_buf, key, TOMS_SIG_KEY_LEN);
		key_present = true;
		LOG_INF("signing key provisioned");
	}
	return rc;
}
