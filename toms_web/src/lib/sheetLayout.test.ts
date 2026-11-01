import { describe, expect, it } from 'vitest';
import {
  PAPERS,
  PT_PER_MM,
  backRotation,
  cardMatrix,
  computeSheet,
  hingeFor,
  mirrorSlot,
  planSheets,
  sheetsNeeded,
  svgSlotTransform,
  type SheetLayout,
  type SheetOptions,
} from './sheetLayout';

const A4 = PAPERS.find((p) => p.id === 'a4')!;
const LETTER = PAPERS.find((p) => p.id === 'letter')!;

const base = (over: Partial<SheetOptions> = {}): SheetOptions => ({
  paperW: A4.w,
  paperH: A4.h,
  orientation: 'auto',
  cardW: 85.6,
  cardH: 54,
  margin: 10,
  gap: 4,
  allowRotate: true,
  cropMarks: false,
  ...over,
});

const ok = (o: SheetOptions): SheetLayout => {
  const r = computeSheet(o);
  if (!r.ok) throw new Error(r.error);
  return r;
};

describe('how many cards fit', () => {
  it('fits 2 x 4 credit-size cards on A4 with a 10 mm margin and 4 mm gap', () => {
    // width: (190 + 4) / (85.6 + 4) = 2.16 -> 2 ; height: (277 + 4) / (54 + 4) = 4.84 -> 4
    const l = ok(base({ allowRotate: false, orientation: 'portrait' }));
    expect([l.cols, l.rows, l.perSheet]).toEqual([2, 4, 8]);
  });

  it('finds 9 per A4 sheet by using landscape paper, and prefers upright cards on a tie', () => {
    // Portrait paper: upright 2 x 4 = 8, turned 3 x 3 = 9.
    // Landscape paper: upright 3 x 3 = 9 (the same layout seen from the side). Upright wins the tie.
    const l = ok(base());
    expect(l.perSheet).toBe(9);
    expect(l.landscape).toBe(true);
    expect(l.rotated).toBe(false);
  });

  it('turns the cards on portrait paper to get 9 when landscape is not allowed', () => {
    const l = ok(base({ orientation: 'portrait' }));
    expect(l.perSheet).toBe(9);
    expect(l.rotated).toBe(true);
    expect(l.landscape).toBe(false);
  });

  it('keeps cards upright on portrait paper when turning is switched off', () => {
    const l = ok(base({ allowRotate: false, orientation: 'portrait' }));
    expect(l.rotated).toBe(false);
    expect(l.perSheet).toBe(8);
  });

  it('fits 8 per Letter sheet, either upright or turned', () => {
    // Letter 215.9 x 279.4, margin 10, gap 4. Portrait upright: 2 x 4 = 8; turned: 3 x 2 = 6.
    // Landscape upright: 2 x 3 = 6; turned: 4 x 2 = 8.
    const portrait = ok(base({ paperW: LETTER.w, paperH: LETTER.h, allowRotate: false, orientation: 'portrait' }));
    expect(portrait.perSheet).toBe(8);
    const best = ok(base({ paperW: LETTER.w, paperH: LETTER.h }));
    expect(best.perSheet).toBe(8);
  });

  it('chooses landscape paper when it fits more', () => {
    // Card 100 x 40 on A4 with margin 5 and gap 2: portrait upright 2 x 7 = 14, landscape 2 x 5... check by comparison
    const auto = ok(base({ cardW: 40, cardH: 100, margin: 5, gap: 2, allowRotate: false }));
    const portrait = ok(base({ cardW: 40, cardH: 100, margin: 5, gap: 2, allowRotate: false, orientation: 'portrait' }));
    const landscape = ok(base({ cardW: 40, cardH: 100, margin: 5, gap: 2, allowRotate: false, orientation: 'landscape' }));
    expect(auto.perSheet).toBe(Math.max(portrait.perSheet, landscape.perSheet));
  });

  it('respects a forced orientation even when the other fits more', () => {
    const l = ok(base({ orientation: 'landscape', allowRotate: false }));
    expect(l.landscape).toBe(true);
    expect(l.pageW).toBe(A4.h);
    expect(l.pageH).toBe(A4.w);
  });

  it('does not depend on which paper side was typed first', () => {
    const a = ok(base({ paperW: 210, paperH: 297 }));
    const b = ok(base({ paperW: 297, paperH: 210 }));
    expect(b.perSheet).toBe(a.perSheet);
    expect(b.landscape).toBe(a.landscape);
  });

  it('counts a card that exactly fills the width, despite rounding', () => {
    // three 60 mm cards and two 5 mm gaps are 190 mm, plus two 10 mm margins = a 210 mm wide page
    const l = ok(base({ paperW: 210, paperH: 150, orientation: 'landscape', cardW: 60, cardH: 100, gap: 5, allowRotate: false }));
    expect(l.pageW).toBe(210);
    expect(l.cols).toBe(3);
  });

  it('works with no gap at all', () => {
    const l = ok(base({ gap: 0, margin: 0, allowRotate: false, orientation: 'portrait', cardW: 70, cardH: 99 }));
    expect([l.cols, l.rows]).toEqual([3, 3]);
  });
});

