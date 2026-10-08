/**
 * @file storage.h
 * @brief TOMS local non-volatile storage (SPIFFS + NVS) for transaction buffering.
 *
 * Provides offline-first transaction logging to SPIFFS during signal dead zones,
 * preventing revenue leakage. Also wraps NVS for device configuration.
 */

#ifndef TOMS_STORAGE_H
#define TOMS_STORAGE_H

#include <stdint.h>
#include <stdbool.h>
#include "protocol.h"

#ifdef __cplusplus
extern "C" {
#endif

/** SPIFFS partition label (matches partitions.csv) */
#define TOMS_STORAGE_PARTITION  "storage"

/** Mount point in VFS */
#define TOMS_STORAGE_MOUNT      "/storage"

/** Max transaction log filename length */
#define TOMS_STORAGE_MAX_FNAME  300

/** Records per log file before rotation */
#define TOMS_STORAGE_MAX_RECORDS_PER_FILE  1000

/**
 * @brief Initialize storage subsystem.
 *
 * Mounts SPIFFS and initializes NVS.
 *
 * @return ESP_OK on success.
 */
int toms_storage_init(void);

/**
 * @brief Log a passenger event to SPIFFS.
 *
 * Appends a binary record to the current transaction log file.
 * Rotates to a new file after TOMS_STORAGE_MAX_RECORDS_PER_FILE records.
 *
 * @param event     Passenger event to log.
 * @return true on success.
 */
bool toms_storage_log_event(const toms_passenger_event_t *event);

/**
 * @brief Get count of unsynced log files.
 */
int toms_storage_get_pending_count(void);

/**
 * @brief Read the next unsynced log file.
 *
 * @param buf       Output buffer for binary records.
 * @param buf_size  Buffer capacity.
 * @param out_len   [out] Actual bytes read.
 * @param fname     [out] Filename (for marking done later).
 * @return true if a file was read successfully.
 */
bool toms_storage_read_pending(uint8_t *buf, size_t buf_size,
                               size_t *out_len, char *fname);

/**
 * @brief Mark a log file as synced (renames to .done).
 *
 * @param fname     Filename to mark.
 * @return true on success.
 */
bool toms_storage_mark_synced(const char *fname);

/**
 * @brief Clean up .done files when storage is low.
 */
void toms_storage_cleanup(void);

/**
 * @brief Get SPIFFS usage statistics.
 *
 * @param total     [out] Total bytes.
 * @param used      [out] Used bytes.
 */
void toms_storage_get_stats(size_t *total, size_t *used);

/* ── NVS Configuration Helpers ────────────────────────────────────────── */

/**
 * @brief Store a string config value in NVS.
 */
bool toms_config_set_str(const char *key, const char *value);

/**
 * @brief Read a string config value from NVS.
 *
 * @param key       NVS key.
 * @param buf       Output buffer.
 * @param buf_len   Buffer capacity.
 * @return true if key exists and was read.
 */
bool toms_config_get_str(const char *key, char *buf, size_t buf_len);

/**
 * @brief Store a uint32 config value in NVS.
 */
bool toms_config_set_u32(const char *key, uint32_t value);

/**
 * @brief Read a uint32 config value from NVS.
 */
bool toms_config_get_u32(const char *key, uint32_t *value);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_STORAGE_H */
