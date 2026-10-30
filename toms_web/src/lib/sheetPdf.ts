import {
  PDFDocument,
  StandardFonts,
  concatTransformationMatrix,
  popGraphicsState,
  pushGraphicsState,
  rgb,
  type PDFFont,
  type PDFImage,
  type PDFPage,
} from 'pdf-lib';
import QRCode from 'qrcode';
import { QR_QUIET_MODULES, cardUrl, type Placement } from './cardDesign';
import { PT_PER_MM, cardMatrix, planSheets, type Flip, type Rotation, type SheetLayout, type Slot } from './sheetLayout';

export interface PdfArt {
  /** The design as PNG or JPEG bytes. */
  bytes: Uint8Array;
  kind: 'png' | 'jpg';
  /** Pixel size of the design. Positions are shares of this, so any size works. */
  width: number;
  height: number;
  /** Called when the original bytes cannot be embedded, to get a plain PNG of the design instead. */
  rasterize?: () => Promise<Uint8Array>;
}

export interface SheetSide {
  art: PdfArt;
  placement: Placement | null;
}

export interface SheetCard {
  serial: string;
  card_uuid: string;
}

export interface SheetPdfInput {
  layout: SheetLayout;
  /** Card size in millimetres, as drawn on the sheet before any turning. */
  cardW: number;
  cardH: number;
  cards: SheetCard[];
  baseUrl: string;
  front: SheetSide;
  back?: SheetSide | null;
  flip: Flip;
  onProgress?: (done: number, total: number) => void;
}

