/**
 * @file protocol.c
 * @brief TOMS shared packet serialization, deserialization, and CRC-16-CCITT.
 */

#include "protocol.h"
#include <string.h>

/* ── CRC-16-CCITT (polynomial 0x1021, init 0xFFFF) ───────────────────── */

uint16_t toms_crc16(const uint8_t *data, size_t len)
{
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++) {
        crc ^= (uint16_t)data[i] << 8;
        for (uint8_t bit = 0; bit < 8; bit++) {
            if (crc & 0x8000) {
                crc = (crc << 1) ^ 0x1021;
            } else {
                crc <<= 1;
            }
        }
    }
    return crc;
}

/* ── Build ────────────────────────────────────────────────────────────── */

bool toms_packet_build(toms_packet_t *pkt, toms_msg_type_t msg_type,
                       uint8_t seq, const uint8_t *payload, uint8_t payload_len)
{
    if (!pkt || payload_len > TOMS_MAX_PAYLOAD_LEN) {
        return false;
    }
    pkt->sync     = TOMS_SYNC_WORD;
    pkt->length   = payload_len;
    pkt->msg_type = (uint8_t)msg_type;
    pkt->sequence = seq;

    if (payload && payload_len > 0) {
        memcpy(pkt->payload, payload, payload_len);
    }
    return true;
}

/* ── Serialize ────────────────────────────────────────────────────────── */

bool toms_packet_serialize(const toms_packet_t *pkt, uint8_t *out_buf, size_t *out_len)
{
    if (!pkt || !out_buf || !out_len) {
        return false;
    }
    if (pkt->length > TOMS_MAX_PAYLOAD_LEN) {
        return false;
    }

    size_t idx = 0;

    /* Sync word (little-endian) */
    out_buf[idx++] = TOMS_SYNC_BYTE_HI;
    out_buf[idx++] = TOMS_SYNC_BYTE_LO;

    /* Header */
    out_buf[idx++] = pkt->length;
    out_buf[idx++] = pkt->msg_type;
    out_buf[idx++] = pkt->sequence;

    /* Payload */
    if (pkt->length > 0) {
        memcpy(&out_buf[idx], pkt->payload, pkt->length);
        idx += pkt->length;
    }

    /* CRC-16 over everything from length to end of payload */
    uint16_t crc = toms_crc16(&out_buf[2], 3 + pkt->length); /* len+type+seq+payload */
    out_buf[idx++] = (uint8_t)(crc & 0xFF);
    out_buf[idx++] = (uint8_t)((crc >> 8) & 0xFF);

    *out_len = idx;
    return true;
}

/* ── Deserialize ──────────────────────────────────────────────────────── */

bool toms_packet_deserialize(const uint8_t *buf, size_t buf_len, toms_packet_t *pkt)
{
    if (!buf || !pkt) {
        return false;
    }

    /* Minimum packet: sync(2) + len(1) + type(1) + seq(1) + crc(2) = 7 */
    if (buf_len < TOMS_PACKET_OVERHEAD) {
        return false;
    }

    /* Check sync word */
    if (buf[0] != TOMS_SYNC_BYTE_HI || buf[1] != TOMS_SYNC_BYTE_LO) {
        return false;
    }

    uint8_t payload_len = buf[2];
    if (payload_len > TOMS_MAX_PAYLOAD_LEN) {
        return false;
    }

    /* Verify total length */
    size_t expected_len = TOMS_PACKET_OVERHEAD + payload_len;
    if (buf_len < expected_len) {
        return false;
    }

    /* Verify CRC */
    uint16_t received_crc = (uint16_t)buf[5 + payload_len]
                          | ((uint16_t)buf[6 + payload_len] << 8);
    uint16_t computed_crc = toms_crc16(&buf[2], 3 + payload_len);
    if (received_crc != computed_crc) {
        return false;
    }

    /* Populate struct */
    pkt->sync     = TOMS_SYNC_WORD;
    pkt->length   = payload_len;
    pkt->msg_type = buf[3];
    pkt->sequence = buf[4];

    if (payload_len > 0) {
        memcpy(pkt->payload, &buf[5], payload_len);
    }

    return true;
}
