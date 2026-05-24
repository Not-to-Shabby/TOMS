/**
 * @file crypto.c
 * @brief AES-256-CBC encryption/decryption using mbedTLS.
 */

#include "crypto.h"

#include <string.h>
#include "esp_log.h"
#include "esp_random.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "mbedtls/aes.h"

static const char *TAG = "toms_crypto";

/** Stored AES-256 key (loaded from NVS at init) */
static uint8_t s_aes_key[TOMS_AES_KEY_LEN];
static bool    s_initialized = false;

/* ── NVS Key Management ───────────────────────────────────────────────── */

static esp_err_t load_or_generate_key(void)
{
    nvs_handle_t nvs;
    esp_err_t err = nvs_open("toms", NVS_READWRITE, &nvs);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "NVS open failed: %s", esp_err_to_name(err));
        return err;
    }

    size_t key_len = TOMS_AES_KEY_LEN;
    err = nvs_get_blob(nvs, "aes_key", s_aes_key, &key_len);

    if (err == ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGW(TAG, "No AES key in NVS — using default dev key");
        static const uint8_t dev_key[TOMS_AES_KEY_LEN] = {
            0x54, 0x4F, 0x4D, 0x53, 0x5F, 0x44, 0x45, 0x56, 
            0x5F, 0x53, 0x45, 0x43, 0x52, 0x45, 0x54, 0x5F,
            0x4B, 0x45, 0x59, 0x5F, 0x32, 0x30, 0x32, 0x36,
            0x5F, 0x56, 0x31, 0x2E, 0x30, 0x5F, 0x41, 0x42
        };
        memcpy(s_aes_key, dev_key, TOMS_AES_KEY_LEN);
        err = nvs_set_blob(nvs, "aes_key", s_aes_key, TOMS_AES_KEY_LEN);
        if (err == ESP_OK) {
            err = nvs_commit(nvs);
        }
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "Failed to store key: %s", esp_err_to_name(err));
        }
    } else if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to read key: %s", esp_err_to_name(err));
    } else {
        ESP_LOGI(TAG, "AES-256 key loaded from NVS");
    }

    nvs_close(nvs);
    return err;
}

/* ── Public API ───────────────────────────────────────────────────────── */

int toms_crypto_init(void)
{
    esp_err_t err = load_or_generate_key();
    if (err == ESP_OK) {
        s_initialized = true;
    }
    return (int)err;
}

void toms_crypto_get_key(uint8_t *key_out)
{
    if (key_out) {
        memcpy(key_out, s_aes_key, TOMS_AES_KEY_LEN);
    }
}

bool toms_crypto_set_key(const uint8_t *key)
{
    if (!key) return false;

    memcpy(s_aes_key, key, TOMS_AES_KEY_LEN);

    nvs_handle_t nvs;
    esp_err_t err = nvs_open("toms", NVS_READWRITE, &nvs);
    if (err != ESP_OK) return false;

    err = nvs_set_blob(nvs, "aes_key", s_aes_key, TOMS_AES_KEY_LEN);
    if (err == ESP_OK) {
        err = nvs_commit(nvs);
    }
    nvs_close(nvs);
    return (err == ESP_OK);
}

/* ── PKCS#7 Padding ───────────────────────────────────────────────────── */

static size_t pkcs7_padded_len(size_t plain_len)
{
    return ((plain_len / TOMS_AES_BLOCK_LEN) + 1) * TOMS_AES_BLOCK_LEN;
}

static void pkcs7_pad(const uint8_t *in, size_t in_len, uint8_t *out, size_t *out_len)
{
    size_t padded = pkcs7_padded_len(in_len);
    uint8_t pad_val = (uint8_t)(padded - in_len);

    memcpy(out, in, in_len);
    memset(out + in_len, pad_val, pad_val);
    *out_len = padded;
}

