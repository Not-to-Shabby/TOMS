import { useState } from 'react';
import { Radio, CheckCircle2, AlertTriangle, Undo2 } from 'lucide-react';
import { api } from '../lib/api';
import { hasWebNfc, readNfcUid } from '../lib/nfc';

export interface PairingInfo {
  card_uuid: string;
  serial: string | null;
  label: string | null;
  status: string;
  nfc_uid: string | null;
}

export default function PairPanel({ info, onPaired, onUnpaired }: { info: PairingInfo; onPaired?: () => void; onUnpaired?: () => void }) {
  const [uid, setUid] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [done, setDone] = useState(false);
  const name = info.serial ?? info.label ?? info.card_uuid.slice(0, 8);

  async function scan() {
    setError(null);
    setScanning(true);
    const controller = new AbortController();
    try {
      setUid(await readNfcUid(controller.signal));
      if ('vibrate' in navigator) navigator.vibrate(60);
    } catch (err) {
      if ((err as Error).name !== 'AbortError') setError((err as Error).message);
    } finally {
      controller.abort();
      setScanning(false);
    }
  }

  async function confirm() {
    if (!uid) return;
    setSaving(true);
    setError(null);
    try {
      await api.post(`/cards/${info.card_uuid}/pair`, { nfc_uid: uid });
      setDone(true);
      onPaired?.();
    } catch (err) {
      const e = err as { response?: { data?: { error?: string } } };
      setError(e.response?.data?.error ?? 'Could not pair this card.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={{ width: '100%' }}>
      <h3 data-testid="pair-heading" style={{ marginTop: 0 }}>Pair card {name}</h3>
      <p style={{ color: 'var(--text-secondary)', fontSize: 14, margin: '0 0 16px' }}>
        Check that <strong>{name}</strong> is the number printed on the card in your hand, then tap that card to the back of this phone.
      </p>

      {!hasWebNfc() && (
        <div role="alert" style={{ color: 'var(--warning)', fontSize: 13, marginBottom: 12 }}>
          <AlertTriangle size={14} /> Web NFC needs Chrome on Android with NFC turned on.
        </div>
      )}

      {done ? (
        <div style={{ color: 'var(--success)', fontWeight: 700 }}>
          <CheckCircle2 size={16} /> {name} is now paired to {uid}.
        </div>
      ) : (
        <>
          <button
            type="button"
            className="btn-primary"
            onClick={scan}
            disabled={scanning || !hasWebNfc()}
            style={{ width: '100%', padding: 12, borderRadius: 8, display: 'inline-flex', justifyContent: 'center', gap: 8 }}
          >
            <Radio size={16} />
            {scanning ? 'Listening… tap the card to the phone' : uid ? 'Scan a different card' : 'Tap card to scan'}
          </button>

          {uid && (
            <div style={{ marginTop: 16 }}>
              <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>Card chip read</div>
              <div data-testid="scanned-uid" style={{ fontFamily: 'monospace', fontWeight: 700, fontSize: 18 }}>{uid}</div>
              <button
                type="button"
                onClick={confirm}
                disabled={saving}
                className="btn-primary"
                style={{ width: '100%', padding: 12, borderRadius: 8, marginTop: 12 }}
              >
                {saving ? 'Saving…' : `Pair ${uid} to ${name}`}
              </button>
            </div>
          )}
        </>
      )}

      {error && <div role="alert" style={{ color: 'var(--danger)', marginTop: 12, fontSize: 13 }}>{error}</div>}

      {done && (
        <button
          type="button"
          onClick={async () => {
            await api.post(`/cards/${info.card_uuid}/unpair`);
            setDone(false);
            setUid(null);
            onUnpaired?.();
          }}
          style={{ marginTop: 16, background: 'transparent', border: '1px solid var(--border)', borderRadius: 6, padding: '8px 12px', color: 'var(--text-secondary)', cursor: 'pointer' }}
        >
          <Undo2 size={14} /> Undo, this was the wrong card
        </button>
      )}
    </div>
  );
}
