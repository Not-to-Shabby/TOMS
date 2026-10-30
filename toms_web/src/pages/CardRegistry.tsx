import { useState, useEffect, useMemo, useRef } from 'react';
import { Plus, Search, QrCode, Printer, Radio, CheckCircle2, AlertTriangle, XCircle, Bus, Layers, Wifi, ExternalLink } from 'lucide-react';
import { Link } from 'react-router-dom';
import { QRCodeSVG } from 'qrcode.react';
import { api } from '../lib/api';
import LoadError from '../components/LoadError';
import PairPrintedCardModal from '../components/PairPrintedCardModal';
import CardActionDialog from '../components/CardActionDialog';
import { changeStatus } from '../lib/cardRequests';
import CardEditDialog from '../components/CardEditDialog';
import CardHistoryDrawer from '../components/CardHistoryDrawer';
import Modal from '../components/Modal';
import PairPanel from '../components/PairPanel';
import CardRowMenu from '../components/CardRowMenu';
import { confirmationFor, type CardStatus, type Confirmation, type RowAction } from '../lib/cardLifecycle';
import { loadErrorMessage } from '../lib/loadError';

interface CardItem {
  card_uuid: string;
  nfc_uid: string | null;
  serial: string | null;
  label: string | null;
  status: 'unpaired' | 'active' | 'suspended' | 'retired' | 'lost';
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
  const [loadError, setLoadError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('all');
  const [vehicleFilter, setVehicleFilter] = useState<string>('all');
  const [showEnrollModal, setShowEnrollModal] = useState(false);
  const [showPairModal, setShowPairModal] = useState(false);
  const [editing, setEditing] = useState<CardItem | null>(null);
  const [historyOf, setHistoryOf] = useState<CardItem | null>(null);
  const [replacingChip, setReplacingChip] = useState<CardItem | null>(null);
  const [pending, setPending] = useState<{
    confirmation: Confirmation;
    run: (reason: string, confirm: boolean) => Promise<unknown>;
  } | null>(null);
  const [rowError, setRowError] = useState<string | null>(null);
  const [selectedCardForQr, setSelectedCardForQr] = useState<CardItem | null>(null);

  // Enrollment Form State
  const [nfcUidInput, setNfcUidInput] = useState('');
  const [labelInput, setLabelInput] = useState('');
  const [notesInput, setNotesInput] = useState('');
  const [vehicleIdInput, setVehicleIdInput] = useState<string>('');
  const [writeNdefOnEnroll, setWriteNdefOnEnroll] = useState(true);
  const [enrollError, setEnrollError] = useState<string | null>(null);
  const [nfcScanning, setNfcScanning] = useState(false);
  const [nfcStatusText, setNfcStatusText] = useState<string | null>(null);
  const [hasWebNfc, setHasWebNfc] = useState(false);

  // NDEF writing on QR modal
  const [writingTag, setWritingTag] = useState(false);
  const [writeSuccess, setWriteSuccess] = useState<string | null>(null);

  const scanAbortControllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    setHasWebNfc('NDEFReader' in window);
    loadVehicles();
    return () => {
      scanAbortControllerRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    loadCards();
  }, [search, statusFilter, vehicleFilter]);

  async function loadVehicles() {
    try {
      const res = await api.get('/vehicles');
      setVehicles(res.data || []);
    } catch (err) {
      setLoadError(loadErrorMessage(err, 'the vehicle list'));
    }
  }

  async function loadCards() {
    try {
      setLoading(true);
      setLoadError(null);
      const params: Record<string, string> = { limit: '200' };
      if (search.trim()) params.search = search.trim();
      if (statusFilter !== 'all') params.status = statusFilter;
      if (vehicleFilter !== 'all') params.vehicle_id = vehicleFilter;
      const all: CardItem[] = [];
      let page = 1;
      let totalPages = 1;
      do {
        const res = await api.get('/cards', { params: { ...params, page: String(page) } });
        all.push(...(res.data.cards || []));
        totalPages = res.data.metadata?.totalPages ?? 1;
        page += 1;
      } while (page <= totalPages && page <= 50);
      setCards(all);
    } catch (err) {
      setLoadError(loadErrorMessage(err, 'the card list'));
    } finally {
      setLoading(false);
    }
  }

