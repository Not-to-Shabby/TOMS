#include "readstore.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/settings/settings.h>
#include <zephyr/sys/printk.h>
LOG_MODULE_REGISTER(readstore, LOG_LEVEL_INF);

#include <toms/keyring.h>
#include <toms/readlog.h>

#define CAP CONFIG_TOMS_RING_CAPACITY

/* RAM copy of the log records. The settings API cannot read one key by name, so the records
 * are loaded once at boot and every change goes to flash first, then to this copy. */
static uint8_t mirror[CAP][TOMS_READLOG_REC];
static bool used[CAP];

static struct toms_keyring keyring;
static K_MUTEX_DEFINE(key_lock);
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
	if (strcmp(key, "keys") == 0 && len == TOMS_KEYRING_BLOB_LEN) {
		/* the key ring: key in force, and the previous one during grace (67 bytes, crc) */
		uint8_t blob[TOMS_KEYRING_BLOB_LEN];

		if (read_cb(cb_arg, blob, sizeof(blob)) == (ssize_t)sizeof(blob) &&
		    toms_keyring_decode(&keyring, blob, sizeof(blob)) != 0) {
			LOG_ERR("key ring in flash is damaged: ignored, the machine has no key");
		}
		memset(blob, 0, sizeof(blob));
	} else if (strcmp(key, "key") == 0 && len == TOMS_SIG_KEY_LEN) {
		/* the single-key record written before the key could be replaced: read it as the key in
		 * force (the new record is written the first time the key changes) */
		uint8_t old[TOMS_SIG_KEY_LEN];

		if (!keyring.has_cur &&
		    read_cb(cb_arg, old, sizeof(old)) == (ssize_t)sizeof(old)) {
			(void)toms_keyring_provision(&keyring, old);
		}
		memset(old, 0, sizeof(old));
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
			keyring.has_cur ? "provisioned" : "NOT provisioned (values go out unsigned)");
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

/* Saves the ring. The caller holds key_lock. */
static int keyring_save(void)
{
	uint8_t blob[TOMS_KEYRING_BLOB_LEN];

	int n = toms_keyring_encode(&keyring, blob);
	int rc = (n == (int)sizeof(blob)) ? settings_save_one("toms/k/keys", blob, sizeof(blob)) : -EIO;

	memset(blob, 0, sizeof(blob));
	return rc;
}

bool keystore_get(uint8_t key[TOMS_SIG_KEY_LEN])
{
	k_mutex_lock(&key_lock, K_FOREVER);
	bool ok = toms_keyring_get(&keyring, key);

	k_mutex_unlock(&key_lock);
	return ok;
}

bool keystore_present(void)
{
	k_mutex_lock(&key_lock, K_FOREVER);
	bool ok = keyring.has_cur;

	k_mutex_unlock(&key_lock);
	return ok;
}

int keystore_provision(const uint8_t key[TOMS_SIG_KEY_LEN])
{
	k_mutex_lock(&key_lock, K_FOREVER);
	struct toms_keyring next = keyring;
	int rc = toms_keyring_provision(&next, key);

	if (rc == 0) {
		struct toms_keyring before = keyring;

		keyring = next;
		rc = keyring_save();
		if (rc != 0) {
			keyring = before; /* not saved: not in force */
		} else {
			LOG_INF("signing key provisioned");
		}
		memset(&before, 0, sizeof(before));
	}
	memset(&next, 0, sizeof(next));
	k_mutex_unlock(&key_lock);
	return rc;
}

int keystore_check(uint8_t suffix, const uint8_t *data, size_t len, size_t consumed)
{
	k_mutex_lock(&key_lock, K_FOREVER);
	enum toms_key_used used;
	bool committed;
	struct toms_keyring before = keyring;
	int rc = toms_keyring_check(&keyring, suffix, data, len, consumed, &used, &committed);

	if (committed) {
		/* the new key was used: the previous one is no longer accepted. If this cannot be saved,
		 * keep the grace key in memory too, so a reboot and this run behave the same. */
		if (keyring_save() != 0) {
			keyring = before;
			LOG_WRN("could not save the end of the key grace; the previous key stays valid");
		} else {
			LOG_INF("signing key replace confirmed: the previous key is no longer accepted");
		}
	}
	memset(&before, 0, sizeof(before));
	k_mutex_unlock(&key_lock);
	return rc;
}

uint8_t keystore_replace(const uint8_t *data, size_t len, bool *grace)
{
	k_mutex_lock(&key_lock, K_FOREVER);
	struct toms_keyring before = keyring;
	uint8_t st = toms_keyring_replace(&keyring, data, len, grace);

	if (st == TOMS_REPLACE_OK && keyring_save() != 0) {
		keyring = before; /* nothing changed: the phone's key is still the one in force */
		st = TOMS_REPLACE_STORAGE;
		*grace = keyring.has_prev;
	}
	if (st == TOMS_REPLACE_OK) {
		LOG_INF("signing key replaced (previous key kept for one step)");
	}
	memset(&before, 0, sizeof(before));
	k_mutex_unlock(&key_lock);
	return st;
}
