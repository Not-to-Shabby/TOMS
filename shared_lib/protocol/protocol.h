/**
 * @file protocol.h
 * @brief TOMS shared packet format, message types, and CRC utilities.
 *
 * This module defines the wire protocol used by both ESP-NOW and UART
 * communication channels. Every packet uses the same framing: a 2-byte
 * sync word, length, type, sequence number, payload, and CRC-16-CCITT.
 */

#ifndef TOMS_PROTOCOL_H
#define TOMS_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ── Packet Constants ─────────────────────────────────────────────────── */

#define TOMS_SYNC_WORD          0xAA55
#define TOMS_SYNC_BYTE_HI       0xAA
#define TOMS_SYNC_BYTE_LO       0x55
#define TOMS_MAX_PAYLOAD_LEN    240
#define TOMS_PACKET_OVERHEAD    7       /* sync(2) + len(1) + type(1) + seq(1) + crc(2) */
#define TOMS_MAX_PACKET_LEN     (TOMS_MAX_PAYLOAD_LEN + TOMS_PACKET_OVERHEAD)

/* ── Message Types ────────────────────────────────────────────────────── */

typedef enum {
    /* Slave → Master */
    TOMS_MSG_PASSENGER_BOARD    = 0x01,  /**< Passenger boarding event */
    TOMS_MSG_BUTTON_PRESS       = 0x02,  /**< Button press notification */
    TOMS_MSG_UID_RESPONSE       = 0x03,  /**< Slave UID response to handshake */
    TOMS_MSG_RELEASE            = 0x04,  /**< Passenger alighted, slot release request */

    /* Master → Slave */
    TOMS_MSG_FARE_TABLE_UPDATE  = 0x10,  /**< Fare table push */
    TOMS_MSG_CONFIG_SYNC        = 0x11,  /**< Config: route, vehicle ID, etc. */
    TOMS_MSG_BOARD_COMMAND      = 0x12,  /**< Master-driven boarding command (fare + QR trigger) */
    TOMS_MSG_UID_REQUEST        = 0x13,  /**< Master requests slave UID (legacy, kept for reference) */
    TOMS_MSG_FORCE_RELEASE      = 0x14,  /**< Remotely clear boarding state */
    TOMS_MSG_NFC_BOARD_CMD      = 0x15,  /**< Compact NFC boarding command (dictionary-based) */

    /* ACK/NACK */
    TOMS_MSG_ACK_MASTER         = 0x20,  /**< Acknowledgment from master */
    TOMS_MSG_ACK_SLAVE          = 0x21,  /**< Acknowledgment from slave */
    TOMS_MSG_NFC_ACK            = 0x22,  /**< Compact NFC acknowledgment */
    TOMS_MSG_NACK               = 0x2F,  /**< Negative acknowledgment */

    /* Master → Slave (Alarm) */
    TOMS_MSG_ALARM_CMD          = 0x30,  /**< Proximity alarm command to slave */

    /* Utility */
    TOMS_MSG_HEARTBEAT          = 0xF0,  /**< Heartbeat / ping */
    TOMS_MSG_TIME_SYNC          = 0xF1,  /**< Epoch timestamp sync */
} toms_msg_type_t;

/* ── Boarding Types ───────────────────────────────────────────────────── */

typedef enum {
    TOMS_BOARD_CARD     = 0,
    TOMS_BOARD_BUTTON   = 1,
    TOMS_BOARD_QR       = 2,
} toms_boarding_type_t;

/* ── Packet Structure ─────────────────────────────────────────────────── */

/**
 * @brief Raw TOMS packet (on-wire format).
 *
 * Layout on wire (little-endian):
 *   [0xAA][0x55][LEN][TYPE][SEQ][PAYLOAD...][CRC_LO][CRC_HI]
 */
