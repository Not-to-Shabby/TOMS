#include <toms/ota.h>

#include <errno.h>
#include <string.h>

#define IMAGE_MAGIC 0x96f3b83du
#define IMAGE_HEADER_LEN 32u
#define IMAGE_HEADER_MAX 4096u /* header padding is 32 B..4 KB in practice */

static uint32_t rd32(const uint8_t *p)
{
	return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
	       ((uint32_t)p[3] << 24);
}

static uint16_t rd16(const uint8_t *p)
{
	return (uint16_t)(p[0] | (p[1] << 8));
}

static void wr32(uint8_t *p, uint32_t v)
{
	p[0] = (uint8_t)v;
	p[1] = (uint8_t)(v >> 8);
	p[2] = (uint8_t)(v >> 16);
	p[3] = (uint8_t)(v >> 24);
}

int toms_ota_decode_start(const uint8_t *data, size_t len, uint32_t *size, uint8_t sha[32])
{
	if (data == NULL || len != TOMS_OTA_START_LEN || data[0] != TOMS_OTA_OP_START) {
		return -EINVAL;
	}
	*size = rd32(data + 1);
	memcpy(sha, data + 5, 32);
	return 0;
}

int toms_ota_decode_chunk(const uint8_t *data, size_t len, uint32_t *offset,
			  const uint8_t **payload, size_t *payload_len)
{
	if (data == NULL || len <= TOMS_OTA_CHUNK_HDR ||
	    len - TOMS_OTA_CHUNK_HDR > TOMS_OTA_CHUNK_MAX) {
		return -EINVAL;
	}
	*offset = rd32(data);
	*payload = data + TOMS_OTA_CHUNK_HDR;
	*payload_len = len - TOMS_OTA_CHUNK_HDR;
	return 0;
}

int toms_ota_encode_reply(uint8_t *out, size_t cap, uint8_t op, uint8_t status, uint32_t offset,
			  uint8_t state, const struct toms_image_version *running, uint8_t flags)
{
	if (out == NULL || cap < TOMS_OTA_REPLY_LEN) {
		return -EINVAL;
	}
	out[0] = op;
	out[1] = status;
	wr32(out + 2, offset);
	out[6] = state;
	out[7] = running != NULL ? running->major : 0;
	out[8] = running != NULL ? running->minor : 0;
	out[9] = running != NULL ? (uint8_t)running->revision : 0;
	out[10] = running != NULL ? (uint8_t)(running->revision >> 8) : 0;
	out[11] = flags;
	return TOMS_OTA_REPLY_LEN;
}

void toms_ota_init(struct toms_ota_session *s, uint32_t slot_size)
{
	memset(s, 0, sizeof(*s));
	s->slot_size = slot_size;
	s->state = TOMS_OTA_IDLE;
}

int toms_ota_start(struct toms_ota_session *s, uint32_t size, const uint8_t sha[32],
		   bool *resumed)
{
	*resumed = false;
	if (s->state == TOMS_OTA_VERIFYING || s->state == TOMS_OTA_READY) {
		return TOMS_OTA_BUSY;
	}
	if (size <= IMAGE_HEADER_LEN || s->slot_size <= TOMS_OTA_TRAILER_RESERVE ||
	    size > s->slot_size - TOMS_OTA_TRAILER_RESERVE) {
		return TOMS_OTA_SIZE;
	}
	if (s->state == TOMS_OTA_RECEIVING && s->size == size && memcmp(s->sha, sha, 32) == 0) {
		*resumed = true; /* same upload announced again: keep what is stored */
		return TOMS_OTA_OK;
	}
	s->state = TOMS_OTA_RECEIVING;
	s->size = size;
	s->rx_next = 0;
	memcpy(s->sha, sha, 32);
	return TOMS_OTA_OK;
}

int toms_ota_chunk(struct toms_ota_session *s, uint32_t offset, size_t len, uint32_t *expected)
{
	*expected = s->rx_next;
	if (s->state != TOMS_OTA_RECEIVING) {
		return TOMS_OTA_STATE;
	}
	if (len == 0 || len > TOMS_OTA_CHUNK_MAX) {
		return TOMS_OTA_BAD_MSG;
	}
	if (offset != s->rx_next) {
		return TOMS_OTA_OFFSET;
	}
	/* s->size - s->rx_next cannot underflow: rx_next never passes size. */
	if (len > s->size - s->rx_next) {
		return TOMS_OTA_SIZE;
	}
	s->rx_next += (uint32_t)len;
	return TOMS_OTA_OK;
}

int toms_ota_finish(struct toms_ota_session *s, uint32_t *expected)
{
	*expected = s->rx_next;
	if (s->state != TOMS_OTA_RECEIVING) {
		return TOMS_OTA_STATE;
	}
	if (s->rx_next != s->size) {
		return TOMS_OTA_OFFSET;
	}
	s->state = TOMS_OTA_VERIFYING;
	return TOMS_OTA_OK;
}

void toms_ota_verified(struct toms_ota_session *s, bool ok)
{
	if (s->state == TOMS_OTA_VERIFYING) {
		s->state = ok ? TOMS_OTA_READY : TOMS_OTA_FAILED;
	}
}

void toms_ota_abort(struct toms_ota_session *s)
{
	s->state = TOMS_OTA_IDLE;
	s->size = 0;
	s->rx_next = 0;
}

int toms_ota_parse_header(const uint8_t *hdr, size_t len, uint32_t stored_size,
			  struct toms_image_version *out)
{
	if (hdr == NULL || len < IMAGE_HEADER_LEN || rd32(hdr) != IMAGE_MAGIC) {
		return TOMS_OTA_HEADER;
	}
	uint32_t hdr_size = rd16(hdr + 8);
	uint32_t img_size = rd32(hdr + 12);

	if (hdr_size < IMAGE_HEADER_LEN || hdr_size > IMAGE_HEADER_MAX) {
		return TOMS_OTA_HEADER;
	}
	/* header + image body must leave room for at least the TLV (signature) area */
	if (img_size == 0 || img_size > stored_size || hdr_size > stored_size - img_size ||
	    stored_size - img_size - hdr_size < 8u) {
		return TOMS_OTA_HEADER;
	}
	out->major = hdr[20];
	out->minor = hdr[21];
	out->revision = rd16(hdr + 22);
	out->build = rd32(hdr + 24);
	return TOMS_OTA_OK;
}

int toms_ota_version_cmp(const struct toms_image_version *a, const struct toms_image_version *b)
{
	if (a->major != b->major) {
		return a->major > b->major ? 1 : -1;
	}
	if (a->minor != b->minor) {
		return a->minor > b->minor ? 1 : -1;
	}
	if (a->revision != b->revision) {
		return a->revision > b->revision ? 1 : -1;
	}
	if (a->build != b->build) {
		return a->build > b->build ? 1 : -1;
	}
	return 0;
}
