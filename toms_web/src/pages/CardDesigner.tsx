import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { QRCodeSVG } from 'qrcode.react';
import { ArrowLeft, Download, Upload, AlertTriangle, Info } from 'lucide-react';
import { api } from '../lib/api';
import { getPublicBase } from '../lib/publicBase';
import {
  cardUrl,
  clampPlacement,
  DEFAULT_PLACEMENT,
  fractionOf,
  formatSerial,
  outputSize,
  resolutionNote,
  serialRange,
  validateBatch,
  type Placement,
} from '../lib/cardDesign';
import { buildExportZip, loadDesignImage, saveBlob, type DesignImage, type ExportCard } from '../lib/cardRender';

type Side = 'front' | 'back';
type Drag = { side: Side; target: 'qr' | 'text'; dx: number; dy: number } | null;

interface SideState {
  design: DesignImage;
  src: string;
  placement: Placement | null;
}

const inputStyle = { padding: '10px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-primary)' } as const;

export default function CardDesigner() {
  const [prefix, setPrefix] = useState('TOMS-');
  const [start, setStart] = useState(1);
  const [digits, setDigits] = useState(4);
  const [count, setCount] = useState(50);
  const [vehicles, setVehicles] = useState<{ id: string; plate_number: string }[]>([]);
  const [vehicleId, setVehicleId] = useState('');
  const [base, setBase] = useState<{ url: string; fromServer: boolean } | null>(null);
  const [baseOverride, setBaseOverride] = useState('');
  const [cards, setCards] = useState<ExportCard[]>([]);
  const [sides, setSides] = useState<Partial<Record<Side, SideState>>>({});
  const [qrSide, setQrSide] = useState<Side>('front');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [progress, setProgress] = useState<[number, number] | null>(null);
  const dragRef = useRef<Drag>(null);
  const previewRefs = useRef<Partial<Record<Side, HTMLDivElement | null>>>({});

  useEffect(() => {
    getPublicBase().then(setBase);
    api.get('/vehicles').then((r) => setVehicles(r.data || [])).catch(() => undefined);
  }, []);

  const baseUrl = (baseOverride.trim() || base?.url || window.location.origin).replace(/\/+$/, '');
  const rule = { prefix, start, digits };
  const range = serialRange(rule, count);
  const sampleUuid = cards[0]?.card_uuid ?? '00000000-0000-4000-8000-000000000000';
  const sampleSerial = cards[0]?.serial ?? formatSerial(rule, 0);

  async function generate() {
    const problem = validateBatch(rule, count);
    if (problem) return void setError(problem);
    setError(null);
    setBusy('Creating cards…');
    try {
      const res = await api.post<{ cards: ExportCard[] }>('/cards/batch', {
        count,
        prefix,
        start,
        digits,
        vehicle_id: vehicleId || null,
      });
      setCards(res.data.cards);
    } catch (err) {
      const e = err as { response?: { data?: { error?: string } } };
      setError(e.response?.data?.error ?? 'Could not create the cards.');
    } finally {
      setBusy(null);
    }
  }

  async function upload(side: Side, file: File | undefined) {
    if (!file) return;
    setError(null);
    try {
      const image = await loadDesignImage(file);
      const isSvg = file.type === 'image/svg+xml' || file.name.toLowerCase().endsWith('.svg');
      const { width, height } = outputSize(image.naturalWidth, image.naturalHeight, isSvg);
      const src = URL.createObjectURL(file);
      setSides((s) => ({
        ...s,
        [side]: { design: { image, width, height }, src, placement: side === qrSide ? clampPlacement(DEFAULT_PLACEMENT, width, height) : null },
      }));
    } catch (err) {
      setError((err as Error).message);
    }
  }

  function chooseQrSide(side: Side) {
    setQrSide(side);
    setSides((s) => {
      const next = { ...s };
      const other = side === 'front' ? 'back' : 'front';
      const keep = next[other]?.placement ?? DEFAULT_PLACEMENT;
      if (next[other]) next[other] = { ...next[other]!, placement: null };
      if (next[side]) next[side] = { ...next[side]!, placement: clampPlacement(keep, next[side]!.design.width, next[side]!.design.height) };
      return next;
    });
  }

  function update(side: Side, patch: Partial<Placement>) {
    setSides((s) => {
      const cur = s[side];
      if (!cur?.placement) return s;
      return { ...s, [side]: { ...cur, placement: clampPlacement({ ...cur.placement, ...patch }, cur.design.width, cur.design.height) } };
    });
  }

  function onPointerDown(e: React.PointerEvent, side: Side, target: 'qr' | 'text') {
    const el = previewRefs.current[side];
    const placement = sides[side]?.placement;
    if (!el || !placement) return;
    e.preventDefault();
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    const f = fractionOf(e.clientX, e.clientY, el.getBoundingClientRect());
    dragRef.current =
      target === 'qr'
        ? { side, target, dx: f.x - placement.qrX, dy: f.y - placement.qrY }
        : { side, target, dx: f.x - placement.textX, dy: f.y - placement.textY };
  }

  function onPointerMove(e: React.PointerEvent) {
    const drag = dragRef.current;
    const el = drag && previewRefs.current[drag.side];
    if (!drag || !el) return;
    const f = fractionOf(e.clientX, e.clientY, el.getBoundingClientRect());
    if (drag.target === 'qr') update(drag.side, { qrX: f.x - drag.dx, qrY: f.y - drag.dy });
    else update(drag.side, { textX: f.x - drag.dx, textY: f.y - drag.dy });
  }

  async function exportZip() {
    const front = sides.front;
    if (!front) return void setError('Upload the front design first.');
    if (cards.length === 0) return void setError('Create the cards first.');
    if (!front.placement && !sides.back?.placement) return void setError('Place the QR on one side of the card.');
    setError(null);
    setBusy('Building files…');
    try {
      const zip = await buildExportZip({
        cards,
        baseUrl,
        front: front.design,
        frontPlacement: front.placement,
        back: sides.back?.design ?? null,
        backPlacement: sides.back?.placement ?? null,
        onProgress: (d, t) => setProgress([d, t]),
      });
      saveBlob(zip, `${cards[0].serial}_to_${cards[cards.length - 1].serial}.zip`);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(null);
      setProgress(null);
    }
  }

  const activeSide = sides[qrSide];
  const note = activeSide?.placement ? resolutionNote(activeSide.design.width, activeSide.design.height, activeSide.placement.qrSize) : null;

  return (
    <div style={{ height: '100%', overflow: 'auto', display: 'flex', flexDirection: 'column', gap: 20, paddingBottom: 24 }}>
      <header className="glass-panel" style={{ padding: 24 }}>
        <Link to="/cards" style={{ color: 'var(--accent)', fontSize: 13, textDecoration: 'none' }}>
          <ArrowLeft size={14} /> Back to Card Registry
        </Link>
        <h2 style={{ fontSize: 26, margin: '8px 0 4px' }}>Print-ready cards</h2>
        <div style={{ color: 'var(--text-secondary)' }}>
          Create a batch of cards, place the QR on your Canva design, and download one image per card for the print shop.
        </div>
      </header>

      <section className="glass-panel" style={{ padding: 24, display: 'grid', gap: 14 }}>
        <h3 style={{ margin: 0 }}>1. Serial numbers and cards</h3>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <label>Prefix<br /><input aria-label="Prefix" style={inputStyle} value={prefix} onChange={(e) => setPrefix(e.target.value)} /></label>
          <label>Start at<br /><input aria-label="Start at" type="number" style={{ ...inputStyle, width: 110 }} value={start} onChange={(e) => setStart(Number(e.target.value))} /></label>
          <label>Digits<br /><input aria-label="Digits" type="number" style={{ ...inputStyle, width: 90 }} value={digits} onChange={(e) => setDigits(Number(e.target.value))} /></label>
          <label>How many<br /><input aria-label="How many" type="number" style={{ ...inputStyle, width: 110 }} value={count} onChange={(e) => setCount(Number(e.target.value))} /></label>
          <label>Vehicle deck<br />
            <select aria-label="Vehicle deck" style={inputStyle} value={vehicleId} onChange={(e) => setVehicleId(e.target.value)}>
              <option value="">Spare pool (any vehicle)</option>
              {vehicles.map((v) => <option key={v.id} value={v.id}>{v.id} ({v.plate_number})</option>)}
            </select>
          </label>
        </div>
        <div data-testid="serial-preview" style={{ fontFamily: 'monospace' }}>
          {range.first} → {range.last}
        </div>

        <label>QR link address<br />
          <input
            aria-label="QR link address"
            style={{ ...inputStyle, width: '100%', maxWidth: 420 }}
            placeholder={base?.url ?? window.location.origin}
            value={baseOverride}
            onChange={(e) => setBaseOverride(e.target.value)}
          />
        </label>
        <div style={{ fontFamily: 'monospace', fontSize: 12, color: 'var(--text-secondary)' }}>
          Each QR opens {cardUrl(baseUrl, '<card id>')}
        </div>
        <div role="note" style={{ color: 'var(--warning)', fontSize: 13 }}>
          <AlertTriangle size={14} /> This address is printed on the cards and cannot be changed afterwards.
          {base && !base.fromServer && ' The server has no PUBLIC_BASE_URL set, so this is the address you have open right now.'}
        </div>

        {cards.length === 0 ? (
          <button type="button" className="btn-primary" onClick={generate} disabled={busy !== null} style={{ padding: '12px 20px', borderRadius: 8, justifySelf: 'start' }}>
            Create {count} unpaired cards
          </button>
        ) : (
          <div role="status" style={{ color: 'var(--success)' }}>
            {cards.length} cards created: {cards[0].serial} to {cards[cards.length - 1].serial}. They stay unpaired until each is tapped.
          </div>
        )}
      </section>

      <section className="glass-panel" style={{ padding: 24, display: 'grid', gap: 14 }}>
        <h3 style={{ margin: 0 }}>2. Design</h3>
        <div style={{ color: 'var(--text-secondary)', fontSize: 13 }}>
          Export the card from Canva with the QR area left blank (PNG, JPG or SVG). The print shop sets the final size.
        </div>
        <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
          {(['front', 'back'] as Side[]).map((side) => (
            <div key={side} style={{ flex: '1 1 340px' }}>
              <label style={{ display: 'inline-flex', gap: 6, cursor: 'pointer', color: 'var(--accent)' }}>
                <Upload size={16} /> {side === 'front' ? 'Front design' : 'Back design (optional)'}
                <input
                  aria-label={`${side} design`}
                  type="file"
                  accept="image/png,image/jpeg,image/svg+xml,.svg"
                  hidden
                  onChange={(e) => upload(side, e.target.files?.[0])}
                />
              </label>
              {sides[side] && (
                <>
                  <label style={{ marginLeft: 12, fontSize: 13 }}>
                    <input type="radio" name="qrSide" checked={qrSide === side} onChange={() => chooseQrSide(side)} /> QR goes on this side
                  </label>
                  <div
                    ref={(el) => { previewRefs.current[side] = el; }}
                    onPointerMove={onPointerMove}
                    onPointerUp={() => { dragRef.current = null; }}
                    style={{ position: 'relative', marginTop: 8, touchAction: 'none', userSelect: 'none', containerType: 'inline-size', border: '1px solid var(--border)' }}
                  >
                    <img src={sides[side]!.src} alt={`${side} design preview`} style={{ width: '100%', display: 'block' }} draggable={false} />
                    {sides[side]!.placement && (() => {
                      const p = sides[side]!.placement!;
                      return (
                        <>
                          <div
                            data-testid={`qr-box-${side}`}
                            onPointerDown={(e) => onPointerDown(e, side, 'qr')}
                            style={{
                              position: 'absolute',
                              left: `${p.qrX * 100}%`,
                              top: `${p.qrY * 100}%`,
                              width: `${p.qrSize * 100}%`,
                              aspectRatio: '1',
                              background: '#fff',
                              cursor: 'move',
                              outline: '2px dashed var(--accent)',
                            }}
                          >
                            <QRCodeSVG value={cardUrl(baseUrl, sampleUuid)} size={256} level="M" marginSize={0} style={{ width: '100%', height: '100%', display: 'block' }} />
                          </div>
                          <div
                            data-testid={`text-box-${side}`}
                            onPointerDown={(e) => onPointerDown(e, side, 'text')}
                            style={{
                              position: 'absolute',
                              left: `${p.textX * 100}%`,
                              top: `${p.textY * 100}%`,
                              transform: 'translate(-50%, -50%)',
                              fontFamily: 'monospace',
                              fontWeight: 700,
                              whiteSpace: 'nowrap',
                              fontSize: `${p.textSize * 100}cqw`,
                              color: p.textColor,
                              cursor: 'move',
                              outline: '1px dashed var(--accent)',
                            }}
                          >
                            {sampleSerial}
                          </div>
                        </>
                      );
                    })()}
                  </div>
                </>
              )}
            </div>
          ))}
        </div>

        {activeSide?.placement && (
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'center' }}>
            <label>QR size
              <input aria-label="QR size" type="range" min={8} max={60} value={Math.round(activeSide.placement.qrSize * 100)} onChange={(e) => update(qrSide, { qrSize: Number(e.target.value) / 100 })} />
            </label>
            <label>Serial size
              <input aria-label="Serial size" type="range" min={1} max={12} value={Math.round(activeSide.placement.textSize * 100)} onChange={(e) => update(qrSide, { textSize: Number(e.target.value) / 100 })} />
            </label>
            <label>Serial colour
              <input aria-label="Serial colour" type="color" value={activeSide.placement.textColor} onChange={(e) => update(qrSide, { textColor: e.target.value })} />
            </label>
          </div>
        )}
        {note && (
          <div role="note" data-testid="resolution-note" style={{ color: note.level === 'warn' ? 'var(--warning)' : 'var(--text-secondary)', fontSize: 13 }}>
            {note.level === 'warn' ? <AlertTriangle size={14} /> : <Info size={14} />} {note.message}
          </div>
        )}
        <div style={{ color: 'var(--text-secondary)', fontSize: 12 }}>
          Keep the area around the QR plain and light, and ask the shop where the NFC antenna sits so no metallic ink covers it.
        </div>
      </section>

      <section className="glass-panel" style={{ padding: 24, display: 'grid', gap: 12 }}>
        <h3 style={{ margin: 0 }}>3. Download for the print shop</h3>
        <button
          type="button"
          className="btn-primary"
          onClick={exportZip}
          disabled={busy !== null}
          style={{ padding: '12px 20px', borderRadius: 8, justifySelf: 'start', display: 'inline-flex', gap: 8 }}
        >
          <Download size={16} /> Download ZIP
        </button>
        {busy && <div role="status">{busy}{progress ? ` ${progress[0]} / ${progress[1]}` : ''}</div>}
      </section>

      {error && <div role="alert" style={{ color: 'var(--danger)' }}>{error}</div>}
    </div>
  );
}
