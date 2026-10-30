import { useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import type { Confirmation } from '../lib/cardLifecycle';
import Modal from './Modal';

interface Props {
  confirmation: Confirmation;
  /** The request that carries out the change. It receives the reason and whether open trips were confirmed. */
  run: (reason: string, confirm: boolean) => Promise<unknown>;
  onDone: () => void;
  onClose: () => void;
}

type ApiError = { response?: { status?: number; data?: { error?: string; open_trips?: number; needs_reason?: boolean } } };

/**
 * Confirms a risky change to a card. Asks for a reason when one is needed. If the server says the card still
 * has passengers on a trip, it shows how many and asks once more before going ahead.
 */
export default function CardActionDialog({ confirmation, run, onDone, onClose }: Props) {
  const [reason, setReason] = useState('');
  const [openTrips, setOpenTrips] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reasonMissing = confirmation.needsReason && reason.trim() === '';

  async function go() {
    setBusy(true);
    setError(null);
    try {
      await run(reason.trim(), openTrips !== null);
      onDone();
    } catch (err) {
      const e = err as ApiError;
      const data = e.response?.data;
      if (e.response?.status === 409 && typeof data?.open_trips === 'number') {
        setOpenTrips(data.open_trips);
      } else {
        setError(data?.error ?? 'Could not make that change. Try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  const danger = confirmation.tone === 'danger';
  return (
    <Modal title={confirmation.title} onClose={onClose}>
      <p style={{ margin: '0 0 14px', fontSize: 14, color: 'var(--text-secondary)' }}>{confirmation.message}</p>

      {confirmation.needsReason && (
        <label style={{ display: 'grid', gap: 6, fontSize: 14, marginBottom: 12 }}>
          Why? (kept in the card's history)
          <input
            aria-label="Reason"
            autoFocus
            value={reason}
            maxLength={300}
            onChange={(e) => setReason(e.target.value)}
            placeholder="e.g. Left on the bus, found damaged"
            style={{ padding: 10, background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-primary)' }}
          />
        </label>
      )}

      {openTrips !== null && (
        <div role="alert" style={{ display: 'flex', gap: 10, padding: 12, marginBottom: 12, border: '1px solid var(--warning)', borderRadius: 8, color: 'var(--warning)', fontSize: 14 }}>
          <AlertTriangle size={18} style={{ flexShrink: 0 }} />
          <span>
            This card has <strong>{openTrips} open trip{openTrips === 1 ? '' : 's'}</strong>, so a passenger may still be aboard with it. The trip stays open on the conductor's phone. Continue anyway?
          </span>
        </div>
      )}

      {error && <div role="alert" style={{ color: 'var(--danger)', fontSize: 13, marginBottom: 12 }}>{error}</div>}

      <div style={{ display: 'flex', gap: 10 }}>
        <button type="button" onClick={onClose} style={{ flex: 1, padding: 10, background: 'transparent', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-secondary)', cursor: 'pointer' }}>
          Cancel
        </button>
        <button
          type="button"
          onClick={go}
          disabled={busy || reasonMissing}
          style={{
            flex: 1,
            padding: 10,
            borderRadius: 6,
            border: 'none',
            fontWeight: 700,
            cursor: busy || reasonMissing ? 'not-allowed' : 'pointer',
            opacity: busy || reasonMissing ? 0.5 : 1,
            background: danger ? 'var(--danger)' : confirmation.tone === 'warning' ? 'var(--warning)' : 'var(--accent)',
            color: danger ? '#fff' : '#000',
          }}
        >
          {busy ? 'Working…' : openTrips !== null ? `${confirmation.confirmLabel} anyway` : confirmation.confirmLabel}
        </button>
      </div>
    </Modal>
  );
}
