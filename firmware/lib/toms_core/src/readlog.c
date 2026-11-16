#include <toms/readlog.h>

#include <errno.h>
#include <string.h>

#include <toms/crc16.h>

#define REC_CRC_AT (TOMS_READLOG_REC - 2u)

static void put_u32(uint8_t *p, uint32_t v)
{
	p[0] = (uint8_t)v;
	p[1] = (uint8_t)(v >> 8);
	p[2] = (uint8_t)(v >> 16);
	p[3] = (uint8_t)(v >> 24);
}

static uint32_t get_u32(const uint8_t *p)
{
	return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
	       ((uint32_t)p[3] << 24);
}

/* True when slot holds an intact record for a seq that belongs in that slot. */
static bool slot_valid(const struct toms_readlog *l, uint32_t slot, uint8_t rec[TOMS_READLOG_REC],
		       uint32_t *seq)
{
	if (l->io.read(l->io.ctx, slot, rec) != 0) {
		return false;
	}
	uint16_t crc = toms_crc16(rec, REC_CRC_AT);
	uint16_t stored = (uint16_t)(rec[REC_CRC_AT] | (rec[REC_CRC_AT + 1u] << 8));

	if (crc != stored) {
		return false;
	}
	uint32_t s = get_u32(rec);

	(void)slot;
	if (s == 0 || rec[4] == 0 || rec[4] > TOMS_READLOG_FRAME_MAX) {
		return false;
	}
	*seq = s;
	return true;
}

int toms_readlog_open(struct toms_readlog *l, const struct toms_readlog_io *io, uint32_t capacity)
{
	if (l == NULL || io == NULL || capacity == 0 || io->read == NULL || io->write == NULL ||
	    io->erase == NULL || io->load_hwm == NULL || io->save_hwm == NULL) {
		return -EINVAL;
	}
	memset(l, 0, sizeof(*l));
	l->io = *io;
	l->capacity = capacity;

	uint32_t hwm = 1;
	int rc = io->load_hwm(io->ctx, &hwm);

	if (rc != 0 && rc != -ENOENT) {
		return rc;
	}
	if (rc == -ENOENT || hwm == 0) {
		hwm = 1;
	}

	uint32_t lo = UINT32_MAX, hi = 0, hi_slot = 0, n = 0;
	uint8_t rec[TOMS_READLOG_REC];

	for (uint32_t s = 0; s < capacity; s++) {
		uint32_t seq;

		if (slot_valid(l, s, rec, &seq)) {
			n++;
			if (seq < lo) {
				lo = seq;
			}
			if (seq > hi) {
				hi = seq;
				hi_slot = s;
			}
		}
	}
	l->count = n;
	l->wslot = n ? (hi_slot + 1u) % capacity : 0;
	l->first_seq = n ? lo : hwm;
	l->next_seq = (n && hi + 1u > hwm) ? hi + 1u : hwm;
	l->hwm = l->next_seq; /* the first append saves a new mark before using a number */
	return 0;
}

int32_t toms_readlog_take_seq(struct toms_readlog *l)
{
	if (l == NULL) {
		return -EINVAL;
	}
	if (l->next_seq >= l->hwm) {
		/* Reserve ahead: after a power cycle numbering resumes at the mark, never below it. */
		uint32_t mark = l->next_seq + TOMS_READLOG_HWM_STEP;
		int rc = l->io.save_hwm(l->io.ctx, mark);

		if (rc != 0) {
			return rc;
		}
		l->hwm = mark;
	}
	return (int32_t)l->next_seq++;
}

