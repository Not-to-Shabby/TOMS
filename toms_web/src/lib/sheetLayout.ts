/** Points in a PDF per millimetre. */
export const PT_PER_MM = 72 / 25.4;

export interface PaperSize {
  id: string;
  label: string;
  /** Millimetres, short side first. */
  w: number;
  h: number;
}

export const PAPERS: PaperSize[] = [
  { id: 'a4', label: 'A4', w: 210, h: 297 },
  { id: 'letter', label: 'Letter', w: 215.9, h: 279.4 },
  { id: 'legal', label: 'Legal', w: 215.9, h: 355.6 },
  { id: 'a3', label: 'A3', w: 297, h: 420 },
  { id: 'a5', label: 'A5', w: 148, h: 210 },
];

export type Orientation = 'auto' | 'portrait' | 'landscape';
/** Which way the top of the card faces on the sheet: 0 up, 90 right, 180 down, 270 left. */
export type Rotation = 0 | 90 | 180 | 270;
/** The printer's two-sided setting. */
export type Flip = 'long' | 'short';
/** The fold line the sheet is turned over: vertical mirrors left and right, horizontal mirrors top and bottom. */
export type Hinge = 'vertical' | 'horizontal';

export interface SheetOptions {
  /** Paper in millimetres. Orientation is chosen separately, so the order of the two does not matter. */
  paperW: number;
  paperH: number;
  orientation: Orientation;
  cardW: number;
  cardH: number;
  /** Blank border around the whole grid. */
  margin: number;
  /** Space between neighbouring cards, which is the room for the blade when cutting. */
  gap: number;
  /** Let the layout turn the cards 90 degrees when that fits more on a sheet. */
  allowRotate: boolean;
  cropMarks: boolean;
}

