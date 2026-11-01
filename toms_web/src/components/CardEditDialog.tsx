import { useState } from 'react';
import { api } from '../lib/api';
import Modal from './Modal';

interface Props {
  card: { card_uuid: string; serial: string | null; label: string | null; notes: string | null };
  onDone: () => void;
  onClose: () => void;
}

const field = { padding: 10, background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-primary)' } as const;

/** Edit the label and notes. Emptying a box and saving clears it. */
export default function CardEditDialog({ card, onDone, onClose }: Props) {
  const [label, setLabel] = useState(card.label ?? '');
  const [notes, setNotes] = useState(card.notes ?? '');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const changed = label.trim() !== (card.label ?? '') || notes.trim() !== (card.notes ?? '');

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.patch(`/cards/${card.card_uuid}`, { label: label.trim() || null, notes: notes.trim() || null });
      onDone();
    } catch (err) {
      setError((err as { response?: { data?: { error?: string } } }).response?.data?.error ?? 'Could not save. Try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={`Edit ${card.serial ?? 'card'}`} onClose={onClose}>
      <form onSubmit={save} style={{ display: 'grid', gap: 14 }}>
        <label style={{ display: 'grid', gap: 6, fontSize: 14 }}>
          Label
          <input aria-label="Label" value={label} maxLength={100} onChange={(e) => setLabel(e.target.value)} style={field} />
        </label>
        <label style={{ display: 'grid', gap: 6, fontSize: 14 }}>
          Notes
          <input aria-label="Notes" value={notes} maxLength={500} onChange={(e) => setNotes(e.target.value)} style={field} />
        </label>
        {error && <div role="alert" style={{ color: 'var(--danger)', fontSize: 13 }}>{error}</div>}
        <div style={{ display: 'flex', gap: 10 }}>
          <button type="button" onClick={onClose} style={{ flex: 1, padding: 10, background: 'transparent', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-secondary)', cursor: 'pointer' }}>
            Cancel
          </button>
          <button type="submit" className="btn-primary" disabled={busy || !changed} style={{ flex: 1, padding: 10, borderRadius: 6, opacity: busy || !changed ? 0.5 : 1 }}>
            {busy ? 'Saving…' : 'Save'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
