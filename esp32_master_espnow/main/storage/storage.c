/**
 * @file storage.c
 * @brief TOMS SPIFFS transaction logging and NVS configuration.
 */

#include "storage.h"

#include <string.h>
#include <stdio.h>
#include <dirent.h>
#include <sys/stat.h>
#include "esp_log.h"
#include "esp_spiffs.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

static const char *TAG = "toms_storage";

/* ── Internal State ───────────────────────────────────────────────────── */

static bool               s_initialized = false;
static int                s_file_index  = 0;
static int                s_record_count = 0;
static FILE              *s_current_file = NULL;
static char               s_current_fname[TOMS_STORAGE_MAX_FNAME];
static SemaphoreHandle_t  s_storage_mutex = NULL;

/* Helper macros — always call these as a matched pair */
#define STORAGE_LOCK()   xSemaphoreTake(s_storage_mutex, portMAX_DELAY)
#define STORAGE_UNLOCK() xSemaphoreGive(s_storage_mutex)

/* ── SPIFFS Initialization ────────────────────────────────────────────── */

static esp_err_t spiffs_init(void)
{
    esp_vfs_spiffs_conf_t conf = {
        .base_path       = TOMS_STORAGE_MOUNT,
        .partition_label = TOMS_STORAGE_PARTITION,
        .max_files       = 5,
        .format_if_mount_failed = true,
    };

    esp_err_t err = esp_vfs_spiffs_register(&conf);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "SPIFFS mount failed: %s", esp_err_to_name(err));
        return err;
    }

    size_t total = 0, used = 0;
    err = esp_spiffs_info(TOMS_STORAGE_PARTITION, &total, &used);
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "SPIFFS: %u/%u bytes used", (unsigned)used, (unsigned)total);
    }

    return ESP_OK;
}

/* ── File Management ──────────────────────────────────────────────────── */

static void open_new_log_file(void)
{
    if (s_current_file) {
        fclose(s_current_file);
        s_current_file = NULL;
    }

    snprintf(s_current_fname, sizeof(s_current_fname),
             TOMS_STORAGE_MOUNT "/txlog_%04d.bin", s_file_index++);

    s_current_file = fopen(s_current_fname, "ab");
    if (!s_current_file) {
        ESP_LOGE(TAG, "Failed to open %s", s_current_fname);
    } else {
        ESP_LOGI(TAG, "Opened log file: %s", s_current_fname);
    }
    s_record_count = 0;
}

/* ── Find highest existing file index ─────────────────────────────────── */

static void scan_existing_files(void)
{
    DIR *dir = opendir(TOMS_STORAGE_MOUNT);
    if (!dir) return;

    struct dirent *entry;
    int max_idx = -1;

    while ((entry = readdir(dir)) != NULL) {
        int idx;
        if (sscanf(entry->d_name, "txlog_%d.bin", &idx) == 1) {
            if (idx > max_idx) max_idx = idx;
        }
    }
    closedir(dir);

    s_file_index = max_idx + 1;
    ESP_LOGI(TAG, "Next log file index: %d", s_file_index);
}

/* ── Public API ───────────────────────────────────────────────────────── */

int toms_storage_init(void)
{
    if (s_initialized) return ESP_OK;

    /* Create mutex before any SPIFFS access */
    s_storage_mutex = xSemaphoreCreateMutex();
    if (!s_storage_mutex) {
        ESP_LOGE(TAG, "Failed to create storage mutex");
        return ESP_ERR_NO_MEM;
    }

    /* NVS */
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGW(TAG, "NVS erasing and re-initializing");
        nvs_flash_erase();
        err = nvs_flash_init();
    }
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "NVS init failed: %s", esp_err_to_name(err));
        return (int)err;
    }

    /* SPIFFS */
    err = spiffs_init();
    if (err != ESP_OK) return (int)err;

    /* Scan existing log files */
    scan_existing_files();

    /* Open first log file */
    open_new_log_file();

    s_initialized = true;
    return ESP_OK;
}

bool toms_storage_log_event(const toms_passenger_event_t *event)
{
    if (!s_initialized || !event) return false;

    STORAGE_LOCK();

    if (!s_current_file) {
        open_new_log_file();
        if (!s_current_file) {
            STORAGE_UNLOCK();
            return false;
        }
    }

    size_t written = fwrite(event, sizeof(toms_passenger_event_t), 1, s_current_file);
    if (written != 1) {
        ESP_LOGE(TAG, "Failed to write event");
        STORAGE_UNLOCK();
        return false;
    }

    fflush(s_current_file);
    s_record_count++;

    /* Rotate if needed */
    if (s_record_count >= TOMS_STORAGE_MAX_RECORDS_PER_FILE) {
        open_new_log_file();
    }

    STORAGE_UNLOCK();
    return true;
}

