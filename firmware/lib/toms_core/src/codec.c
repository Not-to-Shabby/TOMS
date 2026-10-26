#include <toms/codec.h>

#include <errno.h>
#include <string.h>

#define PUT_U32(p, v)                                                                             \
	do {                                                                                      \
		(p)[0] = (uint8_t)(v);                                                            \
		(p)[1] = (uint8_t)((v) >> 8);                                                     \
		(p)[2] = (uint8_t)((v) >> 16);                                                    \
		(p)[3] = (uint8_t)((v) >> 24);                                                    \
	} while (0)

#define GET_U32(p)                                                                                \
	((uint32_t)(p)[0] | ((uint32_t)(p)[1] << 8) | ((uint32_t)(p)[2] << 16) |                   \
	 ((uint32_t)(p)[3] << 24))

#define PUT_U16(p, v)                                                                             \
	do {                                                                                      \
		(p)[0] = (uint8_t)(v);                                                            \
		(p)[1] = (uint8_t)((v) >> 8);                                                     \
	} while (0)

#define GET_U16(p) ((uint16_t)((uint16_t)(p)[0] | ((uint16_t)(p)[1] << 8)))

int toms_codec_encode_card_read(uint8_t *buf, size_t cap, const struct toms_card_read_msg *m)
{
	if (buf == NULL || m == NULL || m->uid_len == 0 || m->uid_len > TOMS_UID_MAX) {
		return -EINVAL;
	}
	size_t need = 15u + m->uid_len;
	if (cap < need) {
		return -ENOSPC;
	}

	PUT_U32(&buf[0], m->read_seq);
	PUT_U32(&buf[4], m->timestamp);
	PUT_U32(&buf[8], m->boot_ms);
	buf[12] = m->uid_len;
	memcpy(&buf[13], m->uid, m->uid_len);
	buf[13 + m->uid_len] = m->atype;
	buf[14 + m->uid_len] = m->state_cached;
	return (int)need;
}

int toms_codec_decode_card_read(const uint8_t *data, size_t len, struct toms_card_read_msg *m)
{
	if (data == NULL || m == NULL || len < 15u) {
		return -EINVAL;
	}
	uint8_t uid_len = data[12];
	if (uid_len == 0 || uid_len > TOMS_UID_MAX || len < 15u + uid_len) {
		return -EINVAL;
	}

	m->read_seq = GET_U32(&data[0]);
	m->timestamp = GET_U32(&data[4]);
	m->boot_ms = GET_U32(&data[8]);
	m->uid_len = uid_len;
	memcpy(m->uid, &data[13], uid_len);
	m->atype = data[13 + uid_len];
	m->state_cached = data[14 + uid_len];
	return (int)(15u + uid_len);
}

int toms_codec_encode_heartbeat(uint8_t *buf, size_t cap, const struct toms_heartbeat_msg *m)
{
	if (buf == NULL || m == NULL) {
		return -EINVAL;
	}
	if (cap < 15u) {
		return -ENOSPC;
	}

	PUT_U32(&buf[0], m->uptime_s);
	buf[4] = m->battery_pct;
	PUT_U16(&buf[5], m->battery_mv);
	buf[7] = m->nfc_status;
	PUT_U32(&buf[8], m->state_version);
	PUT_U16(&buf[12], m->buffered_reads);
	buf[14] = m->reset_reason;
	return 15;
}

int toms_codec_encode_paid_check(uint8_t *buf, size_t cap, uint32_t check_seq, const uint8_t *uid,
				 uint8_t uid_len)
{
	if (buf == NULL || uid == NULL || uid_len == 0 || uid_len > TOMS_UID_MAX) {
		return -EINVAL;
	}
	size_t need = 5u + uid_len;
	if (cap < need) {
		return -ENOSPC;
	}

	PUT_U32(&buf[0], check_seq);
	buf[4] = uid_len;
	memcpy(&buf[5], uid, uid_len);
	return (int)need;
}

int toms_codec_decode_paid_reply(const uint8_t *data, size_t len, uint32_t *check_seq,
				 uint8_t *verdict)
{
	if (data == NULL || len < 5u) {
		return -EINVAL;
	}
	*check_seq = GET_U32(&data[0]);
	*verdict = data[4];
	return 5;
}

int toms_codec_decode_ack(const uint8_t *data, size_t len, uint8_t *kind, uint32_t *value)
{
	if (data == NULL || len < 5u) {
		return -EINVAL;
	}
	*kind = data[0];
	*value = GET_U32(&data[1]);
	return 5;
}

int toms_codec_decode_state_update(const uint8_t *data, size_t len, uint32_t *version,
				   bool *snapshot, struct toms_state_entry *entries, uint16_t max,
				   uint16_t *count)
{
	if (data == NULL || len < 6u || version == NULL || snapshot == NULL || count == NULL) {
		return -EINVAL;
	}

	*version = GET_U32(&data[0]);
	*snapshot = (data[4] & 0x01u) != 0u;
	uint16_t n = data[5];

	size_t need = 6u;
	for (uint16_t i = 0; i < n; i++) {
		if (len < need + 1u) {
			return -EINVAL;
		}
		uint8_t uid_len = data[need];
		if (uid_len == 0 || uid_len > TOMS_UID_MAX) {
			return -EINVAL;
		}
		need += 1u + uid_len + 1u; /* len + uid + status */
		if (len < need) {
			return -EINVAL;
		}
	}
	if (n > max) {
		return -EMSGSIZE;
	}

	size_t off = 6u;
	for (uint16_t i = 0; i < n; i++) {
		uint8_t uid_len = data[off++];
		memcpy(entries[i].uid, &data[off], uid_len);
		off += uid_len;
		entries[i].uid_len = uid_len;
		entries[i].status = data[off++];
	}
	*count = n;
	return (int)off;
}
