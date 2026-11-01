#include "ota_ui.h"

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(ota_ui, LOG_LEVEL_INF);

static K_MUTEX_DEFINE(lock);
static struct ota_ui_state state;
static K_SEM_DEFINE(pair_btn, 0, 1);

void ota_ui_show_pair(uint32_t passkey)
{
	k_mutex_lock(&lock, K_FOREVER);
	state.kind = OTA_UI_PAIR;
	state.passkey = passkey;
	k_mutex_unlock(&lock);
}

void ota_ui_pair_wait_confirm(uint32_t wait_ms)
{
	k_sem_reset(&pair_btn);
	/* Any press between now and the wait is kept: the reset happens before
	 * the user could see the number on the LCD. */
	(void)k_sem_take(&pair_btn, K_MSEC(wait_ms));
}

bool ota_ui_pair_button(void)
{
	k_mutex_lock(&lock, K_FOREVER);
	bool mine = state.kind == OTA_UI_PAIR;
	k_mutex_unlock(&lock);
	if (mine) {
		k_sem_give(&pair_btn);
	}
	return mine;
}

void ota_ui_clear_pair(void)
{
	k_mutex_lock(&lock, K_FOREVER);
	if (state.kind == OTA_UI_PAIR) {
		state.kind = OTA_UI_NONE;
	}
	k_mutex_unlock(&lock);
}

void ota_ui_show_update(uint8_t phase, uint32_t done, uint32_t total)
{
	k_mutex_lock(&lock, K_FOREVER);
	state.kind = OTA_UI_UPDATE;
	state.phase = phase;
	state.done = done;
	state.total = total;
	k_mutex_unlock(&lock);
}

void ota_ui_clear_update(void)
{
	k_mutex_lock(&lock, K_FOREVER);
	if (state.kind == OTA_UI_UPDATE) {
		state.kind = OTA_UI_NONE;
	}
	k_mutex_unlock(&lock);
}

struct ota_ui_state ota_ui_get(void)
{
	k_mutex_lock(&lock, K_FOREVER);
	struct ota_ui_state out = state;
	k_mutex_unlock(&lock);
	return out;
}
