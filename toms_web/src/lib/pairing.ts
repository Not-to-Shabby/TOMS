/** What the server tells the dashboard about a phone. There is no token in it, and there never will be. */
export interface Phone {
  device_id: string;
  vehicle_id: string | null;
  label: string | null;
  enrolled_at: string;
  revoked_at: string | null;
  revoked: boolean;
  last_seen: string | null;
}

export type CodeStatus = 'open' | 'used' | 'cancelled' | 'locked' | 'expired';

/** A code as the list shows it: its public id and how it ended, never the code itself. */
export interface PairingCodeRow {
  id: number;
  code_id: string;
  vehicle_id: string;
  label: string | null;
  created_by: string;
  created_at: string;
  expires_at: string;
  attempts: number;
  used_at: string | null;
  used_device_id: string | null;
  status: CodeStatus;
}

/**
 * Whether the conductor app has its enrolment screen. It does not yet: the app step of the pairing work (see
 * documentation/device_pairing.md) is not built, so a code issued here cannot be used from a phone and the page
 * says so. The app PR flips this to true and the note goes away.
 */
export const APP_ENROL_SCREEN_READY = false;

/** How often the list is read while a code is on screen, to notice that a phone used it. */
export const POLL_MILLIS = 3000;

export const CODE_STATUS: Record<CodeStatus, { text: string; tone: 'good' | 'warn' | 'bad' | 'neutral' }> = {
  open: { text: 'Open', tone: 'good' },
  used: { text: 'Used', tone: 'neutral' },
  cancelled: { text: 'Cancelled', tone: 'neutral' },
  locked: { text: 'Locked', tone: 'bad' },
  expired: { text: 'Expired', tone: 'neutral' },
};

/** How a code ended, for the note shown when it leaves the screen. */
export type Ended = { kind: 'used'; deviceId: string; vehicleId: string } | { kind: 'locked' | 'cancelled' | 'expired' };

/**
 * What to tell the administrator when a code they were showing is gone, from the server's own record of it.
 * A code that is still open (or missing from the list) reads as expired: it is only asked about once the
 * countdown ended, and a code that lapsed by the server's clock a moment later is not worth keeping on screen.
 */
export function endedFrom(row: PairingCodeRow | undefined): Ended {
  if (row?.status === 'used' && row.used_device_id) return { kind: 'used', deviceId: row.used_device_id, vehicleId: row.vehicle_id };
  if (row?.status === 'locked' || row?.status === 'cancelled') return { kind: row.status };
  return { kind: 'expired' };
}

/** m:ss, rounded up, never negative: 600 is 10:00, 59.2 is 1:00, and anything at or below 0 is 0:00. */
export function formatCountdown(totalSeconds: number): string {
  const s = Math.max(0, Math.ceil(totalSeconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
