import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { loadErrorMessage } from '../lib/loadError';
import { describeEvent } from '../lib/cardLifecycle';
import Modal from './Modal';

interface HistoryEvent {
  id: number;
  action: string;
  from_status: string | null;
  to_status: string | null;
  from_nfc_uid: string | null;
  to_nfc_uid: string | null;
  reason: string | null;
  admin_username: string;
  details: Record<string, unknown>;
  at: string;
}

interface Props {
  card: { card_uuid: string; serial: string | null };
  onClose: () => void;
}

/** Everything that happened to one card, newest first. */
export default function CardHistoryDrawer({ card, onClose }: Props) {
  const [events, setEvents] = useState<HistoryEvent[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .get<{ events: HistoryEvent[] }>(`/cards/${card.card_uuid}/history`)
      .then((r) => !cancelled && setEvents(r.data.events))
      .catch((err) => !cancelled && setError(loadErrorMessage(err, 'the history')));
    return () => {
      cancelled = true;
    };
  }, [card.card_uuid]);

  return (
    <Modal title={`History of ${card.serial ?? 'card'}`} onClose={onClose} side width={440}>
      {error && <div role="alert" style={{ color: 'var(--danger)', fontSize: 14 }}>{error}</div>}
      {!events && !error && <div style={{ color: 'var(--text-secondary)' }}>Loading…</div>}
      {events && events.length === 0 && (
        <div style={{ color: 'var(--text-secondary)', fontSize: 14 }}>
          Nothing recorded yet. Changes made from now on will appear here.
        </div>
      )}
      {events && events.length > 0 && (
        <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 0 }}>
          {events.map((e) => (
            <li key={e.id} data-testid="history-row" style={{ padding: '12px 0', borderBottom: '1px solid var(--border)' }}>
              <div style={{ fontWeight: 600, fontSize: 14 }}>{describeEvent(e)}</div>
              {e.reason && <div style={{ fontSize: 13, color: 'var(--text-primary)', marginTop: 2 }}>Reason: {e.reason}</div>}
              <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 2 }}>
                {e.admin_username} · {new Date(e.at).toLocaleString()}
              </div>
            </li>
          ))}
        </ol>
      )}
    </Modal>
  );
}
