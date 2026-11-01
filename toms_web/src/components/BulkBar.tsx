import { Printer, X } from 'lucide-react';
import type { CardStatus } from '../lib/cardLifecycle';
import { statusActions } from '../lib/cardLifecycle';
import { describeCounts } from '../lib/cardSelection';
import type { BulkChoice } from './BulkDialog';

interface Props {
  count: number;
  counts: Record<string, number>;
  /** Status changes every selected card can make. */
  moves: CardStatus[];
  vehicles: { id: string; plate_number: string }[];
  onChoose: (choice: BulkChoice) => void;
  onReprint: () => void;
  onClear: () => void;
  /** Offered when the whole filter, not only the visible cards, could be selected. */
  allMatching?: { total: number; onSelect: () => void } | null;
}

const button = { padding: '7px 12px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--bg-dark)', cursor: 'pointer', fontSize: 13, fontWeight: 600 } as const;
const tone = { normal: 'var(--text-primary)', warning: 'var(--warning)', danger: 'var(--danger)', good: 'var(--success)' } as const;

/** The strip that appears when cards are ticked: what is selected, and what can be done to all of them. */
export default function BulkBar({ count, counts, moves, vehicles, onChoose, onReprint, onClear, allMatching }: Props) {
  if (count === 0) return null;

  // statusActions gives the button text and colour for a move; any status works as the "from" because the
  // wording only depends on where the card is going, except found/restore, which bulk words as Activate.
  const labelFor = (to: CardStatus) => {
    const a = statusActions('active', true).find((x) => x.to === to) ?? statusActions('suspended', true).find((x) => x.to === to) ?? statusActions('lost', true).find((x) => x.to === to);
    const names: Partial<Record<CardStatus, string>> = { active: 'Activate', unpaired: 'Restore' };
    return { label: names[to] ?? a?.label ?? to, tone: a?.tone ?? 'normal' };
  };

  return (
    <div
      role="region"
      aria-label="Selected cards"
      className="glass-panel"
      style={{ position: 'sticky', top: 0, zIndex: 20, padding: '12px 16px', display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center', border: '1px solid var(--accent)' }}
    >
      <strong style={{ fontSize: 14 }}>{count} selected</strong>
      <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>({describeCounts(counts)})</span>
      {allMatching && (
        <button type="button" onClick={allMatching.onSelect} style={{ ...button, color: 'var(--accent)', borderColor: 'var(--accent)' }}>
          Select all {allMatching.total} that match
        </button>
      )}
      <span style={{ flex: 1 }} />

      {moves.map((to) => {
        const { label, tone: t } = labelFor(to);
        return (
          <button key={to} type="button" onClick={() => onChoose({ kind: 'status', status: to, label })} style={{ ...button, color: tone[t] }}>
            {label}
          </button>
        );
      })}

      <select
        aria-label="Move to vehicle"
        value=""
        onChange={(e) => {
          if (e.target.value === '') return;
          const vehicleId = e.target.value === '__spare__' ? null : e.target.value;
          onChoose({ kind: 'vehicle', vehicleId, label: vehicleId ? `Move to ${vehicleId}` : 'Move to spare pool' });
        }}
        style={{ ...button, color: 'var(--text-primary)' }}
      >
        <option value="">Move to vehicle…</option>
        <option value="__spare__">Spare pool</option>
        {vehicles.map((v) => (
          <option key={v.id} value={v.id}>{v.id} ({v.plate_number})</option>
        ))}
      </select>

      <button type="button" onClick={onReprint} style={{ ...button, color: 'var(--accent)', display: 'inline-flex', gap: 6, alignItems: 'center' }}>
        <Printer size={14} /> Reprint
      </button>
      <button type="button" aria-label="Clear selection" onClick={onClear} style={{ ...button, display: 'inline-flex', padding: '7px 8px' }}>
        <X size={14} />
      </button>
    </div>
  );
}
