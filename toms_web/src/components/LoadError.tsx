import { AlertTriangle } from 'lucide-react';

export default function LoadError({ message, onRetry }: { message: string | null; onRetry?: () => void }) {
  if (!message) return null;
  return (
    <div
      role="alert"
      className="glass-panel"
      style={{ padding: '14px 20px', display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', color: 'var(--danger)', borderColor: 'var(--danger)' }}
    >
      <AlertTriangle size={18} />
      <span style={{ flex: '1 1 240px', fontSize: 14 }}>{message}</span>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          style={{ padding: '6px 14px', borderRadius: 6, border: '1px solid var(--danger)', background: 'transparent', color: 'var(--danger)', cursor: 'pointer', fontWeight: 600 }}
        >
          Try again
        </button>
      )}
    </div>
  );
}
