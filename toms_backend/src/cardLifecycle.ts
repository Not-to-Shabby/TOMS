export const CARD_STATUSES = ['unpaired', 'active', 'suspended', 'lost', 'retired'] as const;
export type CardStatus = (typeof CARD_STATUSES)[number];

/** Marking a card lost or retired is a decision someone may need to explain later. */
export const NEEDS_REASON: readonly CardStatus[] = ['lost', 'retired'];

/** Moving a card to one of these can strand a passenger who is still on a trip with it. */
export const WARNS_OPEN_TRIPS: readonly CardStatus[] = ['suspended', 'lost', 'retired'];

/**
 * Which status a card may move to next. One table, used by every route.
 * - unpaired -> active only happens by pairing a chip, never by changing the status.
 * - retired is final except restoring: back to active when it still has its chip, back to unpaired when it
 *   was retired before it ever got one.
 */
export function allowedMoves(from: CardStatus, hasChip: boolean): CardStatus[] {
  switch (from) {
    case 'unpaired':
      return ['retired'];
    case 'active':
      return ['suspended', 'lost', 'retired'];
    case 'suspended':
      return ['active', 'lost', 'retired'];
    case 'lost':
      return ['active', 'retired'];
    case 'retired':
      return hasChip ? ['active'] : ['unpaired'];
  }
}

export type MoveCheck = { ok: true } | { ok: false; error: string };

export function checkMove(from: CardStatus, to: CardStatus, hasChip: boolean): MoveCheck {
  if (allowedMoves(from, hasChip).includes(to)) return { ok: true };
  if (from === 'unpaired' && (to === 'active' || to === 'suspended')) {
    return { ok: false, error: 'A card with no NFC chip paired cannot be made active or suspended. Pair it first.' };
  }
  if (from === 'retired') {
    return { ok: false, error: 'A retired card can only be restored. Restore it first, then change it.' };
  }
  return { ok: false, error: `A ${from} card cannot be changed to ${to}.` };
}
