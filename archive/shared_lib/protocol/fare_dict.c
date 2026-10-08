/**
 * @file fare_dict.c
 * @brief TOMS fare dictionary — NVS persistence and lookup.
 */

#include "fare_dict.h"

#include <string.h>
#include "esp_log.h"
#include "nvs_flash.h"
#include "nvs.h"

static const char *TAG = "toms_fare_dict";

/* ── Defaults ─────────────────────────────────────────────────────────── */

void toms_fare_dict_set_defaults(toms_fare_dict_t *dict)
{
    if (!dict) return;
    memset(dict, 0, sizeof(*dict));

    /* Entry 1 */
    dict->entries[0].fare_id       = 1;
    dict->entries[0].fare_centavos = 1300;
    dict->entries[0].route_id      = 1;
    strncpy(dict->entries[0].route_name, "City-Hills", sizeof(dict->entries[0].route_name) - 1);
    memcpy(dict->entries[0].vehicle_id, "VEH001", 6);

    /* Entry 2 */
    dict->entries[1].fare_id       = 2;
    dict->entries[1].fare_centavos = 1500;
    dict->entries[1].route_id      = 2;
    strncpy(dict->entries[1].route_name, "Terminal-Plaza", sizeof(dict->entries[1].route_name) - 1);
    memcpy(dict->entries[1].vehicle_id, "VEH001", 6);

    /* Entry 3 */
    dict->entries[2].fare_id       = 3;
    dict->entries[2].fare_centavos = 900;
    dict->entries[2].route_id      = 3;
    strncpy(dict->entries[2].route_name, "Express Route", sizeof(dict->entries[2].route_name) - 1);
    memcpy(dict->entries[2].vehicle_id, "VEH001", 6);

    dict->count = 3;

    ESP_LOGI(TAG, "Loaded %d default fare entries", dict->count);
}

/* ── NVS Load ─────────────────────────────────────────────────────────── */

esp_err_t toms_fare_dict_load(toms_fare_dict_t *dict)
{
    if (!dict) return ESP_ERR_INVALID_ARG;

    nvs_handle_t nvs;
    esp_err_t err = nvs_open(TOMS_FARE_NVS_NS, NVS_READONLY, &nvs);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "NVS open failed (%s) — loading defaults", esp_err_to_name(err));
        toms_fare_dict_set_defaults(dict);
        return ESP_OK;
    }

    size_t blob_len = sizeof(toms_fare_dict_t);
    err = nvs_get_blob(nvs, TOMS_FARE_NVS_KEY, dict, &blob_len);
    nvs_close(nvs);

    if (err != ESP_OK || blob_len != sizeof(toms_fare_dict_t)) {
        ESP_LOGW(TAG, "NVS read failed or size mismatch — loading defaults");
        toms_fare_dict_set_defaults(dict);
        return ESP_OK;
    }

    ESP_LOGI(TAG, "Loaded %d fare entries from NVS", dict->count);
    return ESP_OK;
}

/* ── NVS Save ─────────────────────────────────────────────────────────── */

esp_err_t toms_fare_dict_save(const toms_fare_dict_t *dict)
{
    if (!dict) return ESP_ERR_INVALID_ARG;

    nvs_handle_t nvs;
    esp_err_t err = nvs_open(TOMS_FARE_NVS_NS, NVS_READWRITE, &nvs);
    if (err != ESP_OK) return err;

    err = nvs_set_blob(nvs, TOMS_FARE_NVS_KEY, dict, sizeof(toms_fare_dict_t));
    if (err == ESP_OK) {
        err = nvs_commit(nvs);
    }
    nvs_close(nvs);

    if (err == ESP_OK) {
        ESP_LOGI(TAG, "Saved %d fare entries to NVS", dict->count);
    }
    return err;
}

/* ── Lookup ───────────────────────────────────────────────────────────── */

const toms_fare_entry_t *toms_fare_dict_lookup(const toms_fare_dict_t *dict,
                                                uint8_t fare_id)
{
    if (!dict || fare_id == 0) return NULL;

    for (int i = 0; i < dict->count && i < TOMS_MAX_FARE_ENTRIES; i++) {
        if (dict->entries[i].fare_id == fare_id) {
            return &dict->entries[i];
        }
    }
    return NULL;
}
