export interface BatchRule {
  prefix: string;
  start: number;
  digits: number;
}

export function formatSerial({ prefix, start, digits }: BatchRule, offset: number): string {
  return `${prefix}${String(start + offset).padStart(digits, '0')}`;
}

export function serialRange(rule: BatchRule, count: number): { first: string; last: string } {
  return { first: formatSerial(rule, 0), last: formatSerial(rule, Math.max(count, 1) - 1) };
}

/** Same rules the backend enforces, so the form can refuse before a request is sent. */
export function validateBatch(rule: BatchRule, count: number): string | null {
  if (!Number.isInteger(count) || count < 1 || count > 500) return 'Count must be a whole number from 1 to 500.';
  if (!/^[A-Za-z0-9_-]*$/.test(rule.prefix) || rule.prefix.length > 20) {
    return 'Prefix can use letters, digits, - and _ only (20 characters at most).';
  }
  if (!Number.isInteger(rule.start) || rule.start < 0) return 'Start number must be 0 or more.';
  if (!Number.isInteger(rule.digits) || rule.digits < 1 || rule.digits > 10) return 'Digits must be from 1 to 10.';
  return null;
}

/** Blank modules a QR code needs on every side to be read reliably. The placed box includes them. */
export const QR_QUIET_MODULES = 4;

export function cardUrl(baseUrl: string, cardUuid: string): string {
  return `${baseUrl.replace(/\/+$/, '')}/r/${cardUuid}`;
}

/** Everything is stored as a share of the image so it holds at any resolution the shop scales to. */
export interface Placement {
  /** Left edge of the QR square, 0..1 of image width. */
  qrX: number;
  /** Top edge of the QR square, 0..1 of image height. */
  qrY: number;
  /** Side of the QR square, 0..1 of image width. */
  qrSize: number;
  /** Centre of the serial text, 0..1 of image width / height. */
  textX: number;
  textY: number;
  /** Text height, 0..1 of image width. */
  textSize: number;
  textColor: string;
}

export const DEFAULT_PLACEMENT: Placement = {
  qrX: 0.62,
  qrY: 0.2,
  qrSize: 0.3,
  textX: 0.77,
  textY: 0.88,
  textSize: 0.045,
  textColor: '#111111',
};

const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi);

/** Keeps the QR square fully inside the image and within sensible size limits. */
export function clampPlacement(p: Placement, width: number, height: number): Placement {
  const aspect = width / height;
  const qrSize = clamp(p.qrSize, 0.08, Math.min(1, 1 / aspect));
  return {
    ...p,
    qrSize,
    qrX: clamp(p.qrX, 0, 1 - qrSize),
    qrY: clamp(p.qrY, 0, 1 - qrSize * aspect),
    textX: clamp(p.textX, 0, 1),
    textY: clamp(p.textY, 0, 1),
    textSize: clamp(p.textSize, 0.01, 0.2),
  };
}

export interface PixelPlacement {
  qrX: number;
  qrY: number;
  qrSize: number;
  textX: number;
  textY: number;
  textPx: number;
}

export function toPixels(p: Placement, width: number, height: number): PixelPlacement {
  return {
    qrX: Math.round(p.qrX * width),
    qrY: Math.round(p.qrY * height),
    qrSize: Math.round(p.qrSize * width),
    textX: Math.round(p.textX * width),
    textY: Math.round(p.textY * height),
    textPx: Math.max(8, Math.round(p.textSize * width)),
  };
}

/** Pixel position inside the preview element to a 0..1 share of it. */
export function fractionOf(clientX: number, clientY: number, rect: { left: number; top: number; width: number; height: number }) {
  return {
    x: clamp((clientX - rect.left) / rect.width, 0, 1),
    y: clamp((clientY - rect.top) / rect.height, 0, 1),
  };
}

export interface ResolutionNote {
  level: 'ok' | 'warn';
  message: string;
}

