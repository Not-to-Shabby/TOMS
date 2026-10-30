import JSZip from 'jszip';
import QRCode from 'qrcode';
import { QR_QUIET_MODULES, buildCsv, cardFileName, cardUrl, toPixels, type Placement } from './cardDesign';

export interface DesignImage {
  image: HTMLImageElement;
  width: number;
  height: number;
}

export interface ExportCard {
  serial: string;
  card_uuid: string;
}

export function loadDesignImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('That file could not be read as an image. Use PNG, JPG or SVG.'));
    };
    img.src = url;
  });
}

/** Draws one card: the artwork, then the QR square on its white quiet zone, then the serial text. */
export async function renderCard(
  design: DesignImage,
  placement: Placement | null,
  card: ExportCard,
  baseUrl: string,
): Promise<Blob> {
  const canvas = document.createElement('canvas');
  canvas.width = design.width;
  canvas.height = design.height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('This browser cannot draw the card images.');
  ctx.drawImage(design.image, 0, 0, design.width, design.height);

  if (placement) {
    const px = toPixels(placement, design.width, design.height);

    const qrCanvas = document.createElement('canvas');
    await QRCode.toCanvas(qrCanvas, cardUrl(baseUrl, card.card_uuid), {
      errorCorrectionLevel: 'M',
      margin: QR_QUIET_MODULES,
      width: px.qrSize,
      color: { dark: '#000000', light: '#ffffff' },
    });
    ctx.drawImage(qrCanvas, px.qrX, px.qrY, px.qrSize, px.qrSize);

    ctx.fillStyle = placement.textColor;
    ctx.font = `700 ${px.textPx}px monospace`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(card.serial, px.textX, px.textY);
  }

  return new Promise((resolve, reject) => {
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not create the PNG.'))), 'image/png');
  });
}

export interface ExportInput {
  cards: ExportCard[];
  baseUrl: string;
  front: DesignImage;
  frontPlacement: Placement | null;
  back?: DesignImage | null;
  backPlacement?: Placement | null;
  onProgress?: (done: number, total: number) => void;
}

/** Builds the ZIP the print shop receives. Yields to the browser between cards so the page stays responsive. */
export async function buildExportZip(input: ExportInput): Promise<Blob> {
  const zip = new JSZip();
  const total = input.cards.length;

  for (let i = 0; i < total; i++) {
    const card = input.cards[i];
    zip.file(cardFileName(card.serial, 'front'), await renderCard(input.front, input.frontPlacement, card, input.baseUrl));
    if (input.back) {
      zip.file(cardFileName(card.serial, 'back'), await renderCard(input.back, input.backPlacement ?? null, card, input.baseUrl));
    }
    input.onProgress?.(i + 1, total);
    await new Promise((r) => setTimeout(r, 0));
  }

  zip.file('cards.csv', buildCsv(input.cards, input.baseUrl));
  return zip.generateAsync({ type: 'blob' });
}

export function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  try {
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
}