  function openEnrollForVehicle(presetVehicleId?: string | null) {
    scanAbortControllerRef.current?.abort();
    setEnrollError(null);
    setNfcStatusText(null);
    setNfcScanning(false);
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

  function closeEnrollModal() {
    scanAbortControllerRef.current?.abort();
    setNfcScanning(false);
    setShowEnrollModal(false);
  }

  // Web NFC: Scan hardware serial number using window.NDEFReader
  async function startWebNfcScan() {
    if (!('NDEFReader' in window)) {
      alert('Web NFC is not available on this browser. Use Chrome on Android with NFC turned on in Android settings.');
      return;
    }

    scanAbortControllerRef.current?.abort();
    const controller = new AbortController();
    scanAbortControllerRef.current = controller;

    setNfcScanning(true);
    setEnrollError(null);
    setNfcStatusText('NFC Scanner Ready. Hold card against the back of your phone...');

    try {
      const ndef = new (window as any).NDEFReader();
      await ndef.scan({ signal: controller.signal });

      ndef.addEventListener('reading', (event: any) => {
        if (event.serialNumber) {
          const raw = String(event.serialNumber);
          const clean = raw.replace(/[:\s-]/g, '').toUpperCase();
          setNfcUidInput(clean);
          setNfcScanning(false);
          setNfcStatusText(`✓ Scanned Serial: ${clean}`);

          // Provide haptic feedback if available
          if ('vibrate' in navigator) navigator.vibrate(60);
        }
      });

      ndef.addEventListener('readingerror', () => {
        setEnrollError('Cannot read data from the NFC tag. Try holding it steady against phone.');
        setNfcScanning(false);
      });
    } catch (err: any) {
      if (err.name !== 'AbortError') {
        setEnrollError(err.message || 'NFC scan failed or permission was denied.');
      }
      setNfcScanning(false);
    }
  }

  const receiptUrl = (cardUuid: string) => `${window.location.origin}/r/${cardUuid}`;

  async function handleEnroll(e: React.FormEvent) {
    e.preventDefault();
    setEnrollError(null);
    if (!nfcUidInput.trim()) {
      setEnrollError('NFC UID is required.');
      return;
    }

    try {
      const res = await api.post('/cards', {
        nfc_uid: nfcUidInput.trim(),
        label: labelInput.trim() || null,
        notes: notesInput.trim() || null,
        vehicle_id: vehicleIdInput || null,
      });

      const newCard: CardItem = res.data;

      // If Web NFC writing is enabled and supported, offer to write the static URL to tag memory
      if (writeNdefOnEnroll && 'NDEFReader' in window) {
        try {
          const ndef = new (window as any).NDEFReader();
          await ndef.write({
            records: [{ recordType: 'url', data: receiptUrl(newCard.card_uuid) }],
          });
          if ('vibrate' in navigator) navigator.vibrate([60, 50, 60]);
        } catch (writeErr: any) {
          console.warn('Could not write NDEF record to tag:', writeErr);
        }
      }

      closeEnrollModal();
      loadCards();
    } catch (err: any) {
      setEnrollError(err.response?.data?.error || 'Failed to enroll card.');
    }
  }

  // Write static QR receipt URL to card's NDEF memory from the sticker modal
  async function writeNdefUrlToTag(cardUuid: string) {
    if (!('NDEFReader' in window)) {
      alert('Web NFC writing requires Chrome on Android.');
      return;
    }
    setWritingTag(true);
    setWriteSuccess(null);
    try {
      const ndef = new (window as any).NDEFReader();
      const targetUrl = receiptUrl(cardUuid);
      await ndef.write({
        records: [{ recordType: 'url', data: targetUrl }],
      });
      setWriteSuccess(`✓ Successfully wrote ${targetUrl} to card!`);
      if ('vibrate' in navigator) navigator.vibrate(80);
    } catch (err: any) {
      alert(err.message || 'Failed to write URL to tag. Ensure NFC is enabled and card is held steady.');
    } finally {
      setWritingTag(false);
    }
  }

  /** Runs a row action. Risky ones open a confirmation first; harmless ones go straight through. */
  function handleRowAction(card: CardItem, action: RowAction, to?: CardStatus) {
    setRowError(null);
    const name = card.serial ?? card.label ?? card.card_uuid.slice(0, 8);
    if (action === 'edit') return void setEditing(card);
    if (action === 'history') return void setHistoryOf(card);
    if (action === 'replace') return void setReplacingChip(card);

    const confirmation = confirmationFor(action, name);
    if (action === 'unpair' && confirmation) {
      return void setPending({
        confirmation,
        run: (reason, confirm) => api.post(`/cards/${card.card_uuid}/unpair`, { reason: reason || undefined, confirm: confirm || undefined }),
      });
    }
    if (!to) return;
    if (confirmation) {
      return void setPending({ confirmation, run: (reason, confirm) => changeStatus(card.card_uuid, to, reason, confirm) });
    }
    // Activate, found, restore: nothing to confirm.
    changeStatus(card.card_uuid, to, '', false)
      .then(loadCards)
      .catch((err) => setRowError(err?.response?.data?.error ?? 'Could not change the card.'));
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
      case 'unpaired':
        return <span style={{ color: 'var(--accent)', display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '12px', fontWeight: 700 }}><Radio size={14} /> Waiting for tap</span>;
      case 'suspended':
        return <span style={{ color: 'var(--warning)', display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '12px', fontWeight: 700 }}><AlertTriangle size={14} /> Suspended</span>;
      case 'lost':
        return <span style={{ color: 'var(--danger)', display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '12px', fontWeight: 700 }}><XCircle size={14} /> Lost</span>;
      case 'retired':
      default:
        return <span style={{ color: 'var(--text-secondary)', display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '12px', fontWeight: 600 }}>Retired</span>;
    }
  }