function hexToRgb(hex: string) {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  const n = m ? parseInt(m[1], 16) : 0x111111;
  return rgb(((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255);
}

/** One path for the whole QR code. Joining dark modules into runs keeps it small and seam-free. */
export function qrPath(text: string): { path: string; modules: number } {
  const qr = QRCode.create(text, { errorCorrectionLevel: 'M' });
  const n = qr.modules.size;
  let path = '';
  for (let y = 0; y < n; y++) {
    let x = 0;
    while (x < n) {
      if (!qr.modules.get(x, y)) {
        x++;
        continue;
      }
      let run = 1;
      while (x + run < n && qr.modules.get(x + run, y)) run++;
      path += `M${x} ${y}h${run}v1h-${run}z`;
      x += run;
    }
  }
  return { path, modules: n };
}

async function embed(doc: PDFDocument, art: PdfArt): Promise<PDFImage> {
  try {
    return art.kind === 'png' ? await doc.embedPng(art.bytes) : await doc.embedJpg(art.bytes);
  } catch {
    if (!art.rasterize) throw new Error('This image cannot be placed in a PDF. Export it from Canva again as a standard PNG or JPG.');
    return doc.embedPng(await art.rasterize());
  }
}

interface DrawContext {
  page: PDFPage;
  pageH: number;
  cardWpt: number;
  cardHpt: number;
  font: PDFFont;
  baseUrl: string;
}

/** Draws one card in its slot: the artwork, then (if this side carries them) the QR code and serial. */
function drawCard(
  ctx: DrawContext,
  image: PDFImage,
  side: SheetSide,
  card: SheetCard,
  slot: Slot,
  rotation: Rotation,
) {
  const { page, cardWpt, cardHpt } = ctx;
  const m = cardMatrix(rotation, slot, ctx.pageH);
  page.pushOperators(pushGraphicsState(), concatTransformationMatrix(...m));

  page.drawImage(image, { x: 0, y: 0, width: cardWpt, height: cardHpt });

  const p = side.placement;
  if (p) {
    const pxToPt = cardWpt / side.art.width;
    const qrSide = p.qrSize * cardWpt;
    const qrLeft = p.qrX * cardWpt;
    const qrTop = cardHpt - p.qrY * cardHpt;
    page.drawRectangle({ x: qrLeft, y: qrTop - qrSide, width: qrSide, height: qrSide, color: rgb(1, 1, 1) });
    const { path, modules } = qrPath(cardUrl(ctx.baseUrl, card.card_uuid));
    const unit = qrSide / (modules + 2 * QR_QUIET_MODULES);
    page.drawSvgPath(path, {
      x: qrLeft + QR_QUIET_MODULES * unit,
      y: qrTop - QR_QUIET_MODULES * unit,
      scale: unit,
      color: rgb(0, 0, 0),
      borderWidth: 0,
    });

    const size = Math.max(8, Math.round(p.textSize * side.art.width)) * pxToPt;
    const width = ctx.font.widthOfTextAtSize(card.serial, size);
    const cx = p.textX * cardWpt;
    const cy = cardHpt - p.textY * cardHpt;
    page.drawText(card.serial, { x: cx - width / 2, y: cy - size * 0.32, size, font: ctx.font, color: hexToRgb(p.textColor) });
  }
  page.pushOperators(popGraphicsState());
}

function drawMarks(page: PDFPage, layout: SheetLayout) {
  for (const m of layout.marks) {
    page.drawLine({
      start: { x: m.x1 * PT_PER_MM, y: (layout.pageH - m.y1) * PT_PER_MM },
      end: { x: m.x2 * PT_PER_MM, y: (layout.pageH - m.y2) * PT_PER_MM },
      thickness: 0.3,
      color: rgb(0, 0, 0),
    });
  }
}

function drawFooter(page: PDFPage, layout: SheetLayout, font: PDFFont, text: string) {
  const room = layout.pageH - (layout.gridY + layout.gridH);
  if (room < 9) return;
  const size = 6;
  const width = font.widthOfTextAtSize(text, size);
  page.drawText(text, { x: (layout.pageW * PT_PER_MM - width) / 2, y: 2.5 * PT_PER_MM, size, font, color: rgb(0.45, 0.45, 0.45) });
}

/**
 * Builds the print PDF. Each design is stored once and reused for every card, and each QR code is drawn
 * as vector shapes, so the file stays small and the codes stay sharp at any print size.
 * With a back design the pages alternate front, back, front, back so they print double-sided.
 */
export async function buildSheetPdf(input: SheetPdfInput): Promise<Uint8Array> {
  const { layout, cards } = input;
  const doc = await PDFDocument.create();
  doc.setTitle('TOMS cards');
  doc.setCreator('TOMS');
  const font = await doc.embedFont(StandardFonts.CourierBold);
  const labelFont = await doc.embedFont(StandardFonts.Helvetica);

  const frontImage = await embed(doc, input.front.art);
  const backImage = input.back ? await embed(doc, input.back.art) : null;

  const ctx = (page: PDFPage): DrawContext => ({
    page,
    pageH: layout.pageH,
    cardWpt: input.cardW * PT_PER_MM,
    cardHpt: input.cardH * PT_PER_MM,
    font,
    baseUrl: input.baseUrl,
  });

  const plan = planSheets(layout, cards.length, input.flip, !!input.back);
  const sheets = Math.max(...plan.map((p) => p.sheet), -1) + 1;
  const pageSize: [number, number] = [layout.pageW * PT_PER_MM, layout.pageH * PT_PER_MM];

  for (const planned of plan) {
    const onSheet = planned.items.map((it) => cards[it.index]);
    const label = `${onSheet[0].serial} to ${onSheet[onSheet.length - 1].serial}  |  sheet ${planned.sheet + 1} of ${sheets}  |  ${planned.side}`;
    const page = doc.addPage(pageSize);
    const isBack = planned.side === 'back' && input.back && backImage;
    const side = isBack ? (input.back as SheetSide) : input.front;
    const image = isBack ? (backImage as PDFImage) : frontImage;
    for (const it of planned.items) drawCard(ctx(page), image, side, cards[it.index], it.slot, it.rotation);
    drawMarks(page, layout);
    drawFooter(page, layout, labelFont, label);
    input.onProgress?.(planned.sheet + 1, sheets);
    if (planned.side === 'back' || !input.back) await new Promise((r) => setTimeout(r, 0));
  }

  return doc.save();
}
