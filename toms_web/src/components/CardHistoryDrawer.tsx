import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { loadErrorMessage } from '../lib/loadError';
import { describeEvent } from '../lib/cardLifecycle';
import { pesos, stopRange, tripStateText } from '../lib/format';
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

export interface CardTrip {
  trip_id: string;
  vehicle_id: string | null;
  boarding_stop_id: string | null;
  declared_destination_stop_id: string | null;
  actual_destination_stop_id: string | null;
  passenger_count: number | null;
  fare_centavos: number | null;
  current_state: string;
  paid: boolean;
  effective_at: string;
}

interface Props {
  card: { card_uuid: string; serial: string | null };
  onClose: () => void;
}

type Tab = 'changes' | 'trips';

/** What happened to one card: changes an admin made, and the trips it has been on. Newest first. */
export default function CardHistoryDrawer({ card, onClose }: Props) {
  const [tab, setTab] = useState<Tab>('changes');
  const [events, setEvents] = useState<HistoryEvent[] | null>(null);
  const [trips, setTrips] = useState<CardTrip[] | null>(null);
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

  // Trips are only fetched when somebody looks at them.
  useEffect(() => {
    if (tab !== 'trips' || trips !== null) return;
    let cancelled = false;
    api
      .get<{ trips: CardTrip[] }>(`/cards/${card.card_uuid}/trips`)
      .then((r) => !cancelled && setTrips(r.data.trips))
      .catch((err) => !cancelled && setError(loadErrorMessage(err, 'the trips')));
    return () => {
      cancelled = true;
    };
  }, [tab, trips, card.card_uuid]);

  const tabButton = (id: Tab, label: string) => (
    <button
      type="button"
      role="tab"
      aria-selected={tab === id}
      onClick={() => setTab(id)}
      style={{
        flex: 1,
        padding: '8px 12px',
        border: 'none',
        borderBottom: `2px solid ${tab === id ? 'var(--accent)' : 'transparent'}`,
        background: 'transparent',
        color: tab === id ? 'var(--accent)' : 'var(--text-secondary)',
        fontWeight: 600,
        cursor: 'pointer',
      }}
    >
      {label}
    </button>
  );

  const loading = tab === 'changes' ? events === null : trips === null;

  return (
    <Modal title={`History of ${card.serial ?? 'card'}`} onClose={onClose} side width={440}>
      <div role="tablist" style={{ display: 'flex', marginBottom: 12, borderBottom: '1px solid var(--border)' }}>
        {tabButton('changes', 'Changes')}
        {tabButton('trips', 'Trips')}
      </div>

      {error && <div role="alert" style={{ color: 'var(--danger)', fontSize: 14 }}>{error}</div>}
      {loading && !error && <div style={{ color: 'var(--text-secondary)' }}>Loading…</div>}

      {tab === 'changes' && events && events.length === 0 && (
        <div style={{ color: 'var(--text-secondary)', fontSize: 14 }}>Nothing recorded yet. Changes made from now on will appear here.</div>
      )}
      {tab === 'changes' && events && events.length > 0 && (
        <ol style={{ listStyle: 'none', margin: 0, padding: 0 }}>
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

      {tab === 'trips' && trips && trips.length === 0 && (
        <div style={{ color: 'var(--text-secondary)', fontSize: 14 }}>This card has not been on a trip yet.</div>
      )}
      {tab === 'trips' && trips && trips.length > 0 && (
        <ol style={{ listStyle: 'none', margin: 0, padding: 0 }}>
          {trips.map((t) => (
            <li key={t.trip_id} data-testid="trip-row" style={{ padding: '12px 0', borderBottom: '1px solid var(--border)' }}>
              <div style={{ fontWeight: 600, fontSize: 14 }}>
                {stopRange(t.boarding_stop_id, t.declared_destination_stop_id, t.actual_destination_stop_id)}
              </div>
              <div style={{ fontSize: 13, marginTop: 2 }}>
                {tripStateText(t.current_state, t.paid)} · {pesos(t.fare_centavos)}
                {t.passenger_count ? ` · ${t.passenger_count} passenger${t.passenger_count === 1 ? '' : 's'}` : ''}
              </div>
              <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 2 }}>
                {t.vehicle_id ? `${t.vehicle_id} · ` : ''}
                {new Date(t.effective_at).toLocaleString()}
              </div>
            </li>
          ))}
        </ol>
      )}
    </Modal>
  );
}