describe('placing the cards', () => {
  it('centres the grid and keeps equal gaps', () => {
    const l = ok(base({ allowRotate: false, orientation: 'portrait' }));
    const first = l.slots[0];
    const last = l.slots[l.slots.length - 1];
    expect(first.x).toBeCloseTo(A4.w - (last.x + last.w), 6);
    expect(first.y).toBeCloseTo(A4.h - (last.y + last.h), 6);
    expect(l.slots[1].x - (first.x + first.w)).toBeCloseTo(4, 6);
    expect(l.slots[2].y - (first.y + first.h)).toBeCloseTo(4, 6);
  });

  it('keeps every card inside the margins', () => {
    for (const margin of [0, 5, 10, 20]) {
      const l = ok(base({ margin }));
      for (const s of l.slots) {
        expect(s.x).toBeGreaterThanOrEqual(margin - 1e-6);
        expect(s.y).toBeGreaterThanOrEqual(margin - 1e-6);
        expect(s.x + s.w).toBeLessThanOrEqual(l.pageW - margin + 1e-6);
        expect(s.y + s.h).toBeLessThanOrEqual(l.pageH - margin + 1e-6);
      }
    }
  });

  it('never overlaps two cards', () => {
    const l = ok(base({ gap: 3 }));
    for (let i = 0; i < l.slots.length; i++) {
      for (let j = i + 1; j < l.slots.length; j++) {
        const a = l.slots[i];
        const b = l.slots[j];
        const apart = a.x + a.w <= b.x + 1e-6 || b.x + b.w <= a.x + 1e-6 || a.y + a.h <= b.y + 1e-6 || b.y + b.h <= a.y + 1e-6;
        expect(apart).toBe(true);
      }
    }
  });

  it('reports the sheets needed for a batch', () => {
    expect(sheetsNeeded(50, 9)).toBe(6);
    expect(sheetsNeeded(9, 9)).toBe(1);
    expect(sheetsNeeded(10, 9)).toBe(2);
    expect(sheetsNeeded(0, 9)).toBe(0);
  });
});

describe('cut marks', () => {
  it('adds marks at every card edge, outside the cards, when asked', () => {
    const l = ok(base({ cropMarks: true, allowRotate: false, orientation: 'portrait' }));
    // 2 columns -> 4 vertical edges x 2 ends; 4 rows -> 8 horizontal edges x 2 ends
    expect(l.marks.length).toBe(4 * 2 + 8 * 2);
    for (const m of l.marks) {
      const insideAnyCard = l.slots.some((s) => {
        const px = Math.min(m.x1, m.x2) > s.x && Math.max(m.x1, m.x2) < s.x + s.w;
        const py = Math.min(m.y1, m.y2) > s.y && Math.max(m.y1, m.y2) < s.y + s.h;
        return px && py;
      });
      expect(insideAnyCard).toBe(false);
    }
  });

  it('stays off by default', () => {
    expect(ok(base()).marks).toEqual([]);
  });

  it('draws marks in the space left after the grid is centred, even with a small margin', () => {
    const l = ok(base({ cropMarks: true, margin: 2, allowRotate: false, orientation: 'portrait' }));
    expect(l.marks.length).toBeGreaterThan(0);
    expect(l.markNote).toBeNull();
  });

  it('leaves the marks out and says why when the cards fill the page edge to edge', () => {
    // 2 x 2 cards of 60 x 40 with no gap exactly fill 120 x 80; a 2 mm margin leaves 1 mm for marks.
    const l = ok(base({ paperW: 124, paperH: 84, orientation: 'landscape', cardW: 60, cardH: 40, gap: 0, margin: 2, allowRotate: false, cropMarks: true }));
    expect([l.cols, l.rows]).toEqual([2, 2]);
    expect(l.marks).toEqual([]);
    expect(l.markNote).toMatch(/margin at least 4 mm/);
  });
});