  const unpairedCards = useMemo(() => cards.filter((c) => c.status === 'unpaired'), [cards]);
  const unpairedCount = unpairedCards.length;

  // Group cards by vehicle
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

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '20px', height: '100%', position: 'relative', overflow: 'hidden' }}>
      <header className="glass-panel" style={{ padding: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '16px' }}>
        <div style={{ flex: '1 1 320px', minWidth: 0 }}>
          <h2 style={{ fontSize: '28px', fontWeight: 700, margin: 0, color: 'var(--text-primary)' }}>Card Registry by Vehicle</h2>
          <div style={{ color: 'var(--text-secondary)', marginTop: '4px' }}>
            Bind physical RFID/NFC cards using Web NFC, manage vehicle card decks, and generate static QR receipt stickers
          </div>
        </div>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', flexShrink: 0 }}>
        <Link
          to="/cards/designer"
          style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '12px 20px', borderRadius: '8px', border: '1px solid var(--accent)', color: 'var(--accent)', textDecoration: 'none', whiteSpace: 'nowrap' }}
        >
          <Printer size={18} />
          <span>Print-ready cards</span>
        </Link>
        <button
          onClick={() => setShowPairModal(true)}
          style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '12px 20px', borderRadius: '8px', whiteSpace: 'nowrap', border: '1px solid var(--accent)', background: 'rgba(0, 210, 255, 0.12)', color: 'var(--accent)', cursor: 'pointer', fontWeight: 700 }}
        >
          <QrCode size={18} />
          <span>Pair printed card{unpairedCount > 0 ? ` (${unpairedCount} waiting)` : ''}</span>
        </button>
        <button
          onClick={() => openEnrollForVehicle()}
          className="btn-primary"
          style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '12px 20px', borderRadius: '8px', whiteSpace: 'nowrap' }}
        >
          <Plus size={18} />
          <span>Enroll New Card</span>
        </button>
        </div>
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

          <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
            <span style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>Status:</span>
            {['all', 'unpaired', 'active', 'suspended', 'lost', 'retired'].map((s) => (
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

      <LoadError message={loadError} onRetry={() => { loadVehicles(); loadCards(); }} />

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
                            {c.nfc_uid ?? '—'}
                          </td>
                          <td style={{ padding: '14px 24px', fontWeight: 600 }}>
                            {c.serial && <span style={{ fontFamily: 'monospace', marginRight: 6 }}>{c.serial}</span>}
                            {c.label && c.label !== c.serial ? c.label : !c.serial ? '—' : ''}
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
                                onClick={() => { setSelectedCardForQr(c); setWriteSuccess(null); }}
                                title="Print QR Sticker"
                                style={{ padding: '6px 10px', borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--bg-dark)', color: 'var(--accent)', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '11px', fontWeight: 600 }}
                              >
                                <QrCode size={15} />
                                <span>QR</span>
                              </button>
                              <CardRowMenu
                                serial={c.serial ?? c.label ?? c.card_uuid.slice(0, 8)}
                                status={c.status}
                                hasChip={c.nfc_uid !== null}
                                onAction={(action, to) => handleRowAction(c, action, to)}
                              />
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

      {rowError && (
        <div role="alert" style={{ position: 'fixed', bottom: 20, left: '50%', transform: 'translateX(-50%)', zIndex: 1100, padding: '10px 16px', borderRadius: 8, background: '#2a1215', border: '1px solid var(--danger)', color: 'var(--danger)', fontSize: 14 }}>
          {rowError}
        </div>
      )}
      {pending && (
        <CardActionDialog
          confirmation={pending.confirmation}
          run={pending.run}
          onDone={() => { setPending(null); loadCards(); }}
          onClose={() => setPending(null)}
        />
      )}
      {editing && (
        <CardEditDialog card={editing} onDone={() => { setEditing(null); loadCards(); }} onClose={() => setEditing(null)} />
      )}
      {historyOf && <CardHistoryDrawer card={historyOf} onClose={() => setHistoryOf(null)} />}
      {replacingChip && (
        <Modal title={`Replace chip on ${replacingChip.serial ?? 'card'}`} onClose={() => { setReplacingChip(null); loadCards(); }}>
          <PairPanel
            mode="replace"
            info={{ card_uuid: replacingChip.card_uuid, serial: replacingChip.serial, label: replacingChip.label, status: replacingChip.status, nfc_uid: replacingChip.nfc_uid }}
            onPaired={loadCards}
          />
        </Modal>
      )}
      {showPairModal && (
        <PairPrintedCardModal
          waiting={unpairedCards.map((c) => ({ card_uuid: c.card_uuid, serial: c.serial, label: c.label, status: c.status, nfc_uid: c.nfc_uid }))}
          onClose={() => setShowPairModal(false)}
          onChanged={loadCards}
        />
      )}

      {/* Modal: Enroll Card with Web NFC & Vehicle Selection */}
      {showEnrollModal && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000 }}>
          <div className="glass-panel" style={{ width: '100%', maxWidth: '480px', padding: '28px' }}>
            <h3 style={{ margin: '0 0 16px 0', fontSize: '20px', borderBottom: '1px solid var(--border)', paddingBottom: '12px' }}>
              Enroll Physical NFC Card
            </h3>
            <div style={{ marginBottom: 16, padding: '10px 14px', fontSize: 13, color: 'var(--text-secondary)', background: 'rgba(255,255,255,0.04)', border: '1px solid var(--border)', borderRadius: 8 }}>
              Cards with a printed QR code? Close this and use <strong>Pair printed card</strong> instead. This form is for blank cards with no printed QR.
            </div>

            {hasWebNfc ? (
              <div style={{ marginBottom: '20px', padding: '16px', background: 'rgba(0, 210, 255, 0.08)', border: '1px solid var(--accent)', borderRadius: '8px', textAlign: 'center' }}>
                <p style={{ margin: '0 0 10px 0', fontSize: '13px', color: 'var(--text-secondary)' }}>
                  Web NFC is active. Click below and tap the card against your phone.
                </p>
                <button
                  type="button"
                  onClick={startWebNfcScan}
                  disabled={nfcScanning}
                  className="btn-primary"
                  style={{ display: 'inline-flex', alignItems: 'center', gap: '8px', padding: '10px 18px', borderRadius: '6px' }}
                >
                  <Radio size={16} />
                  <span>{nfcScanning ? 'Listening... Tap Card to Back of Phone' : 'Tap Card to Scan (Web NFC)'}</span>
                </button>
                {nfcStatusText && (
                  <div style={{ fontSize: '12px', color: 'var(--accent)', marginTop: '8px', fontWeight: 600 }}>
                    {nfcStatusText}
                  </div>
                )}
              </div>
            ) : (
              <div style={{ marginBottom: '16px', padding: '12px 16px', background: 'rgba(255, 255, 255, 0.04)', border: '1px solid var(--border)', borderRadius: '8px', fontSize: '12px', color: 'var(--text-secondary)' }}>
                ℹ️ Web NFC requires Chrome on Android. For desktop or unsupported browsers, enter the NFC UID manually below.
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

              {hasWebNfc && (
                <label style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={writeNdefOnEnroll}
                    onChange={(e) => setWriteNdefOnEnroll(e.target.checked)}
                  />
                  <span>Write static receipt URL to tag (NDEF) on save</span>
                </label>
              )}

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
                  onClick={closeEnrollModal}
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
          <div className="glass-panel" style={{ width: '100%', maxWidth: '440px', padding: '28px', textAlign: 'center' }}>
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
              margin: '0 auto 16px auto',
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
                {selectedCardForQr.serial || selectedCardForQr.label || `CARD #${(selectedCardForQr.nfc_uid ?? selectedCardForQr.card_uuid).slice(0, 8)}`}
              </div>
              <div style={{ fontSize: '10px', color: '#555', textAlign: 'center' }}>
                Scan with camera or tap phone to view e-Receipt
              </div>
            </div>

            <div style={{ fontSize: '12px', fontFamily: 'monospace', color: 'var(--text-secondary)', marginBottom: '16px' }}>
              Target: <a href={receiptUrl(selectedCardForQr.card_uuid)} target="_blank" rel="noreferrer" style={{ color: 'var(--accent)', textDecoration: 'none' }}>
                {receiptUrl(selectedCardForQr.card_uuid)} <ExternalLink size={12} style={{ display: 'inline' }} />
              </a>
            </div>

            {hasWebNfc && (
              <div style={{ marginBottom: '16px' }}>
                <button
                  type="button"
                  onClick={() => writeNdefUrlToTag(selectedCardForQr.card_uuid)}
                  disabled={writingTag}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '6px',
                    padding: '8px 16px',
                    borderRadius: '6px',
                    border: '1px solid var(--accent)',
                    background: 'rgba(0, 210, 255, 0.08)',
                    color: 'var(--accent)',
                    cursor: 'pointer',
                    fontSize: '12px',
                    fontWeight: 600,
                  }}
                >
                  <Wifi size={14} />
                  <span>{writingTag ? 'Hold card against phone...' : 'Write NDEF URL to Tag'}</span>
                </button>
                {writeSuccess && (
                  <div style={{ fontSize: '12px', color: 'var(--success)', marginTop: '6px' }}>{writeSuccess}</div>
                )}
              </div>
            )}

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
