import jsQR from 'jsqr';
import QRCode from 'qrcode';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cameraAvailable, cardUuidFromQr, startQrScan } from './qrScan';

const ID = '3f2a9c10-1b2d-4e5f-8a6b-7c8d9e0f1a2b';

describe('cardUuidFromQr', () => {
  it('reads the id out of a printed card link', () => {
    expect(cardUuidFromQr(`https://toms.example.com/r/${ID}`)).toBe(ID);
  });

  it('works with a port, query string, trailing slash or fragment', () => {
    expect(cardUuidFromQr(`http://192.168.1.2:5173/r/${ID}?x=1`)).toBe(ID);
    expect(cardUuidFromQr(`https://t.example/r/${ID}/`)).toBe(ID);
    expect(cardUuidFromQr(`https://t.example/r/${ID}#top`)).toBe(ID);
  });

  it('accepts a bare id, with surrounding spaces and any letter case', () => {
    expect(cardUuidFromQr(`  ${ID.toUpperCase()}  `)).toBe(ID);
  });

  it('rejects anything that is not a card link', () => {
    expect(cardUuidFromQr('https://example.com/')).toBeNull();
    expect(cardUuidFromQr('TOMS-0001')).toBeNull();
    expect(cardUuidFromQr(`https://example.com/receipt/${ID}`)).toBeNull();
    expect(cardUuidFromQr(`https://example.com/r/${ID}extra`)).toBeNull();
    expect(cardUuidFromQr('')).toBeNull();
  });
});

describe('camera access', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('reports no camera when the browser has none', () => {
    vi.stubGlobal('navigator', {});
    expect(cameraAvailable()).toBe(false);
  });

  it('explains a missing camera instead of throwing', async () => {
    vi.stubGlobal('navigator', {});
    const onError = vi.fn();
    const stop = await startQrScan(document.createElement('video'), vi.fn(), onError);
    expect(onError).toHaveBeenCalledWith(expect.stringMatching(/cannot open the camera/));
    expect(() => stop()).not.toThrow();
  });

  it('explains a blocked camera permission', async () => {
    const denied = Object.assign(new Error('denied'), { name: 'NotAllowedError' });
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn().mockRejectedValue(denied) } });
    const onError = vi.fn();
    await startQrScan(document.createElement('video'), vi.fn(), onError);
    expect(onError).toHaveBeenCalledWith(expect.stringMatching(/blocked/));
  });
});

describe('reading a real QR code', () => {
  it('decodes a card QR drawn like the exported card and finds the card id', () => {
    const url = `https://toms.example.com/r/${ID}`;
    const qr = QRCode.create(url, { errorCorrectionLevel: 'M' });
    const n = qr.modules.size;
    const scale = 8;
    const border = 4 * scale;
    const size = n * scale + border * 2;
    const data = new Uint8ClampedArray(size * size * 4).fill(255);
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        if (!qr.modules.get(x, y)) continue;
        for (let dy = 0; dy < scale; dy++) {
          for (let dx = 0; dx < scale; dx++) {
            const i = ((border + y * scale + dy) * size + border + x * scale + dx) * 4;
            data[i] = data[i + 1] = data[i + 2] = 0;
          }
        }
      }
    }
    const decoded = jsQR(data, size, size, { inversionAttempts: 'dontInvert' });
    expect(decoded?.data).toBe(url);
    expect(cardUuidFromQr(decoded?.data ?? '')).toBe(ID);
  });
});
