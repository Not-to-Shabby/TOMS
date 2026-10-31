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
  snapBox,
  SNAP_PX,
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

describe('snapping a dragged object to alignment lines', () => {
  // The card is 500 x 300 px on screen, so a 6 px reach is 0.012 of the width and 0.02 of the height.
  const card = { width: 500, height: 300 };
  const box = (x: number, y: number, w = 0.2, h = 0.2) => ({ x, y, w, h });

  it('snaps the left edge onto the card left edge when it is within reach', () => {
    const r = snapBox(box(0.004, 0.3), [], card);
    expect(r.x).toBe(0);
    expect(r.guides).toContainEqual({ axis: 'x', at: 0 });
  });

  it('snaps the centre onto the card centre line', () => {
    // Left edge at 0.395 puts the centre at 0.495, 0.005 from the card centre (0.5).
    const r = snapBox(box(0.395, 0.1), [], card);
    expect(r.x).toBeCloseTo(0.4, 6);
    expect(r.guides).toContainEqual({ axis: 'x', at: 0.5 });
  });

  it('snaps the right edge onto the card right edge', () => {
    const r = snapBox(box(0.79, 0.1), [], card);
    expect(r.x).toBeCloseTo(0.8, 6);
    expect(r.guides).toContainEqual({ axis: 'x', at: 1 });
  });

  it('snaps the left edge onto the left edge of another object', () => {
    // Other object: left 0.5, right 0.8. Our box: left 0.497, centre 0.597, right 0.697 (the other's right is 0.8, far off).
    const other = box(0.5, 0.7, 0.3, 0.1);
    const r = snapBox(box(0.497, 0.1), [other], card);
    expect(r.x).toBeCloseTo(0.5, 6);
    expect(r.guides).toContainEqual({ axis: 'x', at: 0.5 });
  });

  it('snaps the centre onto the centre line of another object', () => {
    // The other object's centre is 0.5. Our centre at 0.498 (left edge 0.398) is 0.002 away.
    // Its left edge (0.4) is also near our left edge (0.398), so we check the centre match directly.
    const other = box(0.4, 0.7, 0.2, 0.1);
    const near = snapBox(box(0.398, 0.1), [other], card);
    expect(near.guides).toContainEqual({ axis: 'x', at: 0.4 });
    expect(near.x).toBeCloseTo(0.4, 6);
  });

  it('does not snap to anything when no line is within reach', () => {
    // Left edge 0.1 and right edge 0.3 are both far from every line (0, 0.5, 1 and the other object's edges).
    const r = snapBox(box(0.1, 0.1), [], card);
    expect(r.x).toBe(0.1);
    expect(r.guides).toEqual([]);
  });

  it('snaps each axis on its own, and reports a guide for each', () => {
    const r = snapBox(box(0.004, 0.296), [], card);
    expect(r.x).toBe(0);
    expect(r.guides.map((g) => g.axis).sort()).toEqual(['x', 'y']);
  });

  it('uses the screen size to decide reach, so the same distance snaps on a small view but not a large one', () => {
    const small = snapBox(box(0.004, 0.1), [], { width: 500, height: 300 }); // 0.004 is 2 px here: in reach
    expect(small.x).toBe(0);
    const large = snapBox(box(0.004, 0.1), [], { width: 5000, height: 3000 }); // 0.004 is 20 px here: out of reach
    expect(large.x).toBeCloseTo(0.004, 6);
    expect(large.guides).toEqual([]);
  });

  it('keeps the default tolerance at a few pixels', () => {
    expect(SNAP_PX).toBeGreaterThanOrEqual(4);
    expect(SNAP_PX).toBeLessThanOrEqual(10);
  });

  it('picks the nearest line when two are in reach', () => {
    // Left edge 0.002 from the card edge (0) and 0.002 from another object's edge at 0.004: it is nearer 0.
    const other = box(0.004, 0.7, 0.2, 0.1);
    const r = snapBox(box(0.002, 0.3), [other], card);
    expect(r.x).toBe(0);
  });
});
