export const CARD_STATUSES = ['unpaired', 'active', 'suspended', 'lost', 'retired'] as const;
export type CardStatus = (typeof CARD_STATUSES)[number];

/** Same table as toms_backend/src/cardLifecycle.ts. The server is the one that enforces it. */
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

export const NEEDS_REASON: readonly CardStatus[] = ['lost', 'retired'];

export type RowAction = 'edit' | 'replace' | 'suspend' | 'activate' | 'lost' | 'found' | 'retire' | 'restore' | 'unpair' | 'history' | 'qr';

export interface ActionInfo {
  action: RowAction;
  label: string;
  /** The status this action moves the card to, when it is a status change. */
  to?: CardStatus;
  tone: 'normal' | 'warning' | 'danger' | 'good';
}

const STATUS_ACTIONS: Record<string, Omit<ActionInfo, 'to'>> = {
  suspend: { action: 'suspend', label: 'Suspend', tone: 'warning' },
  activate: { action: 'activate', label: 'Activate', tone: 'good' },
  lost: { action: 'lost', label: 'Mark lost', tone: 'danger' },
  found: { action: 'found', label: 'Found it', tone: 'good' },
  retire: { action: 'retire', label: 'Retire', tone: 'danger' },
  restore: { action: 'restore', label: 'Restore', tone: 'good' },
};

/** Which button a status change to `to` is, given where the card is now. */
function actionFor(from: CardStatus, to: CardStatus): ActionInfo | null {
  const make = (key: string): ActionInfo => ({ ...STATUS_ACTIONS[key], to });
  if (to === 'suspended') return make('suspend');
  if (to === 'lost') return make('lost');
  if (to === 'retired') return make('retire');
  if (to === 'active') return make(from === 'lost' ? 'found' : from === 'retired' ? 'restore' : 'activate');
  if (to === 'unpaired') return make('restore');
  return null;
}

/** The status-changing buttons a card should offer, in the order the admin is most likely to want them. */
export function statusActions(status: CardStatus, hasChip: boolean): ActionInfo[] {
  return allowedMoves(status, hasChip)
    .map((to) => actionFor(status, to))
    .filter((a): a is ActionInfo => a !== null);
}

/** Cards with a chip can have it taken off (wrong card, or a chip being replaced). */
export function canUnpair(status: CardStatus, hasChip: boolean): boolean {
  return hasChip && status !== 'unpaired';
}

/** A chip can be swapped on any card that has one, except a retired card (restore it first). */
export function canReplaceChip(status: CardStatus, hasChip: boolean): boolean {
  return hasChip && status !== 'unpaired' && status !== 'retired';
}

export interface Confirmation {
  title: string;
  message: string;
  needsReason: boolean;
  confirmLabel: string;
  tone: 'warning' | 'danger' | 'normal';
}

/** Words for the confirmation box shown before a risky change. */
export function confirmationFor(action: RowAction, serial: string): Confirmation | null {
  switch (action) {
    case 'lost':
      return { title: `Mark ${serial} as lost?`, message: 'The card stops working on the buses at the next sync. Passengers scanning its QR will see that it is not in use.', needsReason: true, confirmLabel: 'Mark lost', tone: 'danger' };
    case 'retire':
      return { title: `Retire ${serial}?`, message: 'A retired card is taken out of service for good. It can still be restored later.', needsReason: true, confirmLabel: 'Retire', tone: 'danger' };
    case 'suspend':
      return { title: `Suspend ${serial}?`, message: 'The card stops working until you activate it again.', needsReason: false, confirmLabel: 'Suspend', tone: 'warning' };
    case 'unpair':
      return { title: `Take the chip off ${serial}?`, message: 'The printed QR and serial stay. You can pair a chip to it again.', needsReason: false, confirmLabel: 'Unpair', tone: 'warning' };
    default:
      return null;
  }
}

/** Plain words for one history row. */
export function describeEvent(e: {
  action: string;
  from_status: string | null;
  to_status: string | null;
  from_nfc_uid: string | null;
  to_nfc_uid: string | null;
  details: Record<string, { from: unknown; to: unknown } | unknown>;
}): string {
  switch (e.action) {
    case 'created':
      return 'Card created, waiting for a chip';
    case 'enrolled':
      return `Enrolled with chip ${e.to_nfc_uid ?? '(none)'}`;
    case 'paired':
      return `Chip ${e.to_nfc_uid} paired`;
    case 'unpaired':
      return `Chip ${e.from_nfc_uid ?? ''} taken off`.replace('  ', ' ');
    case 'replaced':
      return `Chip changed from ${e.from_nfc_uid ?? '(none)'} to ${e.to_nfc_uid ?? '(none)'}`;
    case 'status_changed':
      return `Status ${e.from_status} to ${e.to_status}`;
    case 'vehicle_changed': {
      const v = e.details.vehicle_id as { from: unknown; to: unknown } | undefined;
      return `Vehicle ${v?.from ?? 'spare pool'} to ${v?.to ?? 'spare pool'}`;
    }
    case 'edited': {
      const parts = Object.keys(e.details).map((k) => k);
      return `Edited ${parts.join(' and ') || 'details'}`;
    }
    default:
      return e.action.replace(/_/g, ' ');
  }
}
