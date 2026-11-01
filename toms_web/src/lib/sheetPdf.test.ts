import { PDFDocument, PDFName, PDFRawStream } from 'pdf-lib';
import { describe, expect, it } from 'vitest';
import { DEFAULT_PLACEMENT } from './cardDesign';
import { buildSheetPdf, qrPath } from './sheetPdf';
import { PT_PER_MM, computeSheet, type SheetLayout } from './sheetLayout';

// 1 x 1 PNG, enough for pdf-lib to embed.
const PIXEL = Uint8Array.from(
  atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='),
  (c) => c.charCodeAt(0),
);
const art = { bytes: PIXEL, kind: 'png' as const, width: 1011, height: 638 };
const cards = (n: number) => Array.from({ length: n }, (_, i) => ({ serial: `T-${String(i + 1).padStart(3, '0')}`, card_uuid: `00000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}` }));

const layoutFor = (over: object = {}): SheetLayout => {
  const r = computeSheet({ paperW: 210, paperH: 297, orientation: 'portrait', cardW: 85.6, cardH: 54, margin: 10, gap: 4, allowRotate: false, cropMarks: true, ...over });
  if (!r.ok) throw new Error(r.error);
  return r;
};

const build = (n: number, opts: { back?: boolean; layout?: SheetLayout; flip?: 'long' | 'short' } = {}) =>
  buildSheetPdf({
    layout: opts.layout ?? layoutFor(),
    cardW: 85.6,
    cardH: 54,
    cards: cards(n),
    baseUrl: 'https://toms.example.com',
    flip: opts.flip ?? 'long',
    front: { art, placement: DEFAULT_PLACEMENT },
    back: opts.back ? { art, placement: null } : null,
  });

describe('buildSheetPdf', () => {
  it('writes a valid PDF with one page per sheet', async () => {
    const doc = await PDFDocument.load(await build(20));
    expect(doc.getPageCount()).toBe(3); // 8 + 8 + 4
  });

  it('sizes the pages to the paper', async () => {
    const doc = await PDFDocument.load(await build(3));
    const { width, height } = doc.getPage(0).getSize();
    expect(width).toBeCloseTo(210 * PT_PER_MM, 3);
    expect(height).toBeCloseTo(297 * PT_PER_MM, 3);
  });

  it('uses landscape pages when the layout does', async () => {
    const layout = layoutFor({ orientation: 'landscape' });
    const doc = await PDFDocument.load(await build(3, { layout }));
    const { width, height } = doc.getPage(0).getSize();
    expect(width).toBeGreaterThan(height);
  });

  it('adds a back page after every front page when there is a back design', async () => {
    const doc = await PDFDocument.load(await build(10, { back: true }));
    expect(doc.getPageCount()).toBe(4); // 2 sheets x (front + back)
  });

  it('supports a custom paper size', async () => {
    const layout = layoutFor({ paperW: 150, paperH: 220 });
    const doc = await PDFDocument.load(await build(5, { layout }));
    const { width, height } = doc.getPage(0).getSize();
    expect(width).toBeCloseTo(150 * PT_PER_MM, 3);
    expect(height).toBeCloseTo(220 * PT_PER_MM, 3);
  });

  const imageCount = async (bytes: Uint8Array) => {
    const doc = await PDFDocument.load(bytes);
    let n = 0;
    for (const [, obj] of doc.context.enumerateIndirectObjects()) {
      const isImage = obj instanceof PDFRawStream && obj.dict.get(PDFName.of('Subtype')) === PDFName.of('Image');
      // A PNG with transparency also stores a grey mask image; that is not a second copy of the design.
      if (isImage && obj.dict.get(PDFName.of('ColorSpace')) !== PDFName.of('DeviceGray')) n++;
    }
    return n;
  };

  it('stores the front design once however many cards use it', async () => {
    expect(await imageCount(await build(3))).toBe(1);
    expect(await imageCount(await build(60))).toBe(1);
  });

  it('stores one design for the front and one for the back', async () => {
    expect(await imageCount(await build(60, { back: true }))).toBe(2);
  });

  it('keeps the file small: about one kilobyte per card for its QR code', async () => {
    const bytes = await build(100);
    expect(bytes.length).toBeLessThan(100 * 1500 + 20_000);
  });

  it('reports progress for each sheet', async () => {
    const seen: number[] = [];
    await buildSheetPdf({
      layout: layoutFor(), cardW: 85.6, cardH: 54, cards: cards(17), baseUrl: 'https://x.test', flip: 'long',
      front: { art, placement: null }, onProgress: (d) => seen.push(d),
    });
    expect(seen).toEqual([1, 2, 3]);
  });

  it('explains an image that cannot be placed and has no fallback', async () => {
    await expect(
      buildSheetPdf({
        layout: layoutFor(), cardW: 85.6, cardH: 54, cards: cards(1), baseUrl: 'https://x.test', flip: 'long',
        front: { art: { ...art, bytes: new Uint8Array([1, 2, 3]) }, placement: null },
      }),
    ).rejects.toThrow(/cannot be placed in a PDF/);
  });

  it('uses the fallback when the original bytes cannot be embedded', async () => {
    const bytes = await buildSheetPdf({
      layout: layoutFor(), cardW: 85.6, cardH: 54, cards: cards(1), baseUrl: 'https://x.test', flip: 'long',
      front: { art: { ...art, bytes: new Uint8Array([1, 2, 3]), rasterize: async () => PIXEL }, placement: null },
    });
    expect((await PDFDocument.load(bytes)).getPageCount()).toBe(1);
  });
});

describe('qrPath', () => {
  it('draws only dark modules and stays within the code', () => {
    const { path, modules } = qrPath('https://toms.example.com/r/00000000-0000-4000-8000-000000000001');
    expect(modules).toBeGreaterThan(20);
    for (const m of path.matchAll(/M(\d+) (\d+)h(\d+)v1h-\d+z/g)) {
      const [x, y, run] = [Number(m[1]), Number(m[2]), Number(m[3])];
      expect(x + run).toBeLessThanOrEqual(modules);
      expect(y).toBeLessThan(modules);
    }
    expect(path.length).toBeGreaterThan(100);
  });

  it('gives different codes for different cards', () => {
    expect(qrPath('https://x.test/r/a').path).not.toBe(qrPath('https://x.test/r/b').path);
  });
});
