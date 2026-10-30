import { useMemo, useState } from 'react';
import { AlertTriangle, FileDown } from 'lucide-react';
import { toPdfArt, shapeMismatch, type ArtSource } from '../lib/pdfArt';
import { QR_QUIET_MODULES, cardUrl, type Placement } from '../lib/cardDesign';
import { saveBlob, type ExportCard } from '../lib/cardRender';
import { buildSheetPdf } from '../lib/sheetPdf';
import {
  PAPERS,
  computeSheet,
  planSheets,
  sheetsNeeded,
  svgSlotTransform,
  type Flip,
  type Orientation,
  type SheetLayout,
} from '../lib/sheetLayout';

export interface SheetSideInput extends ArtSource {
  src: string;
  placement: Placement | null;
}

interface Props {
  cards: ExportCard[];
  baseUrl: string;
  front: SheetSideInput | undefined;
  back: SheetSideInput | undefined;
}

const input = { padding: '8px 10px', background: 'var(--bg-dark)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text-primary)', width: '100%' } as const;
const num = (v: string) => (v.trim() === '' ? Number.NaN : Number(v));

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: 'grid', gap: 4, fontSize: 13, minWidth: 110, flex: '1 1 110px' }}>
      <span style={{ color: 'var(--text-secondary)' }}>{label}</span>
      {children}
    </label>
  );
}

/**
 * Lays the cards out on paper sheets (A4, Letter or a custom size) with room between them for cutting,
 * shows the result, and downloads it as a PDF.
 */