describe('invalid input', () => {
  it('explains a card that is bigger than the paper', () => {
    const r = computeSheet(base({ cardW: 300, cardH: 300 }));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toMatch(/does not fit/);
  });

  it('rejects numbers that make no sense', () => {
    expect(computeSheet(base({ cardW: Number.NaN })).ok).toBe(false);
    expect(computeSheet(base({ margin: -1 })).ok).toBe(false);
    expect(computeSheet(base({ gap: -1 })).ok).toBe(false);
    expect(computeSheet(base({ cardW: 5 })).ok).toBe(false);
    expect(computeSheet(base({ paperW: 20 })).ok).toBe(false);
  });
});

describe('two-sided printing', () => {
  it('turns the sheet over a vertical line for a long-edge flip on portrait paper', () => {
    expect(hingeFor('long', false)).toBe('vertical');
    expect(hingeFor('short', false)).toBe('horizontal');
  });

  it('turns the sheet over a horizontal line for a long-edge flip on landscape paper', () => {
    expect(hingeFor('long', true)).toBe('horizontal');
    expect(hingeFor('short', true)).toBe('vertical');
  });

  it('puts the back of a card behind its front', () => {
    expect(mirrorSlot(0, 0, 3, 3, 'vertical')).toEqual({ col: 2, row: 0 });
    expect(mirrorSlot(1, 2, 3, 3, 'vertical')).toEqual({ col: 1, row: 2 });
    expect(mirrorSlot(0, 0, 3, 3, 'horizontal')).toEqual({ col: 0, row: 2 });
  });

  it('mirroring twice returns to the same slot', () => {
    for (const hinge of ['vertical', 'horizontal'] as const) {
      const once = mirrorSlot(1, 0, 3, 4, hinge);
      expect(mirrorSlot(once.col, once.row, 3, 4, hinge)).toEqual({ col: 1, row: 0 });
    }
  });

  it('keeps an upright front upright on the back when the sheet turns like a page', () => {
    expect(backRotation(0, 'vertical')).toBe(0);
    expect(backRotation(0, 'horizontal')).toBe(180);
  });

  it('swaps the two sideways turns across a vertical hinge', () => {
    expect(backRotation(90, 'vertical')).toBe(270);
    expect(backRotation(270, 'vertical')).toBe(90);
    expect(backRotation(90, 'horizontal')).toBe(90);
  });

  it('turning twice is the identity', () => {
    for (const hinge of ['vertical', 'horizontal'] as const) {
      for (const r of [0, 90, 180, 270] as const) {
        expect(backRotation(backRotation(r, hinge), hinge)).toBe(r);
      }
    }
  });
});