int toms_storage_get_pending_count(void)
{
    if (!s_initialized) return 0;

    STORAGE_LOCK();
    DIR *dir = opendir(TOMS_STORAGE_MOUNT);
    if (!dir) { STORAGE_UNLOCK(); return 0; }

    int count = 0;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        /* Count .bin files (not .done) */
        if (strstr(entry->d_name, ".bin") && !strstr(entry->d_name, ".done")) {
            count++;
        }
    }
    closedir(dir);
    STORAGE_UNLOCK();
    return count;
}

bool toms_storage_read_pending(uint8_t *buf, size_t buf_size,
                               size_t *out_len, char *fname)
{
    if (!s_initialized) return false;

    STORAGE_LOCK();
    DIR *dir = opendir(TOMS_STORAGE_MOUNT);
    if (!dir) { STORAGE_UNLOCK(); return false; }

    struct dirent *entry;
    bool found = false;

    while ((entry = readdir(dir)) != NULL) {
        if (strstr(entry->d_name, ".bin") && !strstr(entry->d_name, ".done")) {
            char full_path[TOMS_STORAGE_MAX_FNAME];
            snprintf(full_path, sizeof(full_path), TOMS_STORAGE_MOUNT "/%s", entry->d_name);

            /* Don't read the currently open file */
            if (strcmp(full_path, s_current_fname) == 0) continue;

            FILE *f = fopen(full_path, "rb");
            if (f) {
                *out_len = fread(buf, 1, buf_size, f);
                fclose(f);
                strcpy(fname, full_path);
                found = true;
                break;
            }
        }
    }
    closedir(dir);
    STORAGE_UNLOCK();
    return found;
}

bool toms_storage_mark_synced(const char *fname)
{
    if (!fname) return false;

    char done_path[TOMS_STORAGE_MAX_FNAME + 8];
    snprintf(done_path, sizeof(done_path), "%s.done", fname);

    return (rename(fname, done_path) == 0);
}

void toms_storage_cleanup(void)
{
    if (!s_initialized) return;

    size_t total = 0, used = 0;
    esp_spiffs_info(TOMS_STORAGE_PARTITION, &total, &used);

    /* Only cleanup if >80% full */
    if (total == 0 || (used * 100 / total) < 80) return;

    ESP_LOGI(TAG, "Storage >80%% full, cleaning .done files");

    STORAGE_LOCK();
    DIR *dir = opendir(TOMS_STORAGE_MOUNT);
    if (!dir) { STORAGE_UNLOCK(); return; }

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strstr(entry->d_name, ".done")) {
            char full_path[TOMS_STORAGE_MAX_FNAME];
            snprintf(full_path, sizeof(full_path), TOMS_STORAGE_MOUNT "/%s", entry->d_name);
            remove(full_path);
            ESP_LOGI(TAG, "Deleted: %s", full_path);
        }
    }
    closedir(dir);
    STORAGE_UNLOCK();
}

void toms_storage_get_stats(size_t *total, size_t *used)
{
    if (total && used) {
        esp_spiffs_info(TOMS_STORAGE_PARTITION, total, used);
    }
}

/* ── NVS Config Helpers ───────────────────────────────────────────────── */

bool toms_config_set_str(const char *key, const char *value)
{
    nvs_handle_t nvs;
    if (nvs_open("toms_cfg", NVS_READWRITE, &nvs) != ESP_OK) return false;

    esp_err_t err = nvs_set_str(nvs, key, value);
    if (err == ESP_OK) nvs_commit(nvs);
    nvs_close(nvs);
    return (err == ESP_OK);
}

bool toms_config_get_str(const char *key, char *buf, size_t buf_len)
{
    nvs_handle_t nvs;
    if (nvs_open("toms_cfg", NVS_READONLY, &nvs) != ESP_OK) return false;

    esp_err_t err = nvs_get_str(nvs, key, buf, &buf_len);
    nvs_close(nvs);
    return (err == ESP_OK);
}

bool toms_config_set_u32(const char *key, uint32_t value)
{
    nvs_handle_t nvs;
    if (nvs_open("toms_cfg", NVS_READWRITE, &nvs) != ESP_OK) return false;

    esp_err_t err = nvs_set_u32(nvs, key, value);
    if (err == ESP_OK) nvs_commit(nvs);
    nvs_close(nvs);
    return (err == ESP_OK);
}

bool toms_config_get_u32(const char *key, uint32_t *value)
{
    nvs_handle_t nvs;
    if (nvs_open("toms_cfg", NVS_READONLY, &nvs) != ESP_OK) return false;

    esp_err_t err = nvs_get_u32(nvs, key, value);
    nvs_close(nvs);
    return (err == ESP_OK);
}