int toms_readlog_store(struct toms_readlog *l, const struct toms_card_read_msg *m)
{
	if (l == NULL || m == NULL || m->read_seq == 0) {
		return -EINVAL;
	}
	uint32_t slot = l->wslot;
	uint8_t rec[TOMS_READLOG_REC];
	uint32_t old;
	bool overwrites = slot_valid(l, slot, rec, &old);

	memset(rec, 0, sizeof(rec));
	int len = toms_codec_encode_card_read(&rec[5], TOMS_READLOG_FRAME_MAX, m);

	if (len < 0) {
		return len;
	}
	put_u32(rec, m->read_seq);
	rec[4] = (uint8_t)len;
	uint16_t crc = toms_crc16(rec, REC_CRC_AT);

	rec[REC_CRC_AT] = (uint8_t)crc;
	rec[REC_CRC_AT + 1u] = (uint8_t)(crc >> 8);

	int rc = l->io.write(l->io.ctx, slot, rec);

	if (rc != 0) {
		return rc;
	}
	if (m->read_seq >= l->next_seq) {
		l->next_seq = m->read_seq + 1u;
	}
	l->wslot = (slot + 1u) % l->capacity;
	if (overwrites) {
		if (old > l->sent_upto) {
			l->dropped++;
		}
		if (old >= l->first_seq) {
			l->first_seq = old + 1u;
		}
	} else {
		l->count++;
	}
	return 0;
}

int32_t toms_readlog_append(struct toms_readlog *l, struct toms_card_read_msg *m)
{
	if (l == NULL || m == NULL) {
		return -EINVAL;
	}
	int32_t seq = toms_readlog_take_seq(l);

	if (seq < 0) {
		return seq;
	}
	m->read_seq = (uint32_t)seq;
	int rc = toms_readlog_store(l, m);

	return rc != 0 ? rc : seq;
}

int toms_readlog_get_from(struct toms_readlog *l, uint32_t from_seq, uint8_t *frame, size_t cap,
			  uint32_t *seq_out)
{
	if (l == NULL || frame == NULL || seq_out == NULL) {
		return -EINVAL;
	}
	uint32_t best = 0;
	uint32_t best_slot = 0;
	uint8_t rec[TOMS_READLOG_REC];

	if (from_seq < l->first_seq) {
		from_seq = l->first_seq;
	}
	for (uint32_t s = 0; s < l->capacity; s++) {
		uint32_t seq;

		if (slot_valid(l, s, rec, &seq) && seq >= from_seq && (best == 0 || seq < best)) {
			best = seq;
			best_slot = s;
		}
	}
	if (best == 0) {
		return -ENOENT;
	}
	if (!slot_valid(l, best_slot, rec, &best)) {
		return -EIO;
	}
	if (cap < rec[4]) {
		return -ENOSPC;
	}
	memcpy(frame, &rec[5], rec[4]);
	*seq_out = best;
	return (int)rec[4];
}

int toms_readlog_ack(struct toms_readlog *l, uint32_t upto_seq)
{
	if (l == NULL) {
		return -EINVAL;
	}
	uint8_t rec[TOMS_READLOG_REC];
	uint32_t left = 0;

	for (uint32_t s = 0; s < l->capacity; s++) {
		uint32_t seq;

		if (!slot_valid(l, s, rec, &seq)) {
			continue;
		}
		if (seq <= upto_seq) {
			int rc = l->io.erase(l->io.ctx, s);

			if (rc != 0) {
				return rc;
			}
		} else {
			left++;
		}
	}
	l->count = left;
	if (upto_seq >= l->next_seq) {
		upto_seq = l->next_seq - 1u;
	}
	if (upto_seq + 1u > l->first_seq) {
		l->first_seq = upto_seq + 1u;
	}
	return 0;
}

void toms_readlog_mark_sent(struct toms_readlog *l, uint32_t seq)
{
	if (seq > l->sent_upto) {
		l->sent_upto = seq;
	}
}

void toms_readlog_rewind(struct toms_readlog *l, uint32_t from_seq)
{
	if (from_seq == 0) {
		from_seq = 1;
	}
	if (from_seq < l->first_seq) {
		from_seq = l->first_seq;
	}
	if (from_seq - 1u < l->sent_upto) {
		l->sent_upto = from_seq - 1u;
	}
}

uint32_t toms_readlog_unsent(struct toms_readlog *l)
{
	uint8_t rec[TOMS_READLOG_REC];
	uint32_t n = 0;

	for (uint32_t s = 0; s < l->capacity; s++) {
		uint32_t seq;

		if (slot_valid(l, s, rec, &seq) && seq > l->sent_upto) {
			n++;
		}
	}
	return n;
}

uint32_t toms_readlog_pending(const struct toms_readlog *l)
{
	return l->count;
}