export interface Slot {
  col: number;
  row: number;
  /** Millimetres from the top-left corner of the page. */
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface Mark {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

export interface SheetLayout {
  ok: true;
  pageW: number;
  pageH: number;
  landscape: boolean;
  /** True when the cards are turned 90 degrees on the sheet. */
  rotated: boolean;
  cols: number;
  rows: number;
  perSheet: number;
  slots: Slot[];
  marks: Mark[];
  /** Explains why crop marks were left out, when they were asked for. */
  markNote: string | null;
  gridX: number;
  gridY: number;
  gridW: number;
  gridH: number;
}

export interface SheetError {
  ok: false;
  error: string;
}

const EPS = 1e-6;
const MARK_OFFSET = 1;
const MARK_MAX = 4;
const MARK_MIN = 1.5;

function fit(available: number, size: number, gap: number): number {
  return Math.max(0, Math.floor((available + gap) / (size + gap) + EPS));
}

function checkOptions(o: SheetOptions): string | null {
  const nums = [o.paperW, o.paperH, o.cardW, o.cardH, o.margin, o.gap];
  if (nums.some((n) => !Number.isFinite(n))) return 'Every size must be a number.';
  if (Math.min(o.paperW, o.paperH) < 50) return 'Paper must be at least 50 mm on each side.';
  if (o.cardW < 10 || o.cardH < 10) return 'Cards must be at least 10 mm on each side.';
  if (o.margin < 0 || o.gap < 0) return 'Margin and space between cards cannot be negative.';
  return null;
}

/**
 * Places as many cards as fit on one sheet. With orientation on auto it tries portrait and landscape,
 * with the cards upright and (when allowed) turned 90 degrees, and keeps whichever fits the most.
 * Ties prefer upright cards and portrait paper.
 */
export function computeSheet(o: SheetOptions): SheetLayout | SheetError {
  const problem = checkOptions(o);
  if (problem) return { ok: false, error: problem };

  const short = Math.min(o.paperW, o.paperH);
  const long = Math.max(o.paperW, o.paperH);
  const candidates: { landscape: boolean; rotated: boolean }[] = [];
  for (const rotated of o.allowRotate ? [false, true] : [false]) {
    for (const landscape of [false, true]) {
      if (o.orientation === 'portrait' && landscape) continue;
      if (o.orientation === 'landscape' && !landscape) continue;
      candidates.push({ landscape, rotated });
    }
  }

  let best: { landscape: boolean; rotated: boolean; cols: number; rows: number } | null = null;
  for (const c of candidates) {
    const pageW = c.landscape ? long : short;
    const pageH = c.landscape ? short : long;
    const cw = c.rotated ? o.cardH : o.cardW;
    const ch = c.rotated ? o.cardW : o.cardH;
    const cols = fit(pageW - 2 * o.margin, cw, o.gap);
    const rows = fit(pageH - 2 * o.margin, ch, o.gap);
    if (cols * rows > (best ? best.cols * best.rows : 0)) best = { ...c, cols, rows };
  }
  if (!best) {
    return { ok: false, error: 'The card does not fit on this paper with these margins. Use a larger paper, a smaller margin or a smaller card.' };
  }

  const pageW = best.landscape ? long : short;
  const pageH = best.landscape ? short : long;
  const cw = best.rotated ? o.cardH : o.cardW;
  const ch = best.rotated ? o.cardW : o.cardH;
  const gridW = best.cols * cw + (best.cols - 1) * o.gap;
  const gridH = best.rows * ch + (best.rows - 1) * o.gap;
  const gridX = (pageW - gridW) / 2;
  const gridY = (pageH - gridH) / 2;

  const slots: Slot[] = [];
  for (let row = 0; row < best.rows; row++) {
    for (let col = 0; col < best.cols; col++) {
      slots.push({ col, row, x: gridX + col * (cw + o.gap), y: gridY + row * (ch + o.gap), w: cw, h: ch });
    }
  }

  const room = Math.min(gridX, gridY) - MARK_OFFSET;
  const markLen = Math.min(MARK_MAX, room);
  const marks: Mark[] = [];
  let markNote: string | null = null;
  if (o.cropMarks) {
    if (markLen < MARK_MIN) {
      markNote = 'The border is too small for crop marks. Make the margin at least 4 mm, or leave the marks off.';
    } else {
      const xs = new Set<number>();
      const ys = new Set<number>();
      for (const s of slots) {
        xs.add(Math.round(s.x * 1000) / 1000);
        xs.add(Math.round((s.x + s.w) * 1000) / 1000);
        ys.add(Math.round(s.y * 1000) / 1000);
        ys.add(Math.round((s.y + s.h) * 1000) / 1000);
      }
      const top = gridY - MARK_OFFSET;
      const bottom = gridY + gridH + MARK_OFFSET;
      const left = gridX - MARK_OFFSET;
      const right = gridX + gridW + MARK_OFFSET;
      for (const x of xs) {
        marks.push({ x1: x, y1: top, x2: x, y2: top - markLen });
        marks.push({ x1: x, y1: bottom, x2: x, y2: bottom + markLen });
      }
      for (const y of ys) {
        marks.push({ x1: left, y1: y, x2: left - markLen, y2: y });
        marks.push({ x1: right, y1: y, x2: right + markLen, y2: y });
      }
    }
  }

  return {
    ok: true,
    pageW,
    pageH,
    landscape: best.landscape,
    rotated: best.rotated,
    cols: best.cols,
    rows: best.rows,
    perSheet: best.cols * best.rows,
    slots,
    marks,
    markNote,
    gridX,
    gridY,
    gridW,
    gridH,
  };
}

export function sheetsNeeded(cardCount: number, perSheet: number): number {
  return perSheet > 0 ? Math.ceil(cardCount / perSheet) : 0;
}

/** The fold line when the sheet is turned over, given the printer's flip setting and the page shape. */
export function hingeFor(flip: Flip, landscape: boolean): Hinge {
  if (flip === 'long') return landscape ? 'horizontal' : 'vertical';
  return landscape ? 'vertical' : 'horizontal';
}

/** Where on the back of the sheet the card at (col, row) on the front ends up. */
export function mirrorSlot(col: number, row: number, cols: number, rows: number, hinge: Hinge): { col: number; row: number } {
  return hinge === 'vertical' ? { col: cols - 1 - col, row } : { col, row: rows - 1 - row };
}

/**
 * How the back design must be turned so that its top is the same edge of the card as the top of the front
 * (the card turns over like a page). The sheet is read upright on both sides.
 */
export function backRotation(front: Rotation, hinge: Hinge): Rotation {
  if (hinge === 'vertical') {
    if (front === 90) return 270;
    if (front === 270) return 90;
    return front;
  }
  if (front === 0) return 180;
  if (front === 180) return 0;
  return front;
}

/**
 * SVG transform that places a card drawn upright in its own box (millimetres, y down, origin at its
 * top-left) into its slot on the page, turned the way the top of the card should face.
 */
export function svgSlotTransform(rotation: Rotation, slot: Slot): string {
  switch (rotation) {
    case 0:
      return `translate(${slot.x} ${slot.y})`;
    case 90:
      return `translate(${slot.x + slot.w} ${slot.y}) rotate(90)`;
    case 180:
      return `translate(${slot.x + slot.w} ${slot.y + slot.h}) rotate(180)`;
    case 270:
      return `translate(${slot.x} ${slot.y + slot.h}) rotate(270)`;
  }
}

export interface PlacedCard {
  /** Index into the list of cards being printed. */
  index: number;
  slot: Slot;
  rotation: Rotation;
}

export interface PlannedPage {
  /** Zero-based sheet number. With a back design each sheet has a front page and a back page. */
  sheet: number;
  side: 'front' | 'back';
  items: PlacedCard[];
}

/**
 * Decides which card goes in which slot on which page. Used by both the PDF and the on-screen preview so
 * they cannot disagree. With a back design the pages alternate front, back, and each back sits exactly
 * behind its own front.
 */
export function planSheets(layout: SheetLayout, cardCount: number, flip: Flip, withBack: boolean): PlannedPage[] {
  const frontRotation: Rotation = layout.rotated ? 90 : 0;
  const hinge = hingeFor(flip, layout.landscape);
  const backTurn = backRotation(frontRotation, hinge);
  const pages: PlannedPage[] = [];
  const sheets = sheetsNeeded(cardCount, layout.perSheet);
  for (let sheet = 0; sheet < sheets; sheet++) {
    const first = sheet * layout.perSheet;
    const count = Math.min(layout.perSheet, cardCount - first);
    pages.push({
      sheet,
      side: 'front',
      items: Array.from({ length: count }, (_, k) => ({ index: first + k, slot: layout.slots[k], rotation: frontRotation })),
    });
    if (withBack) {
      pages.push({
        sheet,
        side: 'back',
        items: Array.from({ length: count }, (_, k) => {
          const at = mirrorSlot(layout.slots[k].col, layout.slots[k].row, layout.cols, layout.rows, hinge);
          return { index: first + k, slot: layout.slots[at.row * layout.cols + at.col], rotation: backTurn };
        }),
      });
    }
  }
  return pages;
}

export type Matrix = [number, number, number, number, number, number];

/**
 * PDF transformation matrix that maps a card drawn upright with its origin at its bottom-left corner
 * (units are points, y up) onto its slot on a page, turned the way the top of the card should face.
 */
export function cardMatrix(rotation: Rotation, slot: Slot, pageH: number): Matrix {
  const bx = slot.x * PT_PER_MM;
  const by = (pageH - slot.y - slot.h) * PT_PER_MM;
  const sw = slot.w * PT_PER_MM;
  const sh = slot.h * PT_PER_MM;
  switch (rotation) {
    case 0:
      return [1, 0, 0, 1, bx, by];
    case 90:
      return [0, -1, 1, 0, bx, by + sh];
    case 180:
      return [-1, 0, 0, -1, bx + sw, by + sh];
    case 270:
      return [0, 1, -1, 0, bx + sw, by];
  }
}
