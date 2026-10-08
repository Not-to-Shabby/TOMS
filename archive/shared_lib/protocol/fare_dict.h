/**
 * @file fare_dict.h
 * @brief TOMS shared fare dictionary for compact NFC data exchange.
 *
 * Both Master and Slave store an identical fare dictionary in NVS.
 * During NFC tap, only a fare_id index is transmitted. The receiver
 * looks up the full fare/route details from its local dictionary.
 */

#ifndef TOMS_FARE_DICT_H
#define TOMS_FARE_DICT_H

#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Maximum number of entries in the fare dictionary */
#define TOMS_MAX_FARE_ENTRIES   32

/** NVS namespace for fare dictionary */
#define TOMS_FARE_NVS_NS       "toms_fare"

/** NVS key for fare dictionary blob */
#define TOMS_FARE_NVS_KEY      "dict"

/* ── Fare Entry ───────────────────────────────────────────────────────── */

typedef struct __attribute__((packed)) {
    uint8_t  fare_id;               /**< Unique index (1–255, 0 = unused) */
    uint16_t fare_centavos;         /**< Fare amount in centavos */
    uint8_t  route_id;              /**< Route identifier */
    char     route_name[24];        /**< Human-readable route name */
    uint8_t  vehicle_id[16];        /**< Vehicle UUID for QR receipts */
} toms_fare_entry_t;

/* ── Fare Dictionary ──────────────────────────────────────────────────── */

typedef struct {
    toms_fare_entry_t entries[TOMS_MAX_FARE_ENTRIES];
    uint8_t count;                  /**< Number of valid entries */
} toms_fare_dict_t;

/* ── API ──────────────────────────────────────────────────────────────── */

/**
 * @brief Load the fare dictionary from NVS.
 *
 * If NVS is empty, loads default entries.
 *
 * @param dict  [out] Dictionary to populate.
 * @return ESP_OK on success.
 */
esp_err_t toms_fare_dict_load(toms_fare_dict_t *dict);

/**
 * @brief Save the fare dictionary to NVS.
 *
 * @param dict  Dictionary to persist.
 * @return ESP_OK on success.
 */
esp_err_t toms_fare_dict_save(const toms_fare_dict_t *dict);

/**
 * @brief Look up a fare entry by fare_id.
 *
 * @param dict     Dictionary to search.
 * @param fare_id  Fare ID to find.
 * @return Pointer to entry, or NULL if not found.
 */
const toms_fare_entry_t *toms_fare_dict_lookup(const toms_fare_dict_t *dict,
                                                uint8_t fare_id);

/**
 * @brief Populate dictionary with default entries.
 *
 * Used when NVS is empty (first boot).
 *
 * @param dict  [out] Dictionary to populate.
 */
void toms_fare_dict_set_defaults(toms_fare_dict_t *dict);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_FARE_DICT_H */
