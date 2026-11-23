#include <toms/keyring.h>

#include <errno.h>
#include <string.h>

#include <toms/authz.h>
#include <toms/crc16.h>

#define BLOB_VERSION_BIT 0x80u /* bit 7 set = this layout; the older single-key record has no blob */
#define F_CUR 0x01u
#define F_PREV 0x02u

void toms_keyring_init(struct toms_keyring *k)
{
	memset(k, 0, sizeof(*k));
}

int toms_keyring_provision(struct toms_keyring *k, const uint8_t key[TOMS_SIG_KEY_LEN])
{
	if (k == NULL || key == NULL) {
		return -EINVAL;
	}
	if (k->has_cur) {
		return -EEXIST;
	}
	memcpy(k->cur, key, TOMS_SIG_KEY_LEN);
	k->has_cur = true;
	return 0;
}

bool toms_keyring_get(const struct toms_keyring *k, uint8_t key[TOMS_SIG_KEY_LEN])
{
	if (k == NULL || !k->has_cur) {
		return false;
	}
	memcpy(key, k->cur, TOMS_SIG_KEY_LEN);
	return true;
}

int toms_keyring_check(struct toms_keyring *k, uint8_t suffix, const uint8_t *data, size_t len,
		       size_t consumed, enum toms_key_used *used, bool *committed)
{
	*used = TOMS_KEY_NONE;
	*committed = false;
	if (k == NULL || !k->has_cur) {
		return toms_sig_check(NULL, 0, suffix, data, len, consumed);
	}

	int rc = toms_sig_check(k->cur, TOMS_SIG_KEY_LEN, suffix, data, len, consumed);

	if (rc == TOMS_SIG_OK) {
		*used = TOMS_KEY_CURRENT;
		if (k->has_prev) {
			memset(k->prev, 0, sizeof(k->prev));
			k->has_prev = false;
			*committed = true;
		}
		return rc;
	}
	if (rc == -EBADMSG && k->has_prev) {
		int rg = toms_sig_check(k->prev, TOMS_SIG_KEY_LEN, suffix, data, len, consumed);

		if (rg == TOMS_SIG_OK) {
			*used = TOMS_KEY_GRACE;
			return rg;
		}
	}
	return rc;
}

uint8_t toms_keyring_replace(struct toms_keyring *k, const uint8_t *data, size_t len, bool *grace)
{
	*grace = (k != NULL) && k->has_prev;
	if (k == NULL || data == NULL || len != TOMS_KEY_REPLACE_LEN ||
	    data[0] != TOMS_DIAG_KEY_REPLACE) {
		return TOMS_REPLACE_MALFORMED;
	}
	if (!k->has_cur) {
		return TOMS_REPLACE_NO_KEY;
	}

	/* The signed part is the command byte and the new key; the trailer follows. A replace is not a
	 * "use" of the key in force for the purpose of ending a grace: it works on a copy. */
	struct toms_keyring probe = *k;
	enum toms_key_used used;
	bool committed;
	int rc = toms_keyring_check(&probe, TOMS_DIAG_SUFFIX, data, len, 1u + TOMS_SIG_KEY_LEN, &used,
				    &committed);

	memset(&probe, 0, sizeof(probe));
	if (rc != TOMS_SIG_OK) {
		return TOMS_REPLACE_BAD_SIG; /* unsigned, wrong, or a partial trailer */
	}

	const uint8_t *new_key = &data[1];

	if (used == TOMS_KEY_CURRENT) {
		/* normal replace: what was in force becomes the grace key */
		memcpy(k->prev, k->cur, TOMS_SIG_KEY_LEN);
		k->has_prev = true;
	}
	/* signed with the grace key: a retry. The grace key stays what it was, the new key is set again
	 * (possibly a different one), so the phone can recover from a replace it did not complete. */
	memcpy(k->cur, new_key, TOMS_SIG_KEY_LEN);
	*grace = k->has_prev;
	return TOMS_REPLACE_OK;
}

int toms_keyring_encode(const struct toms_keyring *k, uint8_t out[TOMS_KEYRING_BLOB_LEN])
{
	if (k == NULL || out == NULL) {
		return -EINVAL;
	}
	memset(out, 0, TOMS_KEYRING_BLOB_LEN);
	out[0] = (uint8_t)(BLOB_VERSION_BIT | (k->has_cur ? F_CUR : 0u) | (k->has_prev ? F_PREV : 0u));
	if (k->has_cur) {
		memcpy(&out[1], k->cur, TOMS_SIG_KEY_LEN);
	}
	if (k->has_prev) {
		memcpy(&out[1 + TOMS_SIG_KEY_LEN], k->prev, TOMS_SIG_KEY_LEN);
	}
	uint16_t crc = toms_crc16(out, TOMS_KEYRING_BLOB_LEN - 2u);

	out[TOMS_KEYRING_BLOB_LEN - 2u] = (uint8_t)crc;
	out[TOMS_KEYRING_BLOB_LEN - 1u] = (uint8_t)(crc >> 8);
	return (int)TOMS_KEYRING_BLOB_LEN;
}

int toms_keyring_decode(struct toms_keyring *k, const uint8_t *blob, size_t len)
{
	if (k == NULL) {
		return -EINVAL;
	}
	toms_keyring_init(k);
	if (blob == NULL || len != TOMS_KEYRING_BLOB_LEN || (blob[0] & BLOB_VERSION_BIT) == 0u ||
	    (blob[0] & (uint8_t)~(BLOB_VERSION_BIT | F_CUR | F_PREV)) != 0u) {
		return -EINVAL;
	}
	uint16_t crc = toms_crc16(blob, TOMS_KEYRING_BLOB_LEN - 2u);
	uint16_t stored = (uint16_t)(blob[TOMS_KEYRING_BLOB_LEN - 2u] |
				     (blob[TOMS_KEYRING_BLOB_LEN - 1u] << 8));

	if (crc != stored) {
		return -EINVAL;
	}
	k->has_cur = (blob[0] & F_CUR) != 0u;
	k->has_prev = (blob[0] & F_PREV) != 0u;
	if (k->has_prev && !k->has_cur) {
		toms_keyring_init(k); /* a grace key with nothing in force cannot be right */
		return -EINVAL;
	}
	if (k->has_cur) {
		memcpy(k->cur, &blob[1], TOMS_SIG_KEY_LEN);
	}
	if (k->has_prev) {
		memcpy(k->prev, &blob[1 + TOMS_SIG_KEY_LEN], TOMS_SIG_KEY_LEN);
	}
	return 0;
}
