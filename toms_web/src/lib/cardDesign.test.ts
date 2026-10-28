import { describe, expect, it } from 'vitest';
import {
  buildCsv,
  cardFileName,
  cardUrl,
  clampPlacement,
  DEFAULT_PLACEMENT,
  formatSerial,
  fractionOf,
  outputSize,
  resolutionNote,
  serialRange,
  SVG_FALLBACK_WIDTH,
  toPixels,
  validateBatch,
} from './cardDesign';

describe('serials', () => {
  const rule = { prefix: 'TOMS-', start: 1, digits: 4 };

  it('pads to the chosen number of digits', () => {
    expect(formatSerial(rule, 0)).toBe('TOMS-0001');
    expect(formatSerial(rule, 11)).toBe('TOMS-0012');
  });

  it('does not truncate a number longer than the padding', () => {
    expect(formatSerial({ prefix: '', start: 99999, digits: 3 }, 0)).toBe('99999');
  });

  it('reports the first and last serial of a batch', () => {
    expect(serialRange(rule, 50)).toEqual({ first: 'TOMS-0001', last: 'TOMS-0050' });
  });

  it('accepts a valid rule and rejects invalid ones', () => {
    expect(validateBatch(rule, 50)).toBeNull();
    expect(validateBatch(rule, 0)).not.toBeNull();
    expect(validateBatch(rule, 501)).not.toBeNull();
    expect(validateBatch(rule, 1.5)).not.toBeNull();
    expect(validateBatch({ ...rule, prefix: 'A B' }, 5)).not.toBeNull();
    expect(validateBatch({ ...rule, start: -1 }, 5)).not.toBeNull();
    expect(validateBatch({ ...rule, digits: 0 }, 5)).not.toBeNull();
    expect(validateBatch({ ...rule, digits: 11 }, 5)).not.toBeNull();
  });
});

describe('cardUrl', () => {
  it('joins the base and uuid without doubled slashes', () => {
    expect(cardUrl('https://toms.example.com/', 'abc')).toBe('https://toms.example.com/r/abc');
    expect(cardUrl('https://toms.example.com', 'abc')).toBe('https://toms.example.com/r/abc');
  });
});

describe('placement maths', () => {
  it('keeps the QR square inside a wide image', () => {
    const p = clampPlacement({ ...DEFAULT_PLACEMENT, qrX: 0.95, qrY: 0.95, qrSize: 0.3 }, 1000, 630);
    expect(p.qrX + p.qrSize).toBeLessThanOrEqual(1);
    expect(p.qrY + p.qrSize * (1000 / 630)).toBeLessThanOrEqual(1.000001);
  });

  it('limits the QR size so it fits the shorter side', () => {
    const p = clampPlacement({ ...DEFAULT_PLACEMENT, qrSize: 0.9 }, 1000, 500);
    expect(p.qrSize).toBeLessThanOrEqual(0.5);
  });

  it('does not let the QR shrink to nothing', () => {
    expect(clampPlacement({ ...DEFAULT_PLACEMENT, qrSize: 0 }, 1000, 600).qrSize).toBe(0.08);
  });

  it('scales the same placement to any resolution', () => {
    const p = { ...DEFAULT_PLACEMENT, qrX: 0.5, qrY: 0.25, qrSize: 0.25, textX: 0.5, textY: 0.5, textSize: 0.05 };
    expect(toPixels(p, 1000, 600)).toEqual({ qrX: 500, qrY: 150, qrSize: 250, textX: 500, textY: 300, textPx: 50 });
    expect(toPixels(p, 2000, 1200)).toEqual({ qrX: 1000, qrY: 300, qrSize: 500, textX: 1000, textY: 600, textPx: 100 });
  });

  it('converts a pointer position to a share of the preview and clamps outside it', () => {
    const rect = { left: 100, top: 50, width: 400, height: 200 };
    expect(fractionOf(300, 150, rect)).toEqual({ x: 0.5, y: 0.5 });
    expect(fractionOf(0, 9999, rect)).toEqual({ x: 0, y: 1 });
  });
});

describe('resolutionNote', () => {
  it('warns about a narrow design', () => {
    expect(resolutionNote(800, 500, 0.3).level).toBe('warn');
  });

  it('warns about a tiny QR', () => {
    expect(resolutionNote(1200, 750, 0.05).level).toBe('warn');
  });

  it('is fine for a large design with a sensible QR', () => {
    expect(resolutionNote(1011, 638, 0.3).level).toBe('ok');
  });
});

describe('outputSize', () => {
  it('uses the natural size of a raster design', () => {
    expect(outputSize(1011, 638, false)).toEqual({ width: 1011, height: 638 });
  });

  it('enlarges a small SVG and keeps its proportions', () => {
    expect(outputSize(400, 200, true)).toEqual({ width: SVG_FALLBACK_WIDTH, height: SVG_FALLBACK_WIDTH / 2 });
  });

  it('falls back to a card-shaped size when an SVG reports none', () => {
    const size = outputSize(0, 0, true);
    expect(size.width).toBe(SVG_FALLBACK_WIDTH);
    expect(size.height / size.width).toBeCloseTo(54 / 85.6, 2);
  });
});

describe('exports', () => {
  it('names files safely by serial and side', () => {
    expect(cardFileName('TOMS-0001', 'front')).toBe('TOMS-0001_front.png');
    expect(cardFileName('a/b c', 'back')).toBe('a_b_c_back.png');
  });

  it('writes a CSV of serial and url with quoting where needed', () => {
    const csv = buildCsv(
      [
        { serial: 'TOMS-0001', card_uuid: 'u1' },
        { serial: 'we,ird', card_uuid: 'u2' },
      ],
      'https://x.test',
    );
    expect(csv).toBe('serial,url\r\nTOMS-0001,https://x.test/r/u1\r\n"we,ird",https://x.test/r/u2\r\n');
  });
});