typedef struct __attribute__((packed)) {
    uint16_t sync;                          /**< Always TOMS_SYNC_WORD */
    uint8_t  length;                        /**< Payload length (0–240) */
    uint8_t  msg_type;                      /**< toms_msg_type_t */
    uint8_t  sequence;                      /**< Monotonic packet counter */
    uint8_t  payload[TOMS_MAX_PAYLOAD_LEN]; /**< Variable-length payload */
    /* CRC is appended after payload[length] at serialization time */
} toms_packet_t;

/* ── Passenger Event Payload ──────────────────────────────────────────── */

typedef struct __attribute__((packed)) {
    uint32_t timestamp;             /**< Epoch seconds (UTC) */
    uint8_t  passenger_id[16];      /**< Card UID or generated UUID */
    uint8_t  boarding_type;         /**< toms_boarding_type_t */
    uint16_t fare_centavos;         /**< Fare in centavos */
    uint8_t  seat_number;           /**< Seat number (0 = unassigned) */
    uint8_t  route_id;              /**< Route identifier */
} toms_passenger_event_t;

/* ── Config Sync Payload ──────────────────────────────────────────────── */

typedef struct __attribute__((packed)) {
    uint8_t  vehicle_id[16];        /**< Vehicle UUID */
    uint8_t  route_id;              /**< Active route */
    uint16_t base_fare_centavos;    /**< Base fare */
    uint16_t per_km_centavos;       /**< Per-km rate */
    uint32_t epoch_time;            /**< Current epoch (for slave time sync) */
} toms_config_payload_t;

/* ── UID Payload (Slave → Master) ────────────────────────────────────────── */

/**
 * @brief Slave UID response sent to Master upon UART handshake or ESP-NOW poll.
 *
 * The uid field contains the ESP32's unique eFuse-based MAC address (6 bytes),
 * zero-padded to 16 bytes. The Master uses this to authenticate the Slave and
 * route boarding commands to specific seats/zones.
 */
typedef struct __attribute__((packed)) {
    uint8_t  uid[16];               /**< Slave unique ID (eFuse MAC, zero-padded) */
    uint8_t  seat_number;           /**< Physical seat or zone this slave is assigned to */
    uint8_t  firmware_version[4];   /**< Major, Minor, Patch, Reserved */
} toms_uid_payload_t;

/* ── Board Command Payload (Master → Slave) ──────────────────────────────── */

/**
 * @brief Master-driven boarding command addressed to a specific Slave UID.
 *
 * The Slave MUST validate that target_uid matches its own UID before
 * processing. If it does not match, the packet is silently dropped.
 */
typedef struct __attribute__((packed)) {
    uint8_t  target_uid[16];        /**< UID of the intended slave (anti-fraud validation) */
    uint32_t timestamp;             /**< Epoch seconds (UTC) from Master */
    uint16_t fare_centavos;         /**< Fare in centavos to display */
    uint8_t  seat_number;           /**< Seat number assigned */
    uint8_t  route_id;              /**< Route identifier */
    uint8_t  vehicle_id[16];        /**< Vehicle UUID for QR receipt */
    uint8_t  boarding_type;         /**< toms_boarding_type_t: CARD=0, BUTTON=1, QR=2 */
    char     origin[12];            /**< Boarding stop name (max 11 chars + null) */
    char     destination[12];       /**< Alighting stop name (max 11 chars + null) */
} toms_board_command_t;

/* ── Alarm Command Payload (Master → Slave) ──────────────────────────────── */

/**
 * @brief Proximity alarm command sent by Master to a specific Slave.
 *
 * Slave MUST validate target_uid against its own UID before processing.
 * On match, the Slave shows a red alarm screen prompting the passenger to pay.
 *
 * alarm_type values:
 *   0 = Proximity warning  (approaching destination, N minutes away)
 *   1 = Final stop         (vehicle is at/past the destination stop)
 */
typedef struct __attribute__((packed)) {
    uint8_t  target_uid[16];   /**< UID of the intended slave (anti-fraud) */
    uint8_t  alarm_type;       /**< 0 = proximity warning, 1 = final stop */
    uint8_t  minutes_left;     /**< Estimated minutes to destination (0 if final) */
} toms_alarm_cmd_t;

