#ifndef TOMS_CRC16_H
#define TOMS_CRC16_H

#include <stddef.h>
#include <stdint.h>

/* CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, xorout 0.
 * Same variant the archived TOMS protocol used (toms_packet_t). */
uint16_t toms_crc16(const uint8_t *data, size_t len);

#endif /* TOMS_CRC16_H */
