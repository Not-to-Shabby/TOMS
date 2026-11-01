import type { PdfArt } from './sheetPdf';

export interface ArtSource {
  file: File;
  image: HTMLImageElement;
  width: number;
  height: number;
}

function rasterize(art: ArtSource): () => Promise<Uint8Array> {
  return () =>
    new Promise((resolve, reject) => {
      const canvas = document.createElement('canvas');
      canvas.width = art.width;
      canvas.height = art.height;
      const ctx = canvas.getContext('2d');
      if (!ctx) return reject(new Error('This browser cannot prepare the design for the PDF.'));
      ctx.drawImage(art.image, 0, 0, art.width, art.height);
      canvas.toBlob(async (b) => {
        if (!b) return reject(new Error('Could not prepare the design for the PDF.'));
        resolve(new Uint8Array(await b.arrayBuffer()));
      }, 'image/png');
    });
}

/**
 * PNG and JPG are placed in the PDF as they are, so they keep their full quality. Anything else (SVG, WebP)
 * is drawn to a PNG at the design's output size first.
 */
export async function toPdfArt(art: ArtSource): Promise<PdfArt> {
  const type = art.file.type;
  const raster = rasterize(art);
  if (type === 'image/png' || type === 'image/jpeg') {
    return {
      bytes: new Uint8Array(await art.file.arrayBuffer()),
      kind: type === 'image/png' ? 'png' : 'jpg',
      width: art.width,
      height: art.height,
      rasterize: raster,
    };
  }
  return { bytes: await raster(), kind: 'png', width: art.width, height: art.height };
}

/** True when the card shape differs from the design's shape enough to visibly stretch it. */
export function shapeMismatch(cardW: number, cardH: number, artW: number, artH: number): number {
  if (!(cardW > 0 && cardH > 0 && artW > 0 && artH > 0)) return 0;
  return Math.abs(cardW / cardH / (artW / artH) - 1);
}
