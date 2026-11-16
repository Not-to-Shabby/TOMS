/* Flash-backed log of card reads (firmware/PROTOCOL.md: reads are numbered, kept in flash until
 * the phone has them, and sent in order). Pure code over a small storage interface, so the same
 * logic runs on the host against a fake flash that can lose power in the middle of a write.
 *
 * Layout: slots are used round robin, one fixed 32-byte record each:
 * [seq u32][len u8][frame ...][crc16 u16 in the last two bytes]. A record is valid only when the
 * crc matches, so a torn write is never sent. The write pointer is rebuilt at open from the
 * slot after the highest sequence number. Sequence numbers
 * never go back across a power cycle: a high-water mark is saved ahead of use, in steps. */
#ifndef TOMS_READLOG_H
#define TOMS_READLOG_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <toms/codec.h>

#define TOMS_READLOG_REC 32u
#define TOMS_READLOG_FRAME_MAX (TOMS_READLOG_REC - 7u) /* seq + len + crc */
#define TOMS_READLOG_HWM_STEP 32u

struct toms_readlog_io {
	void *ctx;
	/* 0 = a record was there, -ENOENT = empty slot */
	int (*read)(void *ctx, uint32_t slot, uint8_t rec[TOMS_READLOG_REC]);
	int (*write)(void *ctx, uint32_t slot, const uint8_t rec[TOMS_READLOG_REC]);
	int (*erase)(void *ctx, uint32_t slot);
	/* -ENOENT when nothing was saved yet */
	int (*load_hwm)(void *ctx, uint32_t *hwm);
	int (*save_hwm)(void *ctx, uint32_t hwm);
};

struct toms_readlog {
	struct toms_readlog_io io;
	uint32_t capacity;
	uint32_t first_seq; /* oldest read the phone may still need */
	uint32_t next_seq;  /* seq the next read gets */
	uint32_t wslot;     /* slot the next read is written to */
	uint32_t hwm;       /* saved high-water mark, always >= next_seq */
	uint32_t count;     /* valid records between first_seq and next_seq */
	uint32_t sent_upto; /* highest seq notified on a live link this boot (RAM only) */
	uint32_t dropped;   /* never-sent reads overwritten because the log was full */
};

/* Scans the storage and rebuilds the state. Returns 0 or a negative errno. */
int toms_readlog_open(struct toms_readlog *l, const struct toms_readlog_io *io,
		      uint32_t capacity);

/* Hands out the next read_seq without storing anything (a read delivered live needs a number
 * but no flash record). Numbers are never reused, also across a power cycle. */
int32_t toms_readlog_take_seq(struct toms_readlog *l);

/* Stores the read under the read_seq already in m->read_seq (from toms_readlog_take_seq). */
int toms_readlog_store(struct toms_readlog *l, const struct toms_card_read_msg *m);

/* take_seq + store in one step. Gives the read its read_seq, stores it, returns the seq (> 0) or a negative errno. The log
 * overwrites the oldest read when full and counts it in `dropped`. */
int32_t toms_readlog_append(struct toms_readlog *l, struct toms_card_read_msg *m);

/* First stored frame with seq >= from_seq. Returns the frame length and sets *seq, or -ENOENT. */
int toms_readlog_get_from(struct toms_readlog *l, uint32_t from_seq, uint8_t *frame, size_t cap,
			  uint32_t *seq);

/* The phone has everything up to and including upto_seq: free those records. */
int toms_readlog_ack(struct toms_readlog *l, uint32_t upto_seq);

/* Records are kept until acked, or until their slot is reused. Marking a read sent (it went out
 * on a live link) only lets the log reuse its slot without counting a loss; it stays in flash
 * and is sent again after a reboot until the phone acks it. */
void toms_readlog_mark_sent(struct toms_readlog *l, uint32_t seq);

/* The link dropped: reads from from_seq on may not have arrived, send them again. */
void toms_readlog_rewind(struct toms_readlog *l, uint32_t from_seq);

/* Stored reads the phone has not been sent on this boot. */
uint32_t toms_readlog_unsent(struct toms_readlog *l);

uint32_t toms_readlog_pending(const struct toms_readlog *l);

#endif /* TOMS_READLOG_H */