static bool pkcs7_unpad(const uint8_t *in, size_t in_len, size_t *out_len)
{
    if (in_len == 0 || (in_len % TOMS_AES_BLOCK_LEN) != 0) {
        return false;
    }
    uint8_t pad_val = in[in_len - 1];
    if (pad_val == 0 || pad_val > TOMS_AES_BLOCK_LEN) {
        return false;
    }
    /* Verify all padding bytes */
    for (size_t i = in_len - pad_val; i < in_len; i++) {
        if (in[i] != pad_val) {
            return false;
        }
    }
    *out_len = in_len - pad_val;
    return true;
}

/* ── Encrypt ──────────────────────────────────────────────────────────── */

bool toms_crypto_encrypt(const uint8_t *plaintext, size_t plain_len,
                         uint8_t *out, size_t *out_len)
{
    if (!s_initialized || !plaintext || !out || !out_len) {
        return false;
    }
    if (plain_len > TOMS_CRYPTO_MAX_PLAIN) {
        return false;
    }

    /* Generate random IV */
    uint8_t iv[TOMS_AES_BLOCK_LEN];
    esp_fill_random(iv, sizeof(iv));

    /* Copy IV to output (first 16 bytes) */
    memcpy(out, iv, TOMS_AES_BLOCK_LEN);

    /* Pad plaintext */
    uint8_t padded[TOMS_CRYPTO_MAX_PLAIN + TOMS_AES_BLOCK_LEN];
    size_t padded_len;
    pkcs7_pad(plaintext, plain_len, padded, &padded_len);

    /* AES-256-CBC encrypt */
    mbedtls_aes_context ctx;
    mbedtls_aes_init(&ctx);
    int ret = mbedtls_aes_setkey_enc(&ctx, s_aes_key, 256);
    if (ret != 0) {
        mbedtls_aes_free(&ctx);
        return false;
    }

    /* mbedtls modifies iv in-place during CBC, so use the copy */
    uint8_t iv_copy[TOMS_AES_BLOCK_LEN];
    memcpy(iv_copy, iv, TOMS_AES_BLOCK_LEN);

    ret = mbedtls_aes_crypt_cbc(&ctx, MBEDTLS_AES_ENCRYPT, padded_len,
                                 iv_copy, padded, out + TOMS_AES_BLOCK_LEN);
    mbedtls_aes_free(&ctx);

    if (ret != 0) {
        return false;
    }

    *out_len = TOMS_AES_BLOCK_LEN + padded_len;
    return true;
}

/* ── Decrypt ──────────────────────────────────────────────────────────── */

bool toms_crypto_decrypt(const uint8_t *ciphertext, size_t cipher_len,
                         uint8_t *out, size_t *out_len)
{
    if (!s_initialized || !ciphertext || !out || !out_len) {
        return false;
    }
    if (cipher_len <= TOMS_AES_BLOCK_LEN) {
        return false;  /* Must have at least IV + 1 block */
    }

    size_t ct_body_len = cipher_len - TOMS_AES_BLOCK_LEN;
    if ((ct_body_len % TOMS_AES_BLOCK_LEN) != 0) {
        return false;
    }

    /* Extract IV */
    uint8_t iv[TOMS_AES_BLOCK_LEN];
    memcpy(iv, ciphertext, TOMS_AES_BLOCK_LEN);

    /* AES-256-CBC decrypt */
    mbedtls_aes_context ctx;
    mbedtls_aes_init(&ctx);
    int ret = mbedtls_aes_setkey_dec(&ctx, s_aes_key, 256);
    if (ret != 0) {
        mbedtls_aes_free(&ctx);
        return false;
    }

    uint8_t decrypted[TOMS_CRYPTO_MAX_PLAIN + TOMS_AES_BLOCK_LEN];
    ret = mbedtls_aes_crypt_cbc(&ctx, MBEDTLS_AES_DECRYPT, ct_body_len,
                                 iv, ciphertext + TOMS_AES_BLOCK_LEN, decrypted);
    mbedtls_aes_free(&ctx);

    if (ret != 0) {
        return false;
    }

    /* Remove PKCS#7 padding */
    size_t plain_len;
    if (!pkcs7_unpad(decrypted, ct_body_len, &plain_len)) {
        return false;
    }

    memcpy(out, decrypted, plain_len);
    *out_len = plain_len;
    return true;
}