export default function SheetExport({ cards, baseUrl, front, back }: Props) {
  const [paperId, setPaperId] = useState('a4');
  const [customW, setCustomW] = useState('210');
  const [customH, setCustomH] = useState('297');
  const [orientation, setOrientation] = useState<Orientation>('auto');
  const [cardW, setCardW] = useState('85.6');
  const [cardH, setCardH] = useState('54');
  const [margin, setMargin] = useState('10');
  const [gap, setGap] = useState('4');
  const [allowRotate, setAllowRotate] = useState(true);
  const [cropMarks, setCropMarks] = useState(true);
  const [flip, setFlip] = useState<Flip>('long');
  const [showBack, setShowBack] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const paper = paperId === 'custom' ? { w: num(customW), h: num(customH) } : PAPERS.find((p) => p.id === paperId) ?? PAPERS[0];
  const layout = useMemo(
    () =>
      computeSheet({
        paperW: paper.w,
        paperH: paper.h,
        orientation,
        cardW: num(cardW),
        cardH: num(cardH),
        margin: num(margin),
        gap: num(gap),
        allowRotate,
        cropMarks,
      }),
    [paper.w, paper.h, orientation, cardW, cardH, margin, gap, allowRotate, cropMarks],
  );

  const total = cards.length;
  const ok = layout.ok ? (layout as SheetLayout) : null;
  const sheets = ok ? sheetsNeeded(total, ok.perSheet) : 0;
  const withBack = !!back;
  const pages = useMemo(() => (ok && total > 0 ? planSheets(ok, total, flip, withBack) : []), [ok, total, flip, withBack]);
  const previewPage = pages.find((p) => p.sheet === 0 && p.side === (showBack && withBack ? 'back' : 'front'));
  const shape = front ? shapeMismatch(num(cardW), num(cardH), front.width, front.height) : 0;

  const matchArtwork = () => {
    if (front) setCardH((Math.round((num(cardW) * front.height) / front.width * 10) / 10).toString());
  };

  async function download() {
    if (!ok || !front) return;
    setError(null);
    setBusy('Preparing…');
    try {
      const bytes = await buildSheetPdf({
        layout: ok,
        cardW: num(cardW),
        cardH: num(cardH),
        cards,
        baseUrl,
        flip,
        front: { art: await toPdfArt(front), placement: front.placement },
        back: back ? { art: await toPdfArt(back), placement: back.placement } : null,
        onProgress: (d, t) => setBusy(`Building sheet ${d} of ${t}…`),
      });
      const blob = new Blob([bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer], { type: 'application/pdf' });
      saveBlob(blob, `${cards[0].serial}_to_${cards[cards.length - 1].serial}_sheets.pdf`);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const disabledReason = !front ? 'Upload the front design first.' : total === 0 ? 'Create the cards first.' : !ok ? (layout as { error: string }).error : null;

  return (
    <section className="glass-panel" style={{ padding: 24, display: 'grid', gap: 14 }} aria-label="Print on paper sheets">
      <h3 style={{ margin: 0 }}>4. Print on paper sheets (PDF)</h3>
      <div style={{ color: 'var(--text-secondary)', fontSize: 13 }}>
        For printing the cards yourselves, or sending the print shop ready-made sheets. Cards are laid out to fill each sheet, with space between them for the blade.
      </div>

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        <Field label="Paper">
          <select aria-label="Paper" style={input} value={paperId} onChange={(e) => setPaperId(e.target.value)}>
            {PAPERS.map((p) => (
              <option key={p.id} value={p.id}>{p.label} ({p.w} × {p.h} mm)</option>
            ))}
            <option value="custom">Custom size…</option>
          </select>
        </Field>
        {paperId === 'custom' && (
          <>
            <Field label="Paper width (mm)">
              <input aria-label="Paper width" type="number" min={50} step="0.1" style={input} value={customW} onChange={(e) => setCustomW(e.target.value)} />
            </Field>
            <Field label="Paper height (mm)">
              <input aria-label="Paper height" type="number" min={50} step="0.1" style={input} value={customH} onChange={(e) => setCustomH(e.target.value)} />
            </Field>
          </>
        )}
        <Field label="Paper direction">
          <select aria-label="Paper direction" style={input} value={orientation} onChange={(e) => setOrientation(e.target.value as Orientation)}>
            <option value="auto">Automatic (fits the most)</option>
            <option value="portrait">Portrait</option>
            <option value="landscape">Landscape</option>
          </select>
        </Field>
      </div>

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        <Field label="Card width (mm)">
          <input aria-label="Card width" type="number" min={10} step="0.1" style={input} value={cardW} onChange={(e) => setCardW(e.target.value)} />
        </Field>
        <Field label="Card height (mm)">
          <input aria-label="Card height" type="number" min={10} step="0.1" style={input} value={cardH} onChange={(e) => setCardH(e.target.value)} />
        </Field>
        <Field label="Border around sheet (mm)">
          <input aria-label="Margin" type="number" min={0} step="0.5" style={input} value={margin} onChange={(e) => setMargin(e.target.value)} />
        </Field>
        <Field label="Space between cards (mm)">
          <input aria-label="Space between cards" type="number" min={0} step="0.5" style={input} value={gap} onChange={(e) => setGap(e.target.value)} />
        </Field>
      </div>

      <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap', alignItems: 'center', fontSize: 13 }}>
        <label style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
          <input type="checkbox" checked={allowRotate} onChange={(e) => setAllowRotate(e.target.checked)} /> Turn cards sideways when it fits more
        </label>
        <label style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
          <input type="checkbox" checked={cropMarks} onChange={(e) => setCropMarks(e.target.checked)} /> Cutting marks
        </label>
        {back && (
          <label style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
            Two-sided printing flips on the
            <select aria-label="Flip edge" style={{ ...input, width: 'auto' }} value={flip} onChange={(e) => setFlip(e.target.value as Flip)}>
              <option value="long">long edge (usual default)</option>
              <option value="short">short edge</option>
            </select>
          </label>
        )}
        {front && shape > 0.01 && (
          <button type="button" onClick={matchArtwork} style={{ background: 'transparent', border: '1px solid var(--warning)', color: 'var(--warning)', borderRadius: 6, padding: '6px 10px', cursor: 'pointer' }}>
            <AlertTriangle size={13} /> Card shape differs from the design. Match height to the design
          </button>
        )}
      </div>

      {!ok && (
        <div role="alert" style={{ color: 'var(--danger)', fontSize: 13 }}>{(layout as { error: string }).error}</div>
      )}

      {ok && (
        <div data-testid="sheet-summary" style={{ fontSize: 14 }}>
          <strong>{ok.perSheet} cards per sheet</strong> ({ok.cols} × {ok.rows}{ok.rotated ? ', turned sideways' : ''}, {ok.landscape ? 'landscape' : 'portrait'} {Math.round(ok.pageW * 10) / 10} × {Math.round(ok.pageH * 10) / 10} mm).
          {total > 0 && <> {total} cards need <strong>{sheets} sheet{sheets === 1 ? '' : 's'}</strong>{withBack ? `, ${sheets * 2} pages with the backs` : ''}.</>}
          {ok.markNote && <div style={{ color: 'var(--warning)', marginTop: 4 }}>{ok.markNote}</div>}
        </div>
      )}

      {ok && (
        <div>
          {withBack && (
            <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
              {(['front', 'back'] as const).map((s) => (
                <button
                  key={s}
                  type="button"
                  onClick={() => setShowBack(s === 'back')}
                  aria-pressed={(s === 'back') === showBack}
                  style={{ padding: '4px 12px', borderRadius: 6, border: '1px solid var(--border)', background: (s === 'back') === showBack ? 'var(--accent)' : 'transparent', color: (s === 'back') === showBack ? '#000' : 'var(--text-secondary)', cursor: 'pointer', textTransform: 'capitalize' }}
                >
                  {s} of sheet 1
                </button>
              ))}
            </div>
          )}
          <svg
            data-testid="sheet-preview"
            viewBox={`0 0 ${ok.pageW} ${ok.pageH}`}
            style={{ width: '100%', maxWidth: 360, background: '#fff', border: '1px solid var(--border)', display: 'block' }}
            role="img"
            aria-label="Preview of the first sheet"
          >
            {previewPage
              ? previewPage.items.map((it) => {
                  const side = previewPage.side === 'back' && back ? back : front;
                  const card = cards[it.index];
                  const p = side?.placement;
                  const cw = num(cardW);
                  const ch = num(cardH);
                  return (
                    <g key={it.index} data-testid="sheet-card" transform={svgSlotTransform(it.rotation, it.slot)}>
                      {side ? <image href={side.src} x={0} y={0} width={cw} height={ch} preserveAspectRatio="none" /> : <rect width={cw} height={ch} fill="#0B3D91" />}
                      {p && (
                        <>
                          <rect x={p.qrX * cw} y={p.qrY * ch} width={p.qrSize * cw} height={p.qrSize * cw} fill="#fff" />
                          <rect x={p.qrX * cw + (p.qrSize * cw * QR_QUIET_MODULES) / 29} y={p.qrY * ch + (p.qrSize * cw * QR_QUIET_MODULES) / 29} width={(p.qrSize * cw * 21) / 29} height={(p.qrSize * cw * 21) / 29} fill="#222" opacity={0.85} />
                          <text x={p.textX * cw} y={p.textY * ch} fontSize={p.textSize * cw} fill={p.textColor} textAnchor="middle" dominantBaseline="middle" fontFamily="monospace" fontWeight={700}>
                            {card?.serial}
                          </text>
                        </>
                      )}
                    </g>
                  );
                })
              : ok.slots.map((s) => <rect key={`${s.col}-${s.row}`} x={s.x} y={s.y} width={s.w} height={s.h} fill="#dbe7f7" stroke="#8aa" strokeWidth={0.3} />)}
            {ok.marks.map((m, i) => (
              <line key={i} x1={m.x1} y1={m.y1} x2={m.x2} y2={m.y2} stroke="#000" strokeWidth={0.2} />
            ))}
          </svg>
          <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
            Preview of sheet 1. Print the PDF at 100% (Actual size), never "Fit to page", or the cards will come out the wrong size.
          </div>
        </div>
      )}

      <button
        type="button"
        className="btn-primary"
        onClick={download}
        disabled={busy !== null || disabledReason !== null}
        style={{ padding: '12px 20px', borderRadius: 8, justifySelf: 'start', display: 'inline-flex', gap: 8 }}
      >
        <FileDown size={16} /> Download PDF
      </button>
      {disabledReason && <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{disabledReason}</div>}
      {busy && <div role="status">{busy}</div>}
      {error && <div role="alert" style={{ color: 'var(--danger)' }}>{error}</div>}
      <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
        Cards link to {cardUrl(baseUrl, '<card id>')}
      </div>
    </section>
  );
}
