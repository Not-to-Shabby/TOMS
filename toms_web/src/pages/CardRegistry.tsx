import { useState, useEffect, useMemo } from 'react';
import { Plus, Search, QrCode, Printer, Radio, CheckCircle2, AlertTriangle, XCircle, Bus, Layers } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import { api } from '../lib/api';

interface CardItem {
  card_uuid: string;
  nfc_uid: string;
  label: string | null;
  status: 'active' | 'suspended' | 'retired' | 'lost';
  enrolled_at: string;
  notes: string | null;
  vehicle_id: string | null;
  vehicle_plate: string | null;
}

interface VehicleItem {
  id: string;
  plate_number: string;
  max_capacity: number;
  status: string;
  assigned_route_name?: string | null;
}

export default function CardRegistry() {
  const [cards, setCards] = useState<CardItem[]>([]);
  const [vehicles, setVehicles] = useState<VehicleItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('all');
  const [vehicleFilter, setVehicleFilter] = useState<string>('all');
  const [showEnrollModal, setShowEnrollModal] = useState(false);
  const [selectedCardForQr, setSelectedCardForQr] = useState<CardItem | null>(null);

  // Enrollment Form State
  const [nfcUidInput, setNfcUidInput] = useState('');
  const [labelInput, setLabelInput] = useState('');
  const [notesInput, setNotesInput] = useState('');
  const [vehicleIdInput, setVehicleIdInput] = useState<string>('');
  const [enrollError, setEnrollError] = useState<string | null>(null);
  const [nfcScanning, setNfcScanning] = useState(false);
  const [hasWebNfc, setHasWebNfc] = useState(false);

  useEffect(() => {
    setHasWebNfc('NDEFReader' in window);
    loadVehicles();
  }, []);

  useEffect(() => {
    loadCards();
  }, [search, statusFilter, vehicleFilter]);

  async function loadVehicles() {
    try {
      const res = await api.get('/vehicles');
      setVehicles(res.data || []);
    } catch (err) {
      console.error('Failed to load vehicles:', err);
    }
  }

  async function loadCards() {
    try {
      setLoading(true);
      const params: Record<string, string> = { limit: '200' };
      if (search.trim()) params.search = search.trim();
      if (statusFilter !== 'all') params.status = statusFilter;
      if (vehicleFilter !== 'all') params.vehicle_id = vehicleFilter;
      const res = await api.get('/cards', { params });
      setCards(res.data.cards || []);
    } catch (err) {
      console.error('Failed to load cards:', err);
    } finally {
      setLoading(false);
    }
  }

  function openEnrollForVehicle(presetVehicleId?: string | null) {
    setEnrollError(null);
    setNfcUidInput('');
    setLabelInput('');
    setNotesInput('');
    if (presetVehicleId !== undefined) {
      setVehicleIdInput(presetVehicleId ?? '');
    } else if (vehicleFilter !== 'all' && vehicleFilter !== 'unassigned') {
      setVehicleIdInput(vehicleFilter);
    } else {
      setVehicleIdInput(vehicles[0]?.id ?? '');
    }
    setShowEnrollModal(true);
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
        vehicle_id: vehicleIdInput || null,
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
    } catch {
      alert('Failed to update card status');
    }
  }

  async function handleVehicleReassign(cardUuid: string, newVehicleId: string) {
    try {
      await api.patch(`/cards/${cardUuid}`, { vehicle_id: newVehicleId || null });
      loadCards();
    } catch {
      alert('Failed to update card vehicle assignment');
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

  // Group cards by vehicle so each vehicle's deck is clearly organized
  const groupedSections = useMemo(() => {
    const byVehicle = new Map<string | null, CardItem[]>();
    for (const c of cards) {
      const key = c.vehicle_id ?? null;
      const list = byVehicle.get(key) ?? [];
      list.push(c);
      byVehicle.set(key, list);
    }

    const sections: {
      vehicleId: string | null;
      title: string;
      subtitle: string;
      capacity: number | null;
      items: CardItem[];
    }[] = [];

    // 1. Add sections for known vehicles
    for (const v of vehicles) {
      if (vehicleFilter !== 'all' && vehicleFilter !== v.id) continue;
      const items = byVehicle.get(v.id) ?? [];
      if (items.length === 0 && (search.trim() !== '' || statusFilter !== 'all')) continue;
      sections.push({
        vehicleId: v.id,
        title: `${v.id} · ${v.plate_number}`,
        subtitle: v.assigned_route_name ? `Route: ${v.assigned_route_name}` : 'No route assigned',
        capacity: v.max_capacity,
        items,
      });
      byVehicle.delete(v.id);
    }

    // 2. Any other vehicle IDs present on cards
    for (const [vid, items] of byVehicle.entries()) {
      if (vid === null) continue;
      sections.push({
        vehicleId: vid,
        title: vid,
        subtitle: 'Registered Vehicle',
        capacity: null,
        items,
      });
    }

    // 3. Unassigned / Spare Pool section
    if (vehicleFilter === 'all' || vehicleFilter === 'unassigned') {
      const unassignedItems = byVehicle.get(null) ?? [];
      if (unassignedItems.length > 0 || vehicleFilter === 'unassigned' || sections.length === 0) {
        sections.push({
          vehicleId: null,
          title: 'Unassigned / Spare Card Pool',
          subtitle: 'Available as shared spare cards across any vehicle',
          capacity: null,
          items: unassignedItems,
        });
      }
    }

    return sections;
  }, [cards, vehicles, vehicleFilter, search, statusFilter]);

  const receiptUrl = (cardUuid: string) => `${window.location.origin}/r/${cardUuid}`;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '20px', height: '100%', position: 'relative', overflow: 'hidden' }}>
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>Card Registry by Vehicle</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>
            Organize reusable RFID/NFC passenger card decks per vehicle and print static QR receipt stickers
          </div>
        </div>
        <button
          onClick={() => openEnrollForVehicle()}
          className="btn-primary"
          style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '12px 20px', borderRadius: '8px' }}
        >
          <Plus size={18} />
          <span>Enroll New Card</span>
        </button>
      </header>

      {/* Filter & Vehicle Group Selector Bar */}
      <div className="glass-panel" style={{ padding: '16px 24px', display: 'flex', flexDirection: 'column', gap: '14px' }}>
        <div style={{ display: 'flex', gap: '16px', alignItems: 'center', flexWrap: 'wrap' }}>
          <div style={{ flex: 1, minWidth: '240px', position: 'relative' }}>
            <Search size={18} style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-secondary)' }} />
            <input
              type="text"
              placeholder="Search by NFC UID, Label, Vehicle, or Card UUID..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              style={{ width: '100%', padding: '10px 10px 10px 40px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '8px', color: 'var(--text-primary)' }}
            />
          </div>

          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <span style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>Status:</span>
            {['all', 'active', 'suspended', 'lost', 'retired'].map((s) => (
              <button
                key={s}
                onClick={() => setStatusFilter(s)}
                style={{
                  padding: '6px 12px',
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

        {/* Vehicle Group Pills */}
        <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap', borderTop: '1px solid var(--border)', paddingTop: '12px' }}>
          <span style={{ fontSize: '13px', color: 'var(--text-secondary)', display: 'inline-flex', alignItems: 'center', gap: '6px', marginRight: '4px' }}>
            <Bus size={15} /> Vehicle Deck:
          </span>
          <button
            onClick={() => setVehicleFilter('all')}
            style={{
              padding: '6px 14px',
              borderRadius: '6px',
              border: '1px solid var(--border)',
              background: vehicleFilter === 'all' ? 'var(--accent)' : 'transparent',
              color: vehicleFilter === 'all' ? '#fff' : 'var(--text-secondary)',
              cursor: 'pointer',
              fontSize: '12px',
              fontWeight: 600,
            }}
          >
            All Vehicles ({cards.length})
          </button>
          {vehicles.map((v) => {
            const count = cards.filter((c) => c.vehicle_id === v.id).length;
            return (
              <button
                key={v.id}
                onClick={() => setVehicleFilter(v.id)}
                style={{
                  padding: '6px 14px',
                  borderRadius: '6px',
                  border: '1px solid var(--border)',
                  background: vehicleFilter === v.id ? 'var(--accent)' : 'transparent',
                  color: vehicleFilter === v.id ? '#fff' : 'var(--text-secondary)',
                  cursor: 'pointer',
                  fontSize: '12px',
                  fontWeight: 600,
                }}
              >
                {v.id} ({v.plate_number}) · {count}
              </button>
            );
          })}
          <button
            onClick={() => setVehicleFilter('unassigned')}
            style={{
              padding: '6px 14px',
              borderRadius: '6px',
              border: '1px solid var(--border)',
              background: vehicleFilter === 'unassigned' ? 'var(--accent)' : 'transparent',
              color: vehicleFilter === 'unassigned' ? '#fff' : 'var(--text-secondary)',
              cursor: 'pointer',
              fontSize: '12px',
              fontWeight: 600,
            }}
          >
            Spare Pool ({cards.filter((c) => !c.vehicle_id).length})
          </button>
        </div>
      </div>

      {/* Grouped Vehicle Card Decks */}
      <div style={{ flex: 1, overflow: 'auto', display: 'flex', flexDirection: 'column', gap: '20px', paddingBottom: '16px' }}>
        {loading ? (
          <div className="glass-panel" style={{ padding: '40px', textAlign: 'center', color: 'var(--text-secondary)' }}>
            Loading vehicle card decks...
          </div>
        ) : groupedSections.length === 0 ? (
          <div className="glass-panel" style={{ padding: '40px', textAlign: 'center', color: 'var(--text-secondary)' }}>
            No cards found matching your filter.
          </div>
        ) : (
          groupedSections.map((section) => {
            const activeCount = section.items.filter((i) => i.status === 'active').length;
            return (
              <div key={section.vehicleId ?? 'unassigned'} className="glass-panel" style={{ padding: '0', overflow: 'hidden' }}>
                {/* Vehicle Group Header */}
                <div style={{
                  padding: '16px 24px',
                  background: 'rgba(0, 210, 255, 0.06)',
                  borderBottom: '1px solid var(--border)',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  flexWrap: 'wrap',
                  gap: '12px',
                }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                    <div style={{
                      width: '38px',
                      height: '38px',
                      borderRadius: '8px',
                      background: section.vehicleId ? 'var(--accent-glow)' : 'rgba(255,255,255,0.06)',
                      display: 'grid',
                      placeItems: 'center',
                      color: section.vehicleId ? 'var(--accent)' : 'var(--text-secondary)',
                    }}>
                      {section.vehicleId ? <Bus size={20} /> : <Layers size={20} />}
                    </div>
                    <div>
                      <div style={{ fontSize: '17px', fontWeight: 700, color: 'var(--text-primary)' }}>
                        {section.title}
                      </div>
                      <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                        {section.subtitle}
                      </div>
                    </div>
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
                    <div style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
                      Active Cards: <strong style={{ color: 'var(--text-primary)' }}>{activeCount}</strong>
                      {section.capacity ? ` / ${section.capacity} seats` : ` (${section.items.length} total)`}
                    </div>
                    <button
                      onClick={() => openEnrollForVehicle(section.vehicleId)}
                      style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: '6px',
                        padding: '8px 14px',
                        borderRadius: '6px',
                        border: '1px solid var(--accent)',
                        background: 'transparent',
                        color: 'var(--accent)',
                        cursor: 'pointer',
                        fontSize: '12px',
                        fontWeight: 600,
                      }}
                    >
                      <Plus size={14} />
                      <span>Add Card to {section.vehicleId || 'Spare Pool'}</span>
                    </button>
                  </div>
                </div>

                {/* Cards Table for this Vehicle */}
                {section.items.length === 0 ? (
                  <div style={{ padding: '28px 24px', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '13px' }}>
                    No cards assigned to {section.vehicleId || 'the spare pool'} yet. Click "Add Card" above to enroll one.
                  </div>
                ) : (
                  <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
                    <thead>
                      <tr style={{ borderBottom: '1px solid var(--border)', background: 'rgba(255,255,255,0.02)' }}>
                        <th style={{ padding: '12px 24px', color: 'var(--text-secondary)', fontSize: '12px' }}>NFC UID</th>
                        <th style={{ padding: '12px 24px', color: 'var(--text-secondary)', fontSize: '12px' }}>Card Label</th>
                        <th style={{ padding: '12px 24px', color: 'var(--text-secondary)', fontSize: '12px' }}>Assigned Vehicle</th>
                        <th style={{ padding: '12px 24px', color: 'var(--text-secondary)', fontSize: '12px' }}>Static QR Link</th>
                        <th style={{ padding: '12px 24px', color: 'var(--text-secondary)', fontSize: '12px' }}>Status</th>
                        <th style={{ padding: '12px 24px', color: 'var(--text-secondary)', fontSize: '12px', textAlign: 'center' }}>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {section.items.map((c) => (
                        <tr key={c.card_uuid} style={{ borderBottom: '1px solid var(--border)' }}>
                          <td style={{ padding: '14px 24px', fontFamily: 'monospace', fontWeight: 700, color: 'var(--accent)' }}>
                            {c.nfc_uid}
                          </td>
                          <td style={{ padding: '14px 24px', fontWeight: 600 }}>
                            {c.label || '—'}
                            {c.notes && <div style={{ fontSize: '11px', color: 'var(--text-secondary)', fontWeight: 400 }}>{c.notes}</div>}
                          </td>
                          <td style={{ padding: '14px 24px' }}>
                            <select
                              value={c.vehicle_id ?? ''}
                              onChange={(e) => handleVehicleReassign(c.card_uuid, e.target.value)}
                              style={{
                                padding: '6px 10px',
                                background: 'var(--bg-dark)',
                                border: '1px solid var(--border)',
                                borderRadius: '6px',
                                color: 'var(--text-primary)',
                                fontSize: '12px',
                              }}
                            >
                              <option value="">Spare Pool (Unassigned)</option>
                              {vehicles.map((v) => (
                                <option key={v.id} value={v.id}>
                                  {v.id} ({v.plate_number})
                                </option>
                              ))}
                            </select>
                          </td>
                          <td style={{ padding: '14px 24px', fontSize: '12px', fontFamily: 'monospace', color: 'var(--text-secondary)' }}>
                            /r/{c.card_uuid.slice(0, 8)}...
                          </td>
                          <td style={{ padding: '14px 24px' }}>{getStatusBadge(c.status)}</td>
                          <td style={{ padding: '14px 24px', textAlign: 'center' }}>
                            <div style={{ display: 'inline-flex', gap: '8px' }}>
                              <button
                                onClick={() => setSelectedCardForQr(c)}
                                title="Print QR Sticker"
                                style={{ padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--bg-dark)', color: 'var(--accent)', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '11px', fontWeight: 600 }}
                              >
                                <QrCode size={15} />
                                <span>QR</span>
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
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            );
          })
        )}
      </div>

      {/* Modal: Enroll Card with Web NFC & Vehicle Selection */}
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
                Assign to Vehicle Deck
                <select
                  value={vehicleIdInput}
                  onChange={(e) => setVehicleIdInput(e.target.value)}
                  style={{ padding: '10px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text-primary)' }}
                >
                  <option value="">Unassigned / Spare Card Pool</option>
                  {vehicles.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.id} ({v.plate_number})
                    </option>
                  ))}
                </select>
              </label>

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
                Card Label / Seat Marker
                <input
                  type="text"
                  placeholder="e.g. Card #01, Seat #12"
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
              gap: '10px',
              margin: '0 auto 20px auto',
              width: '260px',
            }}>
              <div style={{ fontSize: '18px', fontWeight: 800, letterSpacing: '1px', color: '#0B3D91' }}>
                TOMS PASS
              </div>
              {selectedCardForQr.vehicle_id && (
                <div style={{
                  fontSize: '11px',
                  fontWeight: 700,
                  background: '#e0f2fe',
                  color: '#0369a1',
                  padding: '3px 10px',
                  borderRadius: '12px',
                }}>
                  {selectedCardForQr.vehicle_id}{selectedCardForQr.vehicle_plate ? ` · ${selectedCardForQr.vehicle_plate}` : ''}
                </div>
              )}
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