/* ── NFC Compact Board Command (dictionary-based, 6 bytes) ────────────── */

/**
 * @brief Compact boarding command for NFC-DEP exchange.
 *
 * Instead of transmitting full fare/route data, only a fare_id index
 * is sent. Both Master and Slave look up the details from their
 * pre-synced fare dictionary (NVS).
 */
typedef struct __attribute__((packed)) {
    uint8_t  fare_id;           /**< Index into shared fare dictionary */
    uint8_t  seat_number;       /**< Seat assignment */
    uint32_t timestamp;         /**< Epoch seconds (UTC) */
} toms_nfc_board_cmd_t;

/* ── NFC Compact ACK (1 byte) ─────────────────────────────────────────── */

#define TOMS_NFC_ACK_OK             0x00
#define TOMS_NFC_ACK_UID_MISMATCH   0x01
#define TOMS_NFC_ACK_DICT_MISS      0x02
#define TOMS_NFC_ACK_ERROR          0xFF

typedef struct __attribute__((packed)) {
    uint8_t status;             /**< TOMS_NFC_ACK_* status code */
} toms_nfc_ack_t;

/* ── Heartbeat Payload (Slave → Master, optional) ─────────────────────── */

/**
 * @brief Optional payload for TOMS_MSG_HEARTBEAT packets.
 *
 * The Slave embeds its LiPo battery level (read via ADC on GPIO 1) so the
 * Master can relay it to the phone for display on the Passenger Slot Card.
 *
 * A zero-length heartbeat (legacy) is still valid — consumers must check
 * pkt.length >= sizeof(toms_heartbeat_payload_t) before casting.
 */
typedef struct __attribute__((packed)) {
    uint8_t  uid[6];         /**< Slave eFuse MAC (first 6 bytes), for routing */
    uint16_t battery_mv;     /**< LiPo voltage in millivolts (e.g. 3700) */
    uint8_t  battery_pct;    /**< State-of-charge estimate 0–100% */
} toms_heartbeat_payload_t;

/* ── CRC Functions ────────────────────────────────────────────────────── */

/**
 * @brief Compute CRC-16-CCITT over a byte buffer.
 * @param data  Pointer to data.
 * @param len   Number of bytes.
 * @return CRC-16 value.
 */
uint16_t toms_crc16(const uint8_t *data, size_t len);

/* ── Serialization ────────────────────────────────────────────────────── */

/**
 * @brief Serialize a toms_packet_t into a transmit buffer.
 *
 * Writes sync word, header, payload, and appended CRC to `out_buf`.
 *
 * @param pkt       Packet to serialize (payload must be filled up to pkt->length).
 * @param out_buf   Output buffer (must be at least TOMS_MAX_PACKET_LEN bytes).
 * @param out_len   [out] Number of bytes written.
 * @return true on success, false if pkt->length > TOMS_MAX_PAYLOAD_LEN.
 */
bool toms_packet_serialize(const toms_packet_t *pkt, uint8_t *out_buf, size_t *out_len);

/**
 * @brief Deserialize a raw byte buffer into a toms_packet_t.
 *
 * Validates sync word and CRC.
 *
 * @param buf       Input buffer.
 * @param buf_len   Length of input buffer.
 * @param pkt       [out] Parsed packet.
 * @return true if packet is valid (sync + CRC match), false otherwise.
 */
bool toms_packet_deserialize(const uint8_t *buf, size_t buf_len, toms_packet_t *pkt);

/**
 * @brief Build a packet with the given parameters (convenience wrapper).
 *
 * @param pkt       [out] Packet structure to fill.
 * @param msg_type  Message type.
 * @param seq       Sequence number.
 * @param payload   Payload data (can be NULL if payload_len == 0).
 * @param payload_len Length of payload.
 * @return true on success.
 */
bool toms_packet_build(toms_packet_t *pkt, toms_msg_type_t msg_type,
                       uint8_t seq, const uint8_t *payload, uint8_t payload_len);

#ifdef __cplusplus
}
#endif

#endif /* TOMS_PROTOCOL_H */