describe('placing a card on the page', () => {
  const slot = { col: 0, row: 0, x: 10, y: 20, w: 60, h: 40 };
  const pageH = 100;
  const apply = (m: number[], x: number, y: number): [number, number] => [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];
  const mm = (v: number) => v * PT_PER_MM;

  it('maps an upright card onto its slot', () => {
    const m = cardMatrix(0, slot, pageH);
    const [x0, y0] = apply(m, 0, 0);
    const [x1, y1] = apply(m, mm(60), mm(40));
    expect(x0).toBeCloseTo(mm(10), 6);
    expect(y0).toBeCloseTo(mm(100 - 20 - 40), 6);
    expect(x1).toBeCloseTo(mm(70), 6);
    expect(y1).toBeCloseTo(mm(100 - 20), 6);
  });

  it('keeps all four corners of a turned card inside its slot box', () => {
    const turned = { ...slot, w: 40, h: 60 };
    for (const r of [90, 270] as const) {
      const m = cardMatrix(r, turned, pageH);
      for (const [cx, cy] of [[0, 0], [mm(60), 0], [0, mm(40)], [mm(60), mm(40)]]) {
        const [x, y] = apply(m, cx, cy);
        expect(x).toBeGreaterThanOrEqual(mm(10) - 1e-6);
        expect(x).toBeLessThanOrEqual(mm(50) + 1e-6);
        expect(y).toBeGreaterThanOrEqual(mm(100 - 20 - 60) - 1e-6);
        expect(y).toBeLessThanOrEqual(mm(100 - 20) + 1e-6);
      }
    }
  });

  it('puts the top edge of the card on the right side after each turn', () => {
    // Top-left of the card is (0, cardH); a point on its top edge is (w/2, cardH).
    const turned = { ...slot, w: 40, h: 60 };
    const top = (r: 0 | 90 | 180 | 270, s = slot) => apply(cardMatrix(r, s, pageH), mm(30), mm(40));
    const centre = (s: typeof slot) => [mm(s.x + s.w / 2), mm(pageH - s.y - s.h / 2)];
    // Turned 90 degrees clockwise: the top edge faces the right of the page.
    expect(top(90, turned)[0]).toBeGreaterThan(centre(turned)[0]);
    // Turned 270: faces the left.
    expect(top(270, turned)[0]).toBeLessThan(centre(turned)[0]);
    // Turned 180: faces down the page (smaller y in PDF coordinates).
    expect(top(180)[1]).toBeLessThan(centre(slot)[1]);
    // Upright: faces up.
    expect(top(0)[1]).toBeGreaterThan(centre(slot)[1]);
  });

  it('gives an svg transform for each turn', () => {
    expect(svgSlotTransform(0, slot)).toBe('translate(10 20)');
    expect(svgSlotTransform(90, slot)).toBe('translate(70 20) rotate(90)');
    expect(svgSlotTransform(180, slot)).toBe('translate(70 60) rotate(180)');
    expect(svgSlotTransform(270, slot)).toBe('translate(10 60) rotate(270)');
  });
});

describe('planning the pages', () => {
  const layout = ok(base({ allowRotate: false, orientation: 'portrait' })); // 2 x 4 = 8 per sheet

  it('lists one page per sheet when there is no back design', () => {
    const plan = planSheets(layout, 20, 'long', false);
    expect(plan.map((p) => [p.sheet, p.side, p.items.length])).toEqual([[0, 'front', 8], [1, 'front', 8], [2, 'front', 4]]);
  });

  it('alternates front and back pages when there is a back design', () => {
    const plan = planSheets(layout, 10, 'long', true);
    expect(plan.map((p) => `${p.sheet}${p.side[0]}`)).toEqual(['0f', '0b', '1f', '1b']);
  });

  it('numbers the cards in order across sheets and slots', () => {
    const plan = planSheets(layout, 10, 'long', false);
    expect(plan[0].items.map((i) => i.index)).toEqual([0, 1, 2, 3, 4, 5, 6, 7]);
    expect(plan[1].items.map((i) => i.index)).toEqual([8, 9]);
    expect(plan[0].items[0].slot).toBe(layout.slots[0]);
  });

  it('puts the back of every card behind its front, across a vertical hinge', () => {
    const [front, back] = planSheets(layout, 8, 'long', true);
    for (const f of front.items) {
      const b = back.items.find((x) => x.index === f.index)!;
      // same row, mirrored column; the centres line up once the sheet is turned over
      expect(b.slot.row).toBe(f.slot.row);
      expect(b.slot.col).toBe(layout.cols - 1 - f.slot.col);
      expect(b.slot.x + b.slot.w / 2).toBeCloseTo(layout.pageW - (f.slot.x + f.slot.w / 2), 6);
      expect(b.slot.y).toBeCloseTo(f.slot.y, 6);
    }
  });

  it('puts the back behind the front across a horizontal hinge with a short-edge flip', () => {
    const [front, back] = planSheets(layout, 8, 'short', true);
    for (const f of front.items) {
      const b = back.items.find((x) => x.index === f.index)!;
      expect(b.slot.col).toBe(f.slot.col);
      expect(b.slot.y + b.slot.h / 2).toBeCloseTo(layout.pageH - (f.slot.y + f.slot.h / 2), 6);
      expect(b.rotation).toBe(180);
    }
  });

  it('plans nothing for an empty batch', () => {
    expect(planSheets(layout, 0, 'long', true)).toEqual([]);
  });

  it('only fills the slots it needs on the last sheet', () => {
    const plan = planSheets(layout, 3, 'long', true);
    expect(plan[0].items).toHaveLength(3);
    expect(plan[1].items).toHaveLength(3);
  });
});
