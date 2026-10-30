import { describe, expect, it } from 'vitest';
import { CARD_STATUSES, allowedMoves, canUnpair, confirmationFor, describeEvent, statusActions } from './cardLifecycle';

describe('the status table matches the server', () => {
  // These are the same expectations as toms_backend/test/cardLifecycle.test.ts. If one side changes, both must.
  it('has the same allowed moves', () => {
    expect(allowedMoves('unpaired', false)).toEqual(['retired']);
    expect(allowedMoves('active', true)).toEqual(['suspended', 'lost', 'retired']);
    expect(allowedMoves('suspended', true)).toEqual(['active', 'lost', 'retired']);
    expect(allowedMoves('lost', true)).toEqual(['active', 'retired']);
    expect(allowedMoves('retired', true)).toEqual(['active']);
    expect(allowedMoves('retired', false)).toEqual(['unpaired']);
  });

  it('lists the same statuses', () => {
    expect([...CARD_STATUSES]).toEqual(['unpaired', 'active', 'suspended', 'lost', 'retired']);
  });
});

describe('statusActions', () => {
  const labels = (s: Parameters<typeof statusActions>[0], chip = true) => statusActions(s, chip).map((a) => a.label);

  it('offers suspend, lost and retire on an active card', () => {
    expect(labels('active')).toEqual(['Suspend', 'Mark lost', 'Retire']);
  });

  it('calls bringing a lost card back "Found it", and a retired one "Restore"', () => {
    expect(labels('lost')).toEqual(['Found it', 'Retire']);
    expect(labels('retired')).toEqual(['Restore']);
  });

  it('calls bringing a suspended card back "Activate"', () => {
    expect(labels('suspended')).toEqual(['Activate', 'Mark lost', 'Retire']);
  });

  it('offers only retire on a card still waiting for a chip, and restore for a chipless retired one', () => {
    expect(labels('unpaired', false)).toEqual(['Retire']);
    expect(labels('retired', false)).toEqual(['Restore']);
    expect(statusActions('retired', false)[0].to).toBe('unpaired');
  });

  it('never offers a move the server would refuse', () => {
    for (const s of CARD_STATUSES) {
      for (const chip of [true, false]) {
        const allowed = allowedMoves(s, chip);
        for (const a of statusActions(s, chip)) expect(allowed).toContain(a.to);
      }
    }
  });
});

describe('canUnpair', () => {
  it('is only for a card that has a chip', () => {
    expect(canUnpair('active', true)).toBe(true);
    expect(canUnpair('lost', true)).toBe(true);
    expect(canUnpair('unpaired', false)).toBe(false);
  });
});

describe('confirmationFor', () => {
  it('asks for a reason when marking lost or retiring', () => {
    expect(confirmationFor('lost', 'TOMS-0001')?.needsReason).toBe(true);
    expect(confirmationFor('retire', 'TOMS-0001')?.needsReason).toBe(true);
  });

  it('does not ask for a reason to suspend or unpair, but still confirms', () => {
    expect(confirmationFor('suspend', 'TOMS-0001')?.needsReason).toBe(false);
    expect(confirmationFor('unpair', 'TOMS-0001')?.title).toContain('TOMS-0001');
  });

  it('has no confirmation for harmless actions', () => {
    for (const a of ['activate', 'found', 'restore', 'edit', 'history', 'qr'] as const) expect(confirmationFor(a, 'X')).toBeNull();
  });
});

describe('describeEvent', () => {
  const base = { from_status: null, to_status: null, from_nfc_uid: null, to_nfc_uid: null, details: {} };

  it('describes a status change and a pairing', () => {
    expect(describeEvent({ ...base, action: 'status_changed', from_status: 'active', to_status: 'lost' })).toBe('Status active to lost');
    expect(describeEvent({ ...base, action: 'paired', to_nfc_uid: '04AA' })).toBe('Chip 04AA paired');
  });

  it('describes a vehicle change including the spare pool', () => {
    expect(describeEvent({ ...base, action: 'vehicle_changed', details: { vehicle_id: { from: null, to: 'BUS-1' } } })).toBe('Vehicle spare pool to BUS-1');
  });

  it('names what was edited', () => {
    expect(describeEvent({ ...base, action: 'edited', details: { label: { from: 'a', to: 'b' }, notes: { from: null, to: 'x' } } })).toBe('Edited label and notes');
  });

  it('describes a replaced chip and a card with a chip taken off', () => {
    expect(describeEvent({ ...base, action: 'replaced', from_nfc_uid: '04AA', to_nfc_uid: '04BB' })).toBe('Chip changed from 04AA to 04BB');
    expect(describeEvent({ ...base, action: 'unpaired', from_nfc_uid: '04AA' })).toBe('Chip 04AA taken off');
  });

  it('falls back to the action name for something new', () => {
    expect(describeEvent({ ...base, action: 'some_new_thing' })).toBe('some new thing');
  });
});
