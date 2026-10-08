import { useState, useEffect } from 'react';
import { Plus, Search, QrCode, Printer, Radio, CheckCircle2, AlertTriangle, XCircle } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { api } from '../lib/api';

interface CardItem {
  card_uuid: string;
  nfc_uid: string;
  label: string | null;
  status: 'active' | 'suspended' | 'retired' | 'lost';
  enrolled_at: string;
  notes: string | null;
}

export default function CardRegistry() {
  const [cards, setCards] = useState<CardItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('all');
  const [showEnrollModal, setShowEnrollModal] = useState(false);
  const [selectedCardForQr, setSelectedCardForQr] = useState<CardItem | null>(null);

  // Enrollment Form State
  const [nfcUidInput, setNfcUidInput] = useState('');
  const [labelInput, setLabelInput] = useState('');
  const [notesInput, setNotesInput] = useState('');
  const [enrollError, setEnrollError] = useState<string | null>(null);
  const [nfcScanning, setNfcScanning] = useState(false);
  const [hasWebNfc, setHasWebNfc] = useState(false);

  useEffect(() => {
    setHasWebNfc('NDEFReader' in window);
    loadCards();
  }, [search, statusFilter]);

  async function loadCards() {
    try {
      setLoading(true);
      const params: Record<string, string> = {};
      if (search.trim()) params.search = search.trim();
      if (statusFilter !== 'all') params.status = statusFilter;
      const res = await api.get('/cards', { params });
      setCards(res.data.cards || []);
    } catch (err) {
      console.error('Failed to load cards:', err);
    } finally {
      setLoading(false);
    }
  }

  async function startWebNfcScan() {
    if (!('NDEFReader' in window)) {
      alert('Web NFC is not supported on this browser. Please use Chrome on Android or enter NFC UID manually.');
      return;
    }
    setNfcScanning(true);
    setEnrollError(null);
    try {
      const ndef = new (window as any).NDEFReader();
      await ndef.scan();
      ndef.addEventListener('reading', (event: any) => {
        if (event.serialNumber) {
          const raw = String(event.serialNumber);
          const clean = raw.replace(/[:\s-]/g, '').toUpperCase();
          setNfcUidInput(clean);
          setNfcScanning(false);
        }
      });
      ndef.addEventListener('readingerror', () => {
        setEnrollError('Cannot read card. Try holding the tag steady against the phone.');
        setNfcScanning(false);
      });
    } catch (err: any) {
      setEnrollError(err.message || 'NFC scan permission denied or unavailable.');
      setNfcScanning(false);
    }
  }

  async function handleEnroll(e: React.FormEvent) {
    e.preventDefault();
    setEnrollError(null);
    if (!nfcUidInput.trim()) {
      setEnrollError('NFC UID is required.');
      return;
    }
    try {
      await api.post('/cards', {
        nfc_uid: nfcUidInput.trim(),
        label: labelInput.trim() || null,
        notes: notesInput.trim() || null,
      });
      setShowEnrollModal(false);
      setNfcUidInput('');
      setLabelInput('');
      setNotesInput('');
      loadCards();
    } catch (err: any) {
      setEnrollError(err.response?.data?.error || 'Failed to enroll card.');
    }
  }

  async function handleStatusChange(cardUuid: string, newStatus: string) {
    try {
      await api.patch(`/cards/${cardUuid}`, { status: newStatus });
      loadCards();
    } catch (err) {
      alert('Failed to update card status');
    }
  }

  function getStatusBadge(status: string) {
    switch (status) {
      case 'active':
        return <span style={{ color: 'var(--success)', display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '12px', fontWeight: 700 }}><CheckCircle2 size={14} /> Active</span>;
      case 'suspended':
        return <span style={{ color: 'var(--warning)', display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '12px', fontWeight: 700 }}><AlertTriangle size={14} /> Suspended</span>;
      case 'lost':
        return <span style={{ color: 'var(--danger)', display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '12px', fontWeight: 700 }}><XCircle size={14} /> Lost</span>;
      case 'retired':
      default:
        return <span style={{ color: 'var(--text-secondary)', display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '12px', fontWeight: 600 }}>Retired</span>;
    }
  }

  const receiptUrl = (cardUuid: string) => `${window.location.origin}/r/${cardUuid}`;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '24px', height: '100%', position: 'relative' }}>
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>Card Registry</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>Bind physical RFID/NFC cards with QR receipt tokens for passenger circulation</div>
        </div>
        <button
          onClick={() => { setShowEnrollModal(true); setEnrollError(null); }}
          className="btn-primary"
          style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '12px 20px', borderRadius: '8px' }}
        >
          <Plus size={18} />
          <span>Enroll New Card</span>
        </button>
      </header>

      {/* Filter and Search Bar */}
      <div className="glass-panel" style={{ padding: '16px 24px', display: 'flex', gap: '16px', alignItems: 'center', flexWrap: 'wrap' }}>
        <div style={{ flex: 1, minWidth: '240px', position: 'relative' }}>
          <Search size={18} style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-secondary)' }} />
          <input
            type="text"
            placeholder="Search by NFC UID, Label, or Card UUID..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            style={{ width: '100%', padding: '10px 10px 10px 40px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '8px', color: 'var(--text-primary)' }}
          />
        </div>
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
          <span style={{ fontSize: '14px', color: 'var(--text-secondary)' }}>Status:</span>
          {['all', 'active', 'suspended', 'lost', 'retired'].map((s) => (
            <button
              key={s}
              onClick={() => setStatusFilter(s)}
              style={{
                padding: '6px 14px',
                borderRadius: '6px',
                border: '1px solid var(--border)',
                background: statusFilter === s ? 'var(--accent)' : 'transparent',
                color: statusFilter === s ? '#fff' : 'var(--text-secondary)',
                cursor: 'pointer',
                fontSize: '12px',
                textTransform: 'capitalize',
                fontWeight: 600,
              }}
            >
              {s}
            </button>
          ))}
        </div>
      </div>

      {/* Cards Table */}
      <div className="glass-panel" style={{ flex: 1, overflow: 'auto', padding: '0' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid var(--border)', background: 'rgba(255,255,255,0.02)' }}>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px' }}>NFC UID</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px' }}>Label / Identifier</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px' }}>Static QR Link</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px' }}>Status</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px' }}>Enrolled Date</th>
              <th style={{ padding: '16px 24px', color: 'var(--text-secondary)', fontSize: '13px', textAlign: 'center' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={6} style={{ padding: '40px', textAlign: 'center', color: 'var(--text-secondary)' }}>Loading cards...</td></tr>
            ) : cards.length === 0 ? (
              <tr><td colSpan={6} style={{ padding: '40px', textAlign: 'center', color: 'var(--text-secondary)' }}>No enrolled cards found.</td></tr>
            ) : (
              cards.map((c) => (
                <tr key={c.card_uuid} style={{ borderBottom: '1px solid var(--border)' }}>
                  <td style={{ padding: '16px 24px', fontFamily: 'monospace', fontWeight: 700, color: 'var(--accent)' }}>{c.nfc_uid}</td>
                  <td style={{ padding: '16px 24px', fontWeight: 600 }}>{c.label || '—'}</td>
                  <td style={{ padding: '16px 24px', fontSize: '12px', fontFamily: 'monospace', color: 'var(--text-secondary)' }}>
                    /r/{c.card_uuid.slice(0, 8)}...
                  </td>
                  <td style={{ padding: '16px 24px' }}>{getStatusBadge(c.status)}</td>
                  <td style={{ padding: '16px 24px', fontSize: '13px', color: 'var(--text-secondary)' }}>{new Date(c.enrolled_at).toLocaleDateString()}</td>
                  <td style={{ padding: '16px 24px', textAlign: 'center' }}>
                    <div style={{ display: 'inline-flex', gap: '8px' }}>
                      <button
                        onClick={() => setSelectedCardForQr(c)}
                        title="Print QR Sticker"
                        style={{ padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--bg-dark)', color: 'var(--accent)', cursor: 'pointer' }}
                      >
                        <QrCode size={16} />
                      </button>
                      {c.status === 'active' && (
                        <button
                          onClick={() => handleStatusChange(c.card_uuid, 'suspended')}
                          style={{ padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--bg-dark)', color: 'var(--warning)', cursor: 'pointer', fontSize: '11px' }}
                        >
                          Suspend
                        </button>
                      )}
                      {c.status === 'suspended' && (
                        <button
                          onClick={() => handleStatusChange(c.card_uuid, 'active')}
                          style={{ padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--bg-dark)', color: 'var(--success)', cursor: 'pointer', fontSize: '11px' }}
                        >
                          Activate
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Modal: Enroll Card with Web NFC */}
      {showEnrollModal && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000 }}>
          <div className="glass-panel" style={{ width: '100%', maxWidth: '480px', padding: '28px' }}>
            <h3 style={{ margin: '0 0 16px 0', fontSize: '20px', borderBottom: '1px solid var(--border)', paddingBottom: '12px' }}>
              Enroll Physical NFC Card
            </h3>

            {hasWebNfc && (
              <div style={{ marginBottom: '20px', padding: '16px', background: 'rgba(0, 210, 255, 0.08)', border: '1px solid var(--accent)', borderRadius: '8px', textAlign: 'center' }}>
                <p style={{ margin: '0 0 10px 0', fontSize: '13px', color: 'var(--text-secondary)' }}>
                  Web NFC is available on this mobile browser. Tap to read card hardware UID directly.
                </p>
                <button
                  type="button"
                  onClick={startWebNfcScan}
                  disabled={nfcScanning}
                  className="btn-primary"
                  style={{ display: 'inline-flex', alignItems: 'center', gap: '8px', padding: '10px 18px', borderRadius: '6px' }}
                >
                  <Radio size={16} />
                  <span>{nfcScanning ? 'Listening... Tap Card Now' : 'Tap Card to Scan'}</span>
                </button>
              </div>
            )}

            <form onSubmit={handleEnroll} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <label style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '14px' }}>
                NFC Hardware UID *
                <input
                  type="text"
                  placeholder="e.g. 6FF1AD39 or 04:A1:B2:C3"
                  value={nfcUidInput}
                  onChange={(e) => setNfcUidInput(e.target.value)}
                  style={{ padding: '10px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)', fontFamily: 'monospace' }}
                  required
                />
              </label>

              <label style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '14px' }}>
                Card Label / Marker Name
                <input
                  type="text"
                  placeholder="e.g. Seat #12, Card A-04"
                  value={labelInput}
                  onChange={(e) => setLabelInput(e.target.value)}
                  style={{ padding: '10px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)' }}
                />
              </label>

              <label style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontSize: '14px' }}>
                Notes (Optional)
                <input
                  type="text"
                  placeholder="Batch or hardware notes"
                  value={notesInput}
                  onChange={(e) => setNotesInput(e.target.value)}
                  style={{ padding: '10px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)' }}
                />
              </label>

              {enrollError && (
                <div role="alert" style={{ color: 'var(--danger)', fontSize: '13px' }}>{enrollError}</div>
              )}

              <div style={{ display: 'flex', gap: '12px', marginTop: '12px' }}>
                <button
                  type="button"
                  onClick={() => setShowEnrollModal(false)}
                  style={{ flex: 1, padding: '10px', background: 'transparent', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-secondary)', cursor: 'pointer' }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  style={{ flex: 1, padding: '10px', borderRadius: '6px' }}
                >
                  Save & Bind QR
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal: QR Code & Printable Sticker Sheet */}
      {selectedCardForQr && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000 }}>
          <div className="glass-panel" style={{ width: '100%', maxWidth: '420px', padding: '28px', textAlign: 'center' }}>
            <h3 style={{ margin: '0 0 8px 0', fontSize: '18px' }}>
              Printable Card QR Sticker
            </h3>
            <div style={{ color: 'var(--text-secondary)', fontSize: '13px', marginBottom: '20px' }}>
              Affix this sticker to physical reusable passenger card
            </div>

            {/* Printable Card Simulation */}
            <div style={{
              background: '#ffffff',
              color: '#111418',
              borderRadius: '12px',
              padding: '24px',
              boxShadow: '0 8px 24px rgba(0,0,0,0.2)',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: '12px',
              margin: '0 auto 20px auto',
              width: '260px',
            }}>
              <div style={{ fontSize: '18px', fontWeight: 800, letterSpacing: '1px', color: '#0B3D91' }}>
                TOMS PASS
              </div>
              <QRCodeSVG
                value={receiptUrl(selectedCardForQr.card_uuid)}
                size={160}
                level="M"
                includeMargin={false}
              />
              <div style={{ fontFamily: 'monospace', fontSize: '12px', fontWeight: 700 }}>
                {selectedCardForQr.label || `CARD #${selectedCardForQr.nfc_uid.slice(0, 8)}`}
              </div>
              <div style={{ fontSize: '10px', color: '#555', textAlign: 'center' }}>
                Scan with phone camera for official e-Receipt
              </div>
            </div>

            <div style={{ fontSize: '12px', fontFamily: 'monospace', color: 'var(--text-secondary)', marginBottom: '20px' }}>
              Target: {receiptUrl(selectedCardForQr.card_uuid)}
            </div>

            <div style={{ display: 'flex', gap: '12px' }}>
              <button
                type="button"
                onClick={() => setSelectedCardForQr(null)}
                style={{ flex: 1, padding: '10px', background: 'transparent', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-secondary)', cursor: 'pointer' }}
              >
                Close
              </button>
              <button
                type="button"
                onClick={() => window.print()}
                className="btn-primary"
                style={{ flex: 1, display: 'inline-flex', justifyContent: 'center', alignItems: 'center', gap: '6px', padding: '10px', borderRadius: '6px' }}
              >
                <Printer size={16} />
                <span>Print Sticker</span>
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
