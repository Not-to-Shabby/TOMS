import { useEffect, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';

interface Props {
  title: string;
  onClose: () => void;
  children: ReactNode;
  width?: number;
  /** Slides in from the right instead of centring, for lists such as history. */
  side?: boolean;
}

/** The dark overlay and panel the card dialogs share. Escape and a click on the backdrop close it. */
export default function Modal({ title, onClose, children, width = 460, side = false }: Props) {
  const panel = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    panel.current?.focus();
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.7)',
        backdropFilter: 'blur(4px)',
        display: 'flex',
        justifyContent: side ? 'flex-end' : 'center',
        alignItems: side ? 'stretch' : 'center',
        zIndex: 1000,
        padding: side ? 0 : 12,
      }}
    >
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="glass-panel"
        style={{
          width: '100%',
          maxWidth: width,
          maxHeight: side ? '100%' : '92vh',
          overflow: 'auto',
          padding: 24,
          outline: 'none',
          borderRadius: side ? '16px 0 0 16px' : undefined,
          background: '#0d1320',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, marginBottom: 14 }}>
          <h3 style={{ margin: 0, fontSize: 19 }}>{title}</h3>
          <button type="button" aria-label="Close" onClick={onClose} style={{ background: 'transparent', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer' }}>
            <X size={20} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
