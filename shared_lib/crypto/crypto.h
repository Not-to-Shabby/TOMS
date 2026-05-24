/**
 * @file crypto.h
 * @brief TOMS AES-256-CBC encryption/decryption via mbedTLS.
 *
 * Provides application-level AES-256 encryption on top of ESP-NOW's
 * native AES-128 CCMP, ensuring the specified security requirement.
 * Each encrypted blob is prefixed with a random 16-byte IV.
 */

#ifndef TOMS_CRYPTO_H
#define TOMS_CRYPTO_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/** AES-256 key length in bytes */
#define TOMS_AES_KEY_LEN    32

/** AES block size */
#define TOMS_AES_BLOCK_LEN  16

/** Maximum plaintext that can be encrypted (must fit in ESP-NOW 250-byte frame) */
#define TOMS_CRYPTO_MAX_PLAIN   208

/**
 * @brief Initialize the crypto module.
 *
 * Loads the AES-256 key from NVS (namespace "toms", key "aes_key").
 * If no key exists, generates a random one and stores it.
 *
 * @return ESP_OK on success.
 */
int toms_crypto_init(void);

/**
 * @brief Encrypt a plaintext buffer using AES-256-CBC.
 *
 * Output format: [IV (16 bytes)][ciphertext (PKCS#7 padded)]
 *
 * @param plaintext     Input plaintext.
 * @param plain_len     Length of plaintext (max TOMS_CRYPTO_MAX_PLAIN).
 * @param out           Output buffer (must be >= plain_len + 32 bytes).
 * @param out_len       [out] Actual output length.
 * @return true on success.
 */
bool toms_crypto_encrypt(const uint8_t *plaintext, size_t plain_len,
                         uint8_t *out, size_t *out_len);

/**
 * @brief Decrypt a ciphertext buffer (IV-prefixed AES-256-CBC).
 *
 * @param ciphertext    Input: [IV (16 bytes)][ciphertext].
 * @param cipher_len    Total input length.
 * @param out           Output plaintext buffer.
 * @param out_len       [out] Actual plaintext length.
 * @return true on success, false on padding/key error.
 */
bool toms_crypto_decrypt(const uint8_t *ciphertext, size_t cipher_len,
                         uint8_t *out, size_t *out_len);

/**
 * @brief Get the current AES-256 key (for provisioning/debug).
 * @param key_out   Buffer of at least TOMS_AES_KEY_LEN bytes.
 */
void toms_crypto_get_key(uint8_t *key_out);

/**
 * @brief Set a new AES-256 key and persist to NVS.
 * @param key   32-byte key.
 * @return true on success.
 */
bool toms_crypto_set_key(const uint8_t *key);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_CRYPTO_H */
