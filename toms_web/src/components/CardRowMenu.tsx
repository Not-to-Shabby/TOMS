import { useEffect, useRef, useState } from 'react';
import { MoreVertical } from 'lucide-react';
import { canReplaceChip, canUnpair, statusActions, type ActionInfo, type CardStatus, type RowAction } from '../lib/cardLifecycle';

interface Props {
  serial: string;
  status: CardStatus;
  hasChip: boolean;
  onAction: (action: RowAction, to?: CardStatus) => void;
}

const tone = { normal: 'var(--text-primary)', warning: 'var(--warning)', danger: 'var(--danger)', good: 'var(--success)' } as const;

/** One menu per card. It only lists changes the server will accept for the card's current status. */
export default function CardRowMenu({ serial, status, hasChip, onAction }: Props) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const away = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', away);
    document.addEventListener('keydown', esc);
    return () => {
      document.removeEventListener('mousedown', away);
      document.removeEventListener('keydown', esc);
    };
  }, [open]);

  const items: ActionInfo[] = [
    { action: 'edit', label: 'Edit label and notes', tone: 'normal' },
    { action: 'history', label: 'History', tone: 'normal' },
    ...(canReplaceChip(status, hasChip) ? [{ action: 'replace', label: 'Replace chip', tone: 'normal' } as ActionInfo] : []),
    ...statusActions(status, hasChip),
    ...(canUnpair(status, hasChip) ? [{ action: 'unpair', label: 'Take chip off', tone: 'warning' } as ActionInfo] : []),
  ];

  return (
    <div ref={box} style={{ position: 'relative', display: 'inline-block' }}>
      <button
        type="button"
        aria-label={`Actions for ${serial}`}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        style={{ padding: '6px 8px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--bg-dark)', color: 'var(--text-secondary)', cursor: 'pointer', display: 'inline-flex' }}
      >
        <MoreVertical size={16} />
      </button>
      {open && (
        <div
          role="menu"
          style={{ position: 'absolute', right: 0, top: '110%', zIndex: 50, minWidth: 190, background: '#0d1320', border: '1px solid var(--border)', borderRadius: 8, boxShadow: '0 8px 24px rgba(0,0,0,0.5)', padding: 4, textAlign: 'left' }}
        >
          {items.map((it) => (
            <button
              key={`${it.action}-${it.to ?? ''}`}
              role="menuitem"
              type="button"
              onClick={() => {
                setOpen(false);
                onAction(it.action, it.to);
              }}
              style={{ display: 'block', width: '100%', textAlign: 'left', padding: '9px 12px', background: 'transparent', border: 'none', borderRadius: 6, color: tone[it.tone], cursor: 'pointer', fontSize: 13 }}
            >
              {it.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
