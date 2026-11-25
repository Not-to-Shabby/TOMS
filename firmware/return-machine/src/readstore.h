/* Persistent store for the Return Machine: the card-read log (toms/readlog.h) and the
 * per-machine signing key, both kept in the settings (NVS) partition.
 *
 * The signing key is never part of the source or the image. It is written once over an
 * encrypted BLE link (diag command 0x06) and read back only by the signing code. */
#ifndef TOMS_READSTORE_H
#define TOMS_READSTORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <toms/codec.h>
#include <toms/sig.h>

/* Safe to call more than once. Returns 0 or a negative errno. */
int readstore_init(void);

/* Numbers the read, stores it, returns the read_seq (> 0) or a negative errno. */
int32_t readstore_record(struct toms_card_read_msg *m);

/* Number only, for a read delivered live; store it afterwards with readstore_keep() if the
 * notify failed. */
int32_t readstore_take_seq(void);
int readstore_keep(const struct toms_card_read_msg *m);

/* The read went out on a live link (it stays stored until acked). */
void readstore_mark_sent(uint32_t seq);

/* Stored reads not yet sent on this boot. */
uint32_t readstore_unsent(void);

/* Oldest stored read not yet sent on this boot, in read_seq order. The frame is the v1 card_read;
 * *outcome is what the paid check ended with (TOMS_OUTCOME_*). -ENOENT when none. */
int readstore_next_unsent(uint8_t *frame, size_t cap, uint32_t *seq, uint8_t *outcome);

/* A phone (re)connected: offer every stored read again, oldest first. */
void readstore_rewind(void);

/* Oldest stored read with seq >= from_seq: frame length, or -ENOENT. */
int readstore_next(uint32_t from_seq, uint8_t *frame, size_t cap, uint32_t *seq);

/* The phone has every read up to and including upto_seq. */
int readstore_ack(uint32_t upto_seq);

uint32_t readstore_pending(void);
/* Reads overwritten before they were sent live. Kept in flash, so it survives power cycles. */
uint32_t readstore_dropped(void);

/* Copies the signing key out. False when none is provisioned. */
bool keystore_get(uint8_t key[TOMS_SIG_KEY_LEN]);

/* Stores the key if there is none yet. -EEXIST when one is already set. */
int keystore_provision(const uint8_t key[TOMS_SIG_KEY_LEN]);

#endif /* TOMS_READSTORE_H */
