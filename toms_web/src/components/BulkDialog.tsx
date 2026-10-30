import { useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { api } from '../lib/api';
import { NEEDS_REASON, type CardStatus } from '../lib/cardLifecycle';
import { describeCounts, summarizeProblems, type BulkProblem } from '../lib/cardSelection';
import Modal from './Modal';

export type BulkChoice = { kind: 'status'; status: CardStatus; label: string } | { kind: 'vehicle'; vehicleId: string | null; label: string };

export interface BulkTarget {
  /** Cards picked by hand. */
  ids?: string[];
  /** Or everything that matches the registry's filters. */
  filter?: { search?: string; status?: string; vehicle_id?: string };
  count: number;
  counts: Record<string, number>;
}

interface Props {
  choice: BulkChoice;
  target: BulkTarget;
  onDone: (changed: number) => void;
  onClose: () => void;
}

type ApiError = { response?: { status?: number; data?: { error?: string; open_trips?: number; problems?: BulkProblem[] } } };

/**
 * Confirms one change to many cards. Shows what is about to happen, asks for a reason when the change
 * needs one, and if the server refuses lists the cards that were the problem (nothing is changed then).
 */
export default function BulkDialog({ choice, target, onDone, onClose }: Props) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [problems, setProblems] = useState<BulkProblem[]>([]);
  const [openTrips, setOpenTrips] = useState<number | null>(null);

  const needsReason = choice.kind === 'status' && NEEDS_REASON.includes(choice.status);
  const reasonMissing = needsReason && reason.trim() === '';
  const danger = choice.kind === 'status' && (choice.status === 'lost' || choice.status === 'retired');

  async function go() {
    setBusy(true);
    setError(null);
    try {
      const where = target.ids ? { card_uuids: target.ids } : { filter: target.filter ?? {} };
      const body =
        choice.kind === 'status'
          ? { ...where, action: 'status', status: choice.status, reason: reason.trim() || undefined, confirm: openTrips !== null ? true : undefined }
          : { ...where, action: 'vehicle', vehicle_id: choice.vehicleId, reason: reason.trim() || undefined };
      const res = await api.post<{ changed: number }>('/cards/bulk', body);
      onDone(res.data.changed);
    } catch (err) {
      const e = err as ApiError;
      const data = e.response?.data;
      if (e.response?.status === 409 && data?.problems) {
        const onlyTrips = data.problems.every((p) => typeof p.open_trips === 'number');
        if (onlyTrips && typeof data.open_trips === 'number') {
          setOpenTrips(data.open_trips);
          setProblems([]);
        } else {
          setProblems(data.problems);
          setError(data.error ?? 'Some cards could not be changed.');
        }
      } else {
        setError(data?.error ?? 'Could not make that change. Try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  const { lines, more } = summarizeProblems(problems);
  const title = `${choice.label} ${target.count} card${target.count === 1 ? '' : 's'}?`;

  return (
    <Modal title={title} onClose={onClose}>
      <p style={{ margin: '0 0 12px', fontSize: 14, color: 'var(--text-secondary)' }}>
        This applies to {target.count} card{target.count === 1 ? '' : 's'} ({describeCounts(target.counts)}). It is all or nothing: if any card cannot make the change, none are changed.
      </p>

      {(needsReason || choice.kind === 'vehicle') && (
        <label style={{ display: 'grid', gap: 6, fontSize: 14, marginBottom: 12 }}>
          {needsReason ? 'Why? (kept in each card\u2019s history)' : 'Note (optional)'}
          <input
            aria-label="Reason"
            autoFocus
            value={reason}
            maxLength={300}
            onChange={(e) => setReason(e.target.value)}
            style={{ padding: 10, background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-primary)' }}
          />
        </label>
      )}

      {openTrips !== null && (
        <div role="alert" style={{ display: 'flex', gap: 10, padding: 12, marginBottom: 12, border: '1px solid var(--warning)', borderRadius: 8, color: 'var(--warning)', fontSize: 14 }}>
          <AlertTriangle size={18} style={{ flexShrink: 0 }} />
          <span>
            Together these cards have <strong>{openTrips} open trip{openTrips === 1 ? '' : 's'}</strong>, so passengers may still be aboard. The trips stay open on the conductors' phones. Continue anyway?
          </span>
        </div>
      )}

      {error && (
        <div role="alert" style={{ color: 'var(--danger)', fontSize: 13, marginBottom: 12 }}>
          {error}
          {lines.length > 0 && (
            <ul style={{ margin: '6px 0 0', paddingLeft: 18 }}>
              {lines.map((l) => (
                <li key={l}>{l}</li>
              ))}
              {more > 0 && <li>and {more} more</li>}
            </ul>
          )}
        </div>
      )}

      <div style={{ display: 'flex', gap: 10 }}>
        <button type="button" onClick={onClose} style={{ flex: 1, padding: 10, background: 'transparent', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-secondary)', cursor: 'pointer' }}>
          Cancel
        </button>
        <button
          type="button"
          onClick={go}
          disabled={busy || reasonMissing}
          style={{
            flex: 1, padding: 10, borderRadius: 6, border: 'none', fontWeight: 700,
            cursor: busy || reasonMissing ? 'not-allowed' : 'pointer', opacity: busy || reasonMissing ? 0.5 : 1,
            background: danger ? 'var(--danger)' : 'var(--accent)', color: danger ? '#fff' : '#000',
          }}
        >
          {busy ? 'Working…' : openTrips !== null ? `${choice.label} anyway` : choice.label}
        </button>
      </div>
    </Modal>
  );
}
