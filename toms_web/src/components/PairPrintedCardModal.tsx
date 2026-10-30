import { useEffect, useMemo, useRef, useState } from 'react';
import { Camera, X, Search } from 'lucide-react';
import { api } from '../lib/api';
import { cameraAvailable, cardUuidFromQr, startQrScan } from '../lib/qrScan';
import PairPanel, { type PairingInfo } from './PairPanel';

interface Props {
  /** Cards that are still waiting for a chip. */
  waiting: PairingInfo[];
  onClose: () => void;
  /** Called after a pairing or undo so the registry list can refresh. */
  onChanged: () => void;
}

/**
 * Pair a printed card from inside the registry: scan the QR on the card (or pick its serial from the
 * list), then tap the card to the phone.
 */
export default function PairPrintedCardModal({ waiting, onClose, onChanged }: Props) {
  const [picked, setPicked] = useState<PairingInfo | null>(null);
  const [scanning, setScanning] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const stopRef = useRef<(() => void) | null>(null);

  const stopCamera = () => {
    stopRef.current?.();
    stopRef.current = null;
    setScanning(false);
  };

  useEffect(() => () => stopRef.current?.(), []);

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return q ? waiting.filter((c) => `${c.serial ?? ''} ${c.label ?? ''}`.toLowerCase().includes(q)) : waiting;
  }, [waiting, filter]);

  async function pickByUuid(uuid: string) {
    try {
      const res = await api.get<PairingInfo>(`/cards/${uuid}/pairing`);
      if (res.data.status !== 'unpaired') {
        setMessage(
          `${res.data.serial ?? 'That card'} is already paired${res.data.nfc_uid ? ` to ${res.data.nfc_uid}` : ''}. ` +
            'Scan a different card, or unpair it first from its own page.',
        );
        return;
      }
      setMessage(null);
      setPicked(res.data);
    } catch (err) {
      const status = (err as { response?: { status?: number } }).response?.status;
      setMessage(status === 404 ? 'This QR code does not belong to any card made here.' : 'Could not look up that card. Try again.');
    }
  }

  async function startCamera() {
    setMessage(null);
    if (!videoRef.current) return;
    setScanning(true);
    stopRef.current = await startQrScan(
      videoRef.current,
      (text) => {
        stopCamera();
        const uuid = cardUuidFromQr(text);
        if (!uuid) return void setMessage('That QR code is not a TOMS card. Scan the QR printed on the card.');
        void pickByUuid(uuid);
      },
      (m) => {
        setMessage(m);
        setScanning(false);
      },
    );
  }

  return (
    <div
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: 12 }}
    >
      <div role="dialog" aria-label="Pair a printed card" className="glass-panel" style={{ width: '100%', maxWidth: 480, maxHeight: '92vh', overflow: 'auto', padding: 24 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <h3 style={{ margin: 0, fontSize: 20 }}>Pair a printed card</h3>
          <button type="button" aria-label="Close" onClick={() => { stopCamera(); onClose(); }} style={{ background: 'transparent', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer' }}>
            <X size={20} />
          </button>
        </div>

        {picked ? (
          <>
            <PairPanel info={picked} onPaired={onChanged} onUnpaired={onChanged} />
            <button
              type="button"
              onClick={() => { setPicked(null); setMessage(null); }}
              style={{ marginTop: 16, width: '100%', padding: 10, background: 'transparent', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-secondary)', cursor: 'pointer' }}
            >
              Pair another card
            </button>
          </>
        ) : (
          <>
            <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--text-secondary)' }}>
              1. Scan the QR printed on the card, or pick its serial below. 2. Tap that same card to the phone.
            </p>

            <video
              ref={videoRef}
              muted
              playsInline
              data-testid="qr-video"
              style={{ width: '100%', borderRadius: 8, background: '#000', display: scanning ? 'block' : 'none', marginBottom: 12 }}
            />
            <button
              type="button"
              className="btn-primary"
              onClick={scanning ? stopCamera : startCamera}
              disabled={!cameraAvailable()}
              style={{ width: '100%', padding: 12, borderRadius: 8, display: 'inline-flex', justifyContent: 'center', gap: 8 }}
            >
              <Camera size={16} /> {scanning ? 'Stop camera' : 'Scan the QR on the card'}
            </button>
            {!cameraAvailable() && (
              <div style={{ fontSize: 12, color: 'var(--warning)', marginTop: 8 }}>
                The camera needs an HTTPS address. Pick the card from the list instead.
              </div>
            )}

            <div style={{ margin: '18px 0 8px', fontSize: 13, color: 'var(--text-secondary)' }}>
              Or pick from the {waiting.length} card{waiting.length === 1 ? '' : 's'} still waiting
            </div>
            <div style={{ position: 'relative', marginBottom: 8 }}>
              <Search size={16} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-secondary)' }} />
              <input
                aria-label="Find a serial"
                placeholder="Find a serial, e.g. TOMS-0012"
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
                style={{ width: '100%', padding: '10px 10px 10px 34px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-primary)' }}
              />
            </div>
            <div style={{ maxHeight: 220, overflow: 'auto', border: '1px solid var(--border)', borderRadius: 6 }}>
              {shown.length === 0 ? (
                <div style={{ padding: 14, fontSize: 13, color: 'var(--text-secondary)' }}>
                  {waiting.length === 0 ? 'Every card is paired. Create more in Print-ready cards.' : 'No serial matches.'}
                </div>
              ) : (
                shown.slice(0, 100).map((c) => (
                  <button
                    key={c.card_uuid}
                    type="button"
                    onClick={() => { setMessage(null); setPicked(c); }}
                    style={{ display: 'block', width: '100%', textAlign: 'left', padding: '10px 14px', background: 'transparent', border: 'none', borderBottom: '1px solid var(--border)', color: 'var(--text-primary)', fontFamily: 'monospace', cursor: 'pointer' }}
                  >
                    {c.serial ?? c.label ?? c.card_uuid.slice(0, 8)}
                  </button>
                ))
              )}
              {shown.length > 100 && <div style={{ padding: 10, fontSize: 12, color: 'var(--text-secondary)' }}>Showing the first 100. Type to narrow the list.</div>}
            </div>
          </>
        )}

        {message && <div role="alert" style={{ color: 'var(--danger)', marginTop: 12, fontSize: 13 }}>{message}</div>}
      </div>
    </div>
  );
}