/** Real card size is chosen by the print shop, so only the pixel count can be judged here. */
export function resolutionNote(width: number, height: number, qrSize: number): ResolutionNote {
  const qrPx = Math.round(qrSize * width);
  if (width < 1000) {
    return { level: 'warn', message: `The design is only ${width} px wide. It will print soft once the shop enlarges it. Export from Canva at 300 dpi or larger.` };
  }
  if (qrPx < 150) {
    return { level: 'warn', message: `The QR is ${qrPx} px wide on this design, which is small. Make it larger so it still scans after printing.` };
  }
  return { level: 'ok', message: `${width} × ${height} px, QR ${qrPx} px. Aim for the QR to be at least 20 mm wide on the printed card.` };
}

/** Width used when an SVG reports no size of its own. Roughly a credit card at 300 dpi, doubled. */
export const SVG_FALLBACK_WIDTH = 2022;

export function outputSize(naturalWidth: number, naturalHeight: number, isSvg: boolean): { width: number; height: number } {
  if (naturalWidth > 0 && naturalHeight > 0 && !isSvg) return { width: naturalWidth, height: naturalHeight };
  const w = naturalWidth > 0 ? Math.max(naturalWidth, SVG_FALLBACK_WIDTH) : SVG_FALLBACK_WIDTH;
  const ratio = naturalWidth > 0 && naturalHeight > 0 ? naturalHeight / naturalWidth : 54 / 85.6;
  return { width: Math.round(w), height: Math.round(w * ratio) };
}

export function cardFileName(serial: string, side: 'front' | 'back'): string {
  const safe = serial.replace(/[^A-Za-z0-9_-]/g, '_') || 'card';
  return `${safe}_${side}.png`;
}

function csvCell(v: string): string {
  return /[",\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}

export function buildCsv(cards: { serial: string; card_uuid: string }[], baseUrl: string): string {
  const lines = ['serial,url', ...cards.map((c) => `${csvCell(c.serial)},${csvCell(cardUrl(baseUrl, c.card_uuid))}`)];
  return lines.join('\r\n') + '\r\n';
}

/** How close (in screen pixels) an edge must be to a target before the object snaps onto it. */
export const SNAP_PX = 6;

/** A line drawn while dragging, as a share of the card: a vertical line at x, or a horizontal line at y. */
export interface Guide {
  axis: 'x' | 'y';
  at: number;
}

export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

/**
 * Snaps a box being dragged so its edges and centre line line up with the card's edges and centre, and with the
 * other object's edges and centre. Works in shares of the card (0..1), so the tolerance is converted from screen
 * pixels with the card's displayed width and height. Snaps each axis independently, to the nearest target in reach.
 */
export function snapBox(
  box: Box,
  others: Box[],
  displayPx: { width: number; height: number },
  tolerancePx = SNAP_PX,
): { x: number; y: number; guides: Guide[] } {
  const tolX = tolerancePx / displayPx.width;
  const tolY = tolerancePx / displayPx.height;
  const targetsX = [0, 0.5, 1];
  const targetsY = [0, 0.5, 1];
  for (const o of others) {
    targetsX.push(o.x, o.x + o.w / 2, o.x + o.w);
    targetsY.push(o.y, o.y + o.h / 2, o.y + o.h);
  }

  // Each axis is decided on its own: probes and targets are both on the same axis, so a Y line can never
  // be reported as an X guide. Of the nearest match in reach, the box moves so that line lines up exactly.
  const pick = (value: number, size: number, targets: number[], tol: number) => {
    const probes = [value, value + size / 2, value + size];
    let best: { delta: number; at: number } | null = null;
    for (const p of probes) {
      for (const t of targets) {
        const d = t - p;
        if (Math.abs(d) <= tol && (best === null || Math.abs(d) < Math.abs(best.delta))) best = { delta: d, at: t };
      }
    }
    return best;
  };

  const sx = pick(box.x, box.w, targetsX, tolX);
  const sy = pick(box.y, box.h, targetsY, tolY);
  const guides: Guide[] = [];
  const x = sx ? box.x + sx.delta : box.x;
  const y = sy ? box.y + sy.delta : box.y;
  if (sx) guides.push({ axis: 'x', at: sx.at });
  if (sy) guides.push({ axis: 'y', at: sy.at });
  return { x, y, guides };
}
